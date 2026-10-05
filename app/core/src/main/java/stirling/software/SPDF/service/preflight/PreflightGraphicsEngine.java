package stirling.software.SPDF.service.preflight;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceN;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDICCBased;
import org.apache.pdfbox.pdmodel.graphics.color.PDIndexed;
import org.apache.pdfbox.pdmodel.graphics.color.PDLab;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.color.PDSeparation;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup.RenderState;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentMembershipDictionary;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.PDTextState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.rendering.RenderDestination;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/**
 * Walks one page's painted content — including form XObjects, Type3 glyphs and soft-mask groups,
 * which {@link PDFGraphicsStreamEngine} recurses into on its own — and records what a print
 * preflight needs: fonts actually used, effective image resolution, stroke widths, paint color
 * spaces and live transparency.
 */
final class PreflightGraphicsEngine extends PDFGraphicsStreamEngine {

    /** A font as actually used by text ops; the service fills {@link #pages}. */
    static final class FontUse {
        final String name;
        final String subType;
        final boolean embedded;
        final boolean type3;
        final Set<Integer> pages = new TreeSet<>();

        /** A few glyph boxes (page space) so unembedded/Type3 text can be located. */
        final List<float[]> bounds = new ArrayList<>();

        FontUse(PDFont font) {
            String base = font.getName();
            this.name = base != null ? base : font.getSubType();
            this.subType = font.getSubType();
            this.type3 = font instanceof PDType3Font;
            this.embedded = font.isEmbedded();
        }
    }

    static final class ImageUse {
        final double effectiveDpi;
        final boolean softMasked;
        final String colorSpaceLabel;
        final boolean technical;
        final int bitsPerComponent;

        /** Image quad in page space: llx, lly, urx, ury — where the object lands on the page. */
        final float[] bounds;

        ImageUse(
                double effectiveDpi,
                boolean softMasked,
                String label,
                float[] bounds,
                boolean technical,
                int bitsPerComponent) {
            this.effectiveDpi = effectiveDpi;
            this.softMasked = softMasked;
            this.colorSpaceLabel = label;
            this.bounds = bounds;
            this.technical = technical;
            this.bitsPerComponent = bitsPerComponent;
        }
    }

    /** One glyph cell below {@link #TEXT_RECORD_MAX_PT}: size, paint and location. */
    static final class TextUse {
        final float fontSize;
        final float[] bounds;
        final PDColor color;

        TextUse(float fontSize, float[] bounds, PDColor color) {
            this.fontSize = fontSize;
            this.bounds = bounds;
            this.color = color;
        }
    }

    /** One stroked path: effective width and its page-space bounds (llx, lly, urx, ury). */
    static final class StrokeUse {
        final float widthPt;
        final float[] bounds;
        final boolean technical;

        StrokeUse(float widthPt, float[] bounds, boolean technical) {
            this.widthPt = widthPt;
            this.bounds = bounds;
            this.technical = technical;
        }
    }

    /** Where a color space or live transparency was painted — page-space bounds or a glyph box. */
    static final class PaintedArea {
        final String label;
        final float[] bounds;
        final boolean technical;

        /** Total ink coverage of the paint color in percent — NaN when not measured. */
        final float totalInk;

        PaintedArea(String label, float[] bounds, boolean technical) {
            this(label, bounds, technical, Float.NaN);
        }

        PaintedArea(String label, float[] bounds, boolean technical, float totalInk) {
            this.label = label;
            this.bounds = bounds;
            this.technical = technical;
            this.totalInk = totalInk;
        }
    }

    /**
     * Bounds of everything already painted on the page. Overprint findings only make sense when
     * something sits underneath: a knockout black object over bare paper prints the same. White
     * paint counts as no ink (it erases); technical and invisible paint never count.
     */
    private record Underlying(float[] bounds, boolean hasNonBlackInk) {}

    private static final int MAX_PAINT_AREAS = 400;

    /** Text cells recorded for small/rich-black checks — larger sizes skip the per-glyph list. */
    private static final float TEXT_RECORD_MAX_PT = 24f;

    private static final int MAX_TEXT_USES = 400;
    private static final int MAX_UNDERLYING = 2000;

    /** Object-level TAC floor for recording: a stricter request threshold lowers it further. */
    private static final float INK_RECORD_PERCENT = 250f;

    /** Component sums below this are white; single-channel gray above it is white too. */
    private static final float WHITE_EPSILON = 0.04f;

    /** C/M/Y at or under this do not count as chromatic ink for overprint purposes. */
    private static final float CHROMATIC_EPSILON = 0.02f;

    private static final float BLACK_MIN_TINT = 0.5f;

    private static final Set<String> BLACK_COLORANTS =
            Set.of("black", "noir", "schwarz", "nero", "preto", "negro", "svart");

    /** Spot colorants that always mean marks or finishing, never ink on the artwork. */
    private static final Set<String> REGISTRATION_COLORANTS = Set.of("all", "registration");

    /** Word-level finishing vocabulary — tokens, not substrings, so "Sunset" can't match "cut". */
    private static final Set<String> TECHNICAL_TOKENS =
            Set.of(
                    "cut",
                    "cutcontour",
                    "contour",
                    "die",
                    "dieline",
                    "diecut",
                    "kiss",
                    "kisscut",
                    "thru",
                    "thrucut",
                    "through",
                    "partialcut",
                    "perfo",
                    "perforation",
                    "fold",
                    "crease",
                    "creasing",
                    "score",
                    "scoring",
                    "rainage",
                    "pli",
                    "decoupe",
                    "découpe",
                    "varnish",
                    "vernis",
                    "foil",
                    "hotfoil",
                    "coldfoil",
                    "emboss",
                    "embossing",
                    "deboss",
                    "debossing",
                    "laser",
                    "lasercut",
                    "pounce",
                    "silhouette",
                    "procut",
                    "stanze",
                    "fustell",
                    "troquel",
                    "druckbett",
                    "spotuv",
                    "scodix",
                    "laminate",
                    "fraisage",
                    "milling",
                    "perf",
                    "glitter",
                    "primer",
                    "white",
                    "blanc");

    private static final Pattern TOKEN_SEPARATOR = Pattern.compile("[^a-z0-9àâäçéèêëîïôöùûü]+");

    private static final int MAX_OCMD_DEPTH = 8;

    private final Map<String, FontUse> fonts = new LinkedHashMap<>();
    private final Map<String, Integer> colorSpaceCounts = new LinkedHashMap<>();
    private final Set<String> spotColors = new LinkedHashSet<>();
    private final List<ImageUse> images = new ArrayList<>();
    private final List<StrokeUse> strokes = new ArrayList<>();
    private final List<PaintedArea> paintAreas = new ArrayList<>();
    private final List<PaintedArea> alphaAreas = new ArrayList<>();
    private boolean transparencyUsed;
    private boolean optionalContentUsed;
    private final Set<String> technicalSeparations = new LinkedHashSet<>();

    private final List<PaintedArea> whiteOverprintAreas = new ArrayList<>();
    private final List<PaintedArea> knockoutBlackAreas = new ArrayList<>();
    private final List<PaintedArea> inkAreas = new ArrayList<>();
    private final List<PaintedArea> registrationAreas = new ArrayList<>();
    private final List<PaintedArea> outsidePageAreas = new ArrayList<>();
    private final List<TextUse> textUses = new ArrayList<>();
    private final List<Underlying> underlying = new ArrayList<>();
    private float maxInkCoverage;
    private float minFontSize = Float.NaN;
    private int paintedOps;
    private boolean invisibleTextUsed;
    private boolean patternUsed;
    private boolean shadingUsed;
    private final PDRectangle cropBox;
    private final float inkRecordFloor;

    /** Open marked-content contexts; null entries stand for non-OC sequences. */
    private final Deque<PDPropertyList> ocgStack = new LinkedList<>();

    // Bounds of the path under construction, in page space.
    private float pathMinX;
    private float pathMinY;
    private float pathMaxX;
    private float pathMaxY;
    private boolean pathHasPoints;

    /**
     * @param inkRecordFloor request's TAC threshold — the recording floor must sit at or under it
     *     so objects just over the user limit are not filtered out before the check runs
     */
    PreflightGraphicsEngine(PDPage page, float inkRecordFloor) {
        super(page);
        this.cropBox = page.getCropBox();
        this.inkRecordFloor = Math.min(INK_RECORD_PERCENT, inkRecordFloor);
    }

    Map<String, FontUse> getFonts() {
        return fonts;
    }

    Map<String, Integer> getColorSpaceCounts() {
        return colorSpaceCounts;
    }

    Set<String> getSpotColors() {
        return spotColors;
    }

    List<ImageUse> getImages() {
        return images;
    }

    List<StrokeUse> getStrokes() {
        return strokes;
    }

    List<PaintedArea> getPaintAreas() {
        return paintAreas;
    }

    List<PaintedArea> getAlphaAreas() {
        return alphaAreas;
    }

    boolean isTransparencyUsed() {
        return transparencyUsed;
    }

    boolean isOptionalContentUsed() {
        return optionalContentUsed;
    }

    Set<String> getTechnicalSeparations() {
        return technicalSeparations;
    }

    List<PaintedArea> getWhiteOverprintAreas() {
        return whiteOverprintAreas;
    }

    List<PaintedArea> getKnockoutBlackAreas() {
        return knockoutBlackAreas;
    }

    List<PaintedArea> getInkAreas() {
        return inkAreas;
    }

    List<PaintedArea> getRegistrationAreas() {
        return registrationAreas;
    }

    List<PaintedArea> getOutsidePageAreas() {
        return outsidePageAreas;
    }

    List<TextUse> getTextUses() {
        return textUses;
    }

    float getMaxInkCoverage() {
        return maxInkCoverage;
    }

    float getMinFontSize() {
        return minFontSize;
    }

    int getPaintedOps() {
        return paintedOps;
    }

    boolean isInvisibleTextUsed() {
        return invisibleTextUsed;
    }

    boolean isPatternUsed() {
        return patternUsed;
    }

    boolean isShadingUsed() {
        return shadingUsed;
    }

    @Override
    protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement)
            throws IOException {
        if (font != null) {
            FontUse use =
                    fonts.computeIfAbsent(
                            font.getName() + "|" + font.getSubType(), k -> new FontUse(font));
            // The glyph box is roughly one em around the position — enough to locate the text.
            float[] bounds = matrixBounds(textRenderingMatrix);
            if (use.bounds.size() < 8 && bounds != null) {
                use.bounds.add(bounds);
            }
            PDGraphicsState state = getGraphicsState();
            PDTextState ts = state.getTextState();
            RenderingMode mode = ts != null ? ts.getRenderingMode() : null;
            boolean stroked = mode != null && mode.isStroke();
            boolean filled = mode == null || mode.isFill();
            PDColorSpace paintCs =
                    stroked && !filled
                            ? state.getStrokingColorSpace()
                            : state.getNonStrokingColorSpace();
            if (filled || stroked) {
                paintedOps++;
                recordPainted(paintCs, bounds);
                boolean technical = isTechnicalPaint(paintCs);
                checkTransparency(bounds, technical);

                float fontSize =
                        (float)
                                Math.min(
                                        Math.abs(textRenderingMatrix.getScalingFactorX()),
                                        Math.abs(textRenderingMatrix.getScalingFactorY()));
                if (Float.isNaN(minFontSize) || fontSize < minFontSize) {
                    minFontSize = fontSize;
                }
                PDColor color =
                        stroked && !filled ? state.getStrokingColor() : state.getNonStrokingColor();
                boolean overprint =
                        stroked && !filled ? state.isOverprint() : state.isNonStrokingOverprint();
                boolean overEarlier = hasUnderlyingNonBlackInk(bounds);
                if (!technical) {
                    checkWhiteOverprint(color, overprint, bounds, "text");
                    checkKnockoutBlack(color, overprint, bounds, "text", overEarlier);
                    recordInk(paintCs, color, bounds);
                    if (fontSize < TEXT_RECORD_MAX_PT && textUses.size() < MAX_TEXT_USES) {
                        textUses.add(new TextUse(fontSize, bounds, color));
                    }
                }
                recordUnderlying(bounds, color, technical || isWhiteOverprint(color, overprint));
            } else {
                invisibleTextUsed = true;
            }
        }
        super.showGlyph(textRenderingMatrix, font, code, displacement);
    }

    @Override
    public void drawImage(PDImage pdImage) throws IOException {
        paintedOps++;
        PDGraphicsState state = getGraphicsState();
        Matrix ctm = state.getCurrentTransformationMatrix();
        double dpi = Double.NaN;
        float[] bounds = null;
        if (ctm != null) {
            double sx = ctm.getScalingFactorX();
            double sy = ctm.getScalingFactorY();
            if (sx > 0 && sy > 0) {
                dpi = Math.min(pdImage.getWidth() * 72.0 / sx, pdImage.getHeight() * 72.0 / sy);
            }
            // The image unit square under the CTM is the quad painted on the page.
            bounds = matrixBounds(ctm);
        }
        boolean smasked = pdImage instanceof PDImageXObject xo && xo.getSoftMask() != null;
        boolean technical = isTechnicalContext();
        String label = null;
        PDColorSpace imageCs = null;
        try {
            imageCs = pdImage.getColorSpace();
            technical = technical || isTechnicalPaint(imageCs);
            label = recordPainted(imageCs, bounds);
            if (pdImage.isStencil()) {
                technical = technical || isTechnicalPaint(state.getNonStrokingColorSpace());
                recordPainted(state.getNonStrokingColorSpace(), bounds);
            }
        } catch (IOException ignored) {
            // unresolvable image color space is surfaced through other checks
        }
        if (smasked) {
            if (!technical) {
                transparencyUsed = true;
            }
            recordAlpha(bounds, "soft-masked image", technical);
        }
        int bpc = pdImage.getBitsPerComponent();
        images.add(new ImageUse(dpi, smasked, label, bounds, technical, bpc));
        checkTransparency(bounds, technical);
        // An image is opaque paint: it covers whatever it overlaps. Gray-only content carries no
        // chromatic ink for knockout-black purposes; everything else counts as coloured ink.
        recordUnderlyingOpaque(bounds, imageCs, technical || smasked);
    }

    @Override
    public void strokePath() throws IOException {
        paintedOps++;
        float[] bounds = takePathBounds();
        PDGraphicsState state = getGraphicsState();
        PDColorSpace cs = state.getStrokingColorSpace();
        boolean technical = isTechnicalPaint(cs);
        recordPainted(cs, bounds);
        recordStrokeWidth(bounds, technical);
        checkTransparency(bounds, technical);
        if (!technical) {
            PDColor color = state.getStrokingColor();
            boolean overprint = state.isOverprint();
            boolean overEarlier = hasUnderlyingNonBlackInk(bounds);
            checkWhiteOverprint(color, overprint, bounds, "stroke");
            checkKnockoutBlack(color, overprint, bounds, "stroke", overEarlier);
            recordInk(cs, color, bounds);
            recordUnderlying(bounds, color, isWhiteOverprint(color, overprint));
        }
    }

    @Override
    public void fillPath(int windingRule) throws IOException {
        paintedOps++;
        float[] bounds = takePathBounds();
        PDGraphicsState state = getGraphicsState();
        PDColorSpace cs = state.getNonStrokingColorSpace();
        boolean technical = isTechnicalPaint(cs);
        recordPainted(cs, bounds);
        checkTransparency(bounds, technical);
        if (!technical) {
            PDColor color = state.getNonStrokingColor();
            boolean overprint = state.isNonStrokingOverprint();
            boolean overEarlier = hasUnderlyingNonBlackInk(bounds);
            checkWhiteOverprint(color, overprint, bounds, "fill");
            checkKnockoutBlack(color, overprint, bounds, "fill", overEarlier);
            recordInk(cs, color, bounds);
            recordUnderlying(bounds, color, isWhiteOverprint(color, overprint));
        }
    }

    @Override
    public void fillAndStrokePath(int windingRule) throws IOException {
        paintedOps++;
        float[] bounds = takePathBounds();
        PDGraphicsState state = getGraphicsState();
        PDColorSpace fillCs = state.getNonStrokingColorSpace();
        PDColorSpace strokeCs = state.getStrokingColorSpace();
        boolean fillTechnical = isTechnicalPaint(fillCs);
        boolean strokeTechnical = isTechnicalPaint(strokeCs);
        recordPainted(fillCs, bounds);
        recordPainted(strokeCs, bounds);
        recordStrokeWidth(bounds, strokeTechnical);
        checkTransparency(bounds, fillTechnical && strokeTechnical);
        if (!fillTechnical) {
            PDColor color = state.getNonStrokingColor();
            boolean overprint = state.isNonStrokingOverprint();
            boolean overEarlier = hasUnderlyingNonBlackInk(bounds);
            checkWhiteOverprint(color, overprint, bounds, "fill");
            checkKnockoutBlack(color, overprint, bounds, "fill", overEarlier);
            recordInk(fillCs, color, bounds);
        }
        if (!strokeTechnical) {
            PDColor color = state.getStrokingColor();
            boolean overprint = state.isOverprint();
            boolean overEarlier = hasUnderlyingNonBlackInk(bounds);
            checkWhiteOverprint(color, overprint, bounds, "stroke");
            checkKnockoutBlack(color, overprint, bounds, "stroke", overEarlier);
            recordInk(strokeCs, color, bounds);
        }
        if (!fillTechnical) {
            recordUnderlying(
                    bounds,
                    state.getNonStrokingColor(),
                    isWhiteOverprint(state.getNonStrokingColor(), state.isNonStrokingOverprint()));
        } else if (!strokeTechnical) {
            recordUnderlying(
                    bounds,
                    state.getStrokingColor(),
                    isWhiteOverprint(state.getStrokingColor(), state.isOverprint()));
        }
    }

    @Override
    public void shadingFill(COSName shadingName) throws IOException {
        paintedOps++;
        shadingUsed = true;
        boolean technical = isTechnicalContext();
        try {
            PDShading shading = getResources().getShading(shadingName);
            if (shading != null) {
                technical = technical || isTechnicalPaint(shading.getColorSpace());
                recordPainted(shading.getColorSpace(), null);
            }
        } catch (IOException ignored) {
            // a shading that cannot be resolved is reported through the other checks
        }
        checkTransparency(null, technical);
    }

    @Override
    public void showTransparencyGroup(PDTransparencyGroup form) throws IOException {
        if (!isTechnicalContext()) {
            transparencyUsed = true;
        }
        super.showTransparencyGroup(form);
    }

    // Marked content must balance within one stream; a malformed child stream that
    // leaves a sequence open must not leak its layer onto the content that follows.
    @Override
    public void showForm(PDFormXObject form) throws IOException {
        int depth = ocgStack.size();
        try {
            super.showForm(form);
        } finally {
            restoreOcgDepth(depth);
        }
    }

    @Override
    protected void processTransparencyGroup(PDTransparencyGroup group) throws IOException {
        int depth = ocgStack.size();
        try {
            super.processTransparencyGroup(group);
        } finally {
            restoreOcgDepth(depth);
        }
    }

    @Override
    protected void showType3Glyph(
            Matrix textRenderingMatrix, PDType3Font font, int code, Vector displacement)
            throws IOException {
        int depth = ocgStack.size();
        try {
            super.showType3Glyph(textRenderingMatrix, font, code, displacement);
        } finally {
            restoreOcgDepth(depth);
        }
    }

    private void restoreOcgDepth(int depth) {
        while (ocgStack.size() > depth) {
            ocgStack.pop();
        }
    }

    @Override
    public void beginMarkedContentSequence(COSName tag, COSDictionary properties) {
        if (COSName.OC.equals(tag)) {
            optionalContentUsed = true;
            ocgStack.push(resolvePropertyList(properties));
        } else {
            ocgStack.push(null);
        }
        super.beginMarkedContentSequence(tag, properties);
    }

    @Override
    public void endMarkedContentSequence() {
        if (!ocgStack.isEmpty()) {
            ocgStack.pop();
        }
        super.endMarkedContentSequence();
    }

    private static PDPropertyList resolvePropertyList(COSDictionary properties) {
        return properties == null ? null : PDPropertyList.create(properties);
    }

    /**
     * True while painting inside a layer meant for finishing — an OCG named like a cut path,
     * flagged off for the print destination, or an OCMD grouping only such layers.
     */
    private boolean isTechnicalContext() {
        for (PDPropertyList pl : ocgStack) {
            if (pl != null && isTechnicalPropertyList(pl, 0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTechnicalPropertyList(PDPropertyList pl, int depth) {
        if (pl instanceof PDOptionalContentGroup ocg) {
            return isTechnicalColorant(ocg.getName()) || isPrintOff(ocg);
        }
        if (pl instanceof PDOptionalContentMembershipDictionary ocmd && depth < MAX_OCMD_DEPTH) {
            List<PDPropertyList> members = ocmd.getOCGs();
            return !members.isEmpty()
                    && members.stream().allMatch(m -> isTechnicalPropertyList(m, depth + 1));
        }
        return false;
    }

    private static boolean isPrintOff(PDOptionalContentGroup ocg) {
        try {
            return RenderState.OFF.equals(ocg.getRenderState(RenderDestination.PRINT));
        } catch (RuntimeException e) {
            // a state name outside the RenderState enum must not sink the whole page pass
            return false;
        }
    }

    static boolean isTechnicalColorant(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.trim().toLowerCase(Locale.ROOT);
        if (REGISTRATION_COLORANTS.contains(lower)) {
            return true;
        }
        if (TECHNICAL_TOKENS.contains(TOKEN_SEPARATOR.matcher(lower).replaceAll(""))) {
            return true;
        }
        for (String token : TOKEN_SEPARATOR.split(lower)) {
            if (TECHNICAL_TOKENS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void clip(int windingRule) throws IOException {
        pathHasPoints = false;
    }

    @Override
    public void moveTo(float x, float y) throws IOException {
        trackPoint(x, y);
    }

    @Override
    public void lineTo(float x, float y) throws IOException {
        trackPoint(x, y);
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3)
            throws IOException {
        trackPoint(x1, y1);
        trackPoint(x2, y2);
        trackPoint(x3, y3);
    }

    @Override
    public Point2D getCurrentPoint() throws IOException {
        return new Point2D.Float();
    }

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) throws IOException {
        trackPoint((float) p0.getX(), (float) p0.getY());
        trackPoint((float) p2.getX(), (float) p2.getY());
    }

    @Override
    public void closePath() throws IOException {}

    @Override
    public void endPath() throws IOException {
        pathHasPoints = false;
    }

    private void trackPoint(float x, float y) {
        if (!pathHasPoints) {
            pathMinX = pathMaxX = x;
            pathMinY = pathMaxY = y;
            pathHasPoints = true;
        } else {
            pathMinX = Math.min(pathMinX, x);
            pathMaxX = Math.max(pathMaxX, x);
            pathMinY = Math.min(pathMinY, y);
            pathMaxY = Math.max(pathMaxY, y);
        }
    }

    private float[] takePathBounds() {
        if (!pathHasPoints) {
            return null;
        }
        pathHasPoints = false;
        return new float[] {pathMinX, pathMinY, pathMaxX, pathMaxY};
    }

    /** Bounding box of the unit square transformed by {@code m}: an image or ~1em glyph cell. */
    private static float[] matrixBounds(Matrix m) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (float[] corner : new float[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}}) {
            Point2D p = m.transformPoint(corner[0], corner[1]);
            minX = Math.min(minX, (float) p.getX());
            maxX = Math.max(maxX, (float) p.getX());
            minY = Math.min(minY, (float) p.getY());
            maxY = Math.max(maxY, (float) p.getY());
        }
        return new float[] {minX, minY, maxX, maxY};
    }

    private void recordStrokeWidth(float[] bounds, boolean technical) {
        PDGraphicsState state = getGraphicsState();
        Matrix ctm = state.getCurrentTransformationMatrix();
        float scale = 1;
        if (ctm != null) {
            scale =
                    (float)
                            Math.min(
                                    Math.abs(ctm.getScalingFactorX()),
                                    Math.abs(ctm.getScalingFactorY()));
        }
        strokes.add(new StrokeUse(state.getLineWidth() * scale, bounds, technical));
    }

    private void checkTransparency(float[] bounds, boolean technical) {
        PDGraphicsState state = getGraphicsState();
        if (state.getAlphaConstant() < 1
                || state.getNonStrokeAlphaConstant() < 1
                || (state.getBlendMode() != null && !BlendMode.NORMAL.equals(state.getBlendMode()))
                || state.getSoftMask() != null) {
            if (!technical) {
                transparencyUsed = true;
            }
            recordAlpha(bounds, "transparency", technical);
        }
    }

    private void recordAlpha(float[] bounds, String detail, boolean technical) {
        if (bounds != null && alphaAreas.size() < MAX_PAINT_AREAS) {
            alphaAreas.add(new PaintedArea(detail, bounds, technical));
        }
    }

    /**
     * A paint counts as technical when its colorant names are all finishing vocabulary
     * (registration marks, cut paths) or it sits on a layer turned off for print.
     */
    private boolean isTechnicalPaint(PDColorSpace cs) throws IOException {
        if (isTechnicalContext()) {
            return true;
        }
        if (cs instanceof PDSeparation sep) {
            return isTechnicalColorant(sep.getColorantName());
        }
        if (cs instanceof PDDeviceN devN) {
            List<String> names = devN.getColorantNames();
            return !names.isEmpty()
                    && names.stream().allMatch(PreflightGraphicsEngine::isTechnicalColorant);
        }
        return false;
    }

    private String recordPainted(PDColorSpace cs, float[] bounds) throws IOException {
        if (cs == null) {
            return null;
        }
        boolean technical = isTechnicalPaint(cs);
        String label = label(cs);
        if (!technical) {
            colorSpaceCounts.merge(label, 1, Integer::sum);
        }
        if (cs instanceof PDSeparation sep) {
            String name = sep.getColorantName();
            spotColors.add(name);
            if (isTechnicalColorant(name)) {
                technicalSeparations.add(name);
            }
        } else if (cs instanceof PDDeviceN devN) {
            List<String> names = devN.getColorantNames();
            spotColors.addAll(names);
            for (String name : names) {
                if (isTechnicalColorant(name)) {
                    technicalSeparations.add(name);
                }
            }
        }
        if (cs instanceof PDPattern) {
            patternUsed = true;
        }
        if (bounds != null && paintAreas.size() < MAX_PAINT_AREAS) {
            paintAreas.add(new PaintedArea(label, bounds, technical));
        }
        if (cs instanceof PDSeparation sep
                && isRegistrationColorant(sep.getColorantName())
                && bounds != null
                && registrationAreas.size() < MAX_PAINT_AREAS) {
            registrationAreas.add(new PaintedArea(label, bounds, technical));
        }
        if (!technical
                && bounds != null
                && outsideCrop(bounds)
                && outsidePageAreas.size() < MAX_PAINT_AREAS) {
            outsidePageAreas.add(new PaintedArea(label, bounds, false));
        }
        return label;
    }

    private static boolean isRegistrationColorant(String name) {
        return name != null
                && REGISTRATION_COLORANTS.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    private boolean outsideCrop(float[] b) {
        return cropBox != null
                && (b[2] < cropBox.getLowerLeftX()
                        || b[0] > cropBox.getUpperRightX()
                        || b[3] < cropBox.getLowerLeftY()
                        || b[1] > cropBox.getUpperRightY());
    }

    private static boolean overlaps(float[] a, float[] b) {
        return a[0] < b[2] && a[2] > b[0] && a[1] < b[3] && a[3] > b[1];
    }

    private boolean hasUnderlyingNonBlackInk(float[] bounds) {
        if (bounds == null) {
            return false;
        }
        for (Underlying u : underlying) {
            if (u.hasNonBlackInk() && overlaps(u.bounds(), bounds)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Everything painted leaves ink or erases what was under it — either way later objects sit on
     * it. Paint that deposits nothing (technical paint, white overprint, invisible text) is not a
     * backdrop a knockout object could reveal.
     */
    private void recordUnderlying(float[] bounds, PDColor color, boolean invisibleEffective) {
        if (bounds == null || invisibleEffective || underlying.size() >= MAX_UNDERLYING) {
            return;
        }
        underlying.add(new Underlying(bounds, hasNonBlackInk(color)));
    }

    private void recordUnderlyingOpaque(
            float[] bounds, PDColorSpace cs, boolean invisibleEffective) {
        if (bounds == null || invisibleEffective || underlying.size() >= MAX_UNDERLYING) {
            return;
        }
        underlying.add(new Underlying(bounds, cs != null && !(cs instanceof PDDeviceGray)));
    }

    private static boolean isWhiteOverprint(PDColor color, boolean overprint) {
        return overprint && isWhite(color);
    }

    private void checkWhiteOverprint(
            PDColor color, boolean overprint, float[] bounds, String kind) {
        if (bounds != null
                && isWhiteOverprint(color, overprint)
                && whiteOverprintAreas.size() < MAX_PAINT_AREAS) {
            whiteOverprintAreas.add(new PaintedArea(kind + " set to overprint", bounds, false));
        }
    }

    private void checkKnockoutBlack(
            PDColor color,
            boolean overprint,
            float[] bounds,
            String kind,
            boolean overEarlierPaint) {
        if (bounds == null
                || overprint
                || !overEarlierPaint
                || !isBlackOnly(color)
                || knockoutBlackAreas.size() >= MAX_PAINT_AREAS) {
            return;
        }
        String detail = kind;
        if (color.getColorSpace() instanceof PDDeviceGray) {
            detail += ", DeviceGray — overprint flag could not help anyway";
        }
        knockoutBlackAreas.add(new PaintedArea(detail, bounds, false));
    }

    private void recordInk(PDColorSpace cs, PDColor color, float[] bounds) {
        float tac = totalInk(color);
        if (Float.isNaN(tac)) {
            return;
        }
        if (tac > maxInkCoverage) {
            maxInkCoverage = tac;
        }
        if (bounds != null && tac > inkRecordFloor && inkAreas.size() < MAX_PAINT_AREAS) {
            inkAreas.add(
                    new PaintedArea(
                            labelSafe(cs) + " · " + Math.round(tac) + "%", bounds, false, tac));
        }
    }

    private static String labelSafe(PDColorSpace cs) {
        try {
            return cs != null ? label(cs) : "paint";
        } catch (IOException e) {
            return "paint";
        }
    }

    private static boolean isBlackColorant(String name) {
        return name != null && BLACK_COLORANTS.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    /** Every channel at ~zero tint — DeviceGray inverted: 1.0 is white there. */
    private static boolean isWhite(PDColor color) {
        if (color == null) {
            return false;
        }
        PDColorSpace cs = color.getColorSpace();
        float[] c = color.getComponents();
        if (cs instanceof PDDeviceGray) {
            return c[0] >= 1f - WHITE_EPSILON;
        }
        if (cs instanceof PDSeparation) {
            return c[0] <= WHITE_EPSILON;
        }
        if (cs instanceof PDDeviceCMYK || cs instanceof PDDeviceN) {
            for (float v : c) {
                if (v > WHITE_EPSILON) {
                    return false;
                }
            }
            return true;
        }
        int[] rgb = rgbComponents(color);
        return rgb != null && rgb[0] > 245 && rgb[1] > 245 && rgb[2] > 245;
    }

    /**
     * Dark paint carried by the K plate alone — the case where knockout leaves a white sliver if
     * registration slips. RGB-family "black" is multi-plate and goes through rich-black logic
     * instead.
     */
    private static boolean isBlackOnly(PDColor color) {
        if (color == null) {
            return false;
        }
        PDColorSpace cs = color.getColorSpace();
        float[] c = color.getComponents();
        if (cs instanceof PDDeviceCMYK) {
            return c[0] <= CHROMATIC_EPSILON
                    && c[1] <= CHROMATIC_EPSILON
                    && c[2] <= CHROMATIC_EPSILON
                    && c[3] > BLACK_MIN_TINT;
        }
        if (cs instanceof PDDeviceGray) {
            return c[0] < 1f - BLACK_MIN_TINT;
        }
        if (cs instanceof PDSeparation sep) {
            return isBlackColorant(sep.getColorantName()) && c[0] > BLACK_MIN_TINT;
        }
        return false;
    }

    /**
     * Whether the paint deposits any ink a black object could knock out: chromatic CMY, a non-black
     * separation, or RGB-family paint that is neither black-ish nor white.
     */
    private static boolean hasNonBlackInk(PDColor color) {
        if (color == null) {
            return false;
        }
        PDColorSpace cs = color.getColorSpace();
        float[] c = color.getComponents();
        if (cs instanceof PDDeviceCMYK) {
            return c[0] > CHROMATIC_EPSILON || c[1] > CHROMATIC_EPSILON || c[2] > CHROMATIC_EPSILON;
        }
        if (cs instanceof PDDeviceGray) {
            return false;
        }
        if (cs instanceof PDSeparation sep) {
            return !isBlackColorant(sep.getColorantName());
        }
        if (cs instanceof PDDeviceN devN) {
            List<String> names = devN.getColorantNames();
            for (int i = 0; i < names.size() && i < c.length; i++) {
                if (c[i] > CHROMATIC_EPSILON && !isBlackColorant(names.get(i))) {
                    return true;
                }
            }
            return false;
        }
        int[] rgb = rgbComponents(color);
        if (rgb == null) {
            return false;
        }
        if (rgb[0] < 26 && rgb[1] < 26 && rgb[2] < 26) {
            return false;
        }
        return rgb[0] < 229 || rgb[1] < 229 || rgb[2] < 229;
    }

    private static int[] rgbComponents(PDColor color) {
        try {
            int rgb = color.toRGB();
            return new int[] {(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Object-level total area coverage of a paint colour, percent. Direct colorant tints are summed
     * (CMYK, DeviceN), a separation contributes its tint on its own plate, and RGB-family colours
     * go through a naive RGB→CMYK split — what a RIP would lay down, approximately. This is
     * per-object, not the per-pixel effective coverage a render pass would measure.
     */
    private static float totalInk(PDColor color) {
        if (color == null) {
            return Float.NaN;
        }
        PDColorSpace cs = color.getColorSpace();
        float[] c = color.getComponents();
        if (cs instanceof PDDeviceCMYK || cs instanceof PDDeviceN) {
            float sum = 0;
            for (float v : c) {
                sum += v;
            }
            return sum * 100f;
        }
        if (cs instanceof PDSeparation) {
            return c[0] * 100f;
        }
        if (cs instanceof PDDeviceGray) {
            return (1f - c[0]) * 100f;
        }
        int[] rgb = rgbComponents(color);
        if (rgb == null) {
            return Float.NaN;
        }
        float r = rgb[0] / 255f;
        float g = rgb[1] / 255f;
        float b = rgb[2] / 255f;
        float k = 1f - Math.max(r, Math.max(g, b));
        if (k >= 0.999f) {
            return 100f;
        }
        float cmy = (1f - r - k) / (1f - k) + (1f - g - k) / (1f - k) + (1f - b - k) / (1f - k);
        return (cmy + k) * 100f;
    }

    private static String label(PDColorSpace cs) throws IOException {
        if (cs instanceof PDDeviceRGB) {
            return "DeviceRGB";
        }
        if (cs instanceof PDDeviceCMYK) {
            return "DeviceCMYK";
        }
        if (cs instanceof PDDeviceGray) {
            return "DeviceGray";
        }
        if (cs instanceof PDICCBased icc) {
            return switch (icc.getNumberOfComponents()) {
                case 1 -> "ICCBased Gray";
                case 3 -> "ICCBased RGB";
                case 4 -> "ICCBased CMYK";
                default -> "ICCBased " + icc.getNumberOfComponents() + "-component";
            };
        }
        if (cs instanceof PDSeparation sep) {
            return "Spot: " + sep.getColorantName();
        }
        if (cs instanceof PDDeviceN devN) {
            return "Spot: " + String.join(", ", devN.getColorantNames());
        }
        if (cs instanceof PDIndexed indexed) {
            PDColorSpace base = indexed.getBaseColorSpace();
            return "Indexed over " + (base != null ? label(base) : "unknown");
        }
        if (cs instanceof PDLab) {
            return "CIE Lab";
        }
        if (cs instanceof PDPattern) {
            return "Pattern";
        }
        String name = cs.getName();
        return name != null ? name : cs.getClass().getSimpleName();
    }
}
