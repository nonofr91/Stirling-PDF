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
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
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

        /** Image quad in page space: llx, lly, urx, ury — where the object lands on the page. */
        final float[] bounds;

        ImageUse(
                double effectiveDpi,
                boolean softMasked,
                String label,
                float[] bounds,
                boolean technical) {
            this.effectiveDpi = effectiveDpi;
            this.softMasked = softMasked;
            this.colorSpaceLabel = label;
            this.bounds = bounds;
            this.technical = technical;
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

        PaintedArea(String label, float[] bounds, boolean technical) {
            this.label = label;
            this.bounds = bounds;
            this.technical = technical;
        }
    }

    private static final int MAX_PAINT_AREAS = 400;

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

    /** Open marked-content contexts; null entries stand for non-OC sequences. */
    private final Deque<PDPropertyList> ocgStack = new LinkedList<>();

    // Bounds of the path under construction, in page space.
    private float pathMinX;
    private float pathMinY;
    private float pathMaxX;
    private float pathMaxY;
    private boolean pathHasPoints;

    PreflightGraphicsEngine(PDPage page) {
        super(page);
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
            PDTextState ts = getGraphicsState().getTextState();
            RenderingMode mode = ts != null ? ts.getRenderingMode() : null;
            PDColorSpace paintCs =
                    mode != null && mode.isStroke()
                            ? getGraphicsState().getStrokingColorSpace()
                            : getGraphicsState().getNonStrokingColorSpace();
            recordPainted(paintCs, bounds);
            checkTransparency(bounds, isTechnicalPaint(paintCs));
        }
        super.showGlyph(textRenderingMatrix, font, code, displacement);
    }

    @Override
    public void drawImage(PDImage pdImage) throws IOException {
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
        try {
            PDColorSpace cs = pdImage.getColorSpace();
            technical = technical || isTechnicalPaint(cs);
            label = recordPainted(cs, bounds);
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
        images.add(new ImageUse(dpi, smasked, label, bounds, technical));
        checkTransparency(bounds, technical);
    }

    @Override
    public void strokePath() throws IOException {
        float[] bounds = takePathBounds();
        PDColorSpace cs = getGraphicsState().getStrokingColorSpace();
        boolean technical = isTechnicalPaint(cs);
        recordPainted(cs, bounds);
        recordStrokeWidth(bounds, technical);
        checkTransparency(bounds, technical);
    }

    @Override
    public void fillPath(int windingRule) throws IOException {
        float[] bounds = takePathBounds();
        PDColorSpace cs = getGraphicsState().getNonStrokingColorSpace();
        recordPainted(cs, bounds);
        checkTransparency(bounds, isTechnicalPaint(cs));
    }

    @Override
    public void fillAndStrokePath(int windingRule) throws IOException {
        float[] bounds = takePathBounds();
        PDColorSpace fillCs = getGraphicsState().getNonStrokingColorSpace();
        PDColorSpace strokeCs = getGraphicsState().getStrokingColorSpace();
        boolean fillTechnical = isTechnicalPaint(fillCs);
        boolean strokeTechnical = isTechnicalPaint(strokeCs);
        recordPainted(fillCs, bounds);
        recordPainted(strokeCs, bounds);
        recordStrokeWidth(bounds, strokeTechnical);
        checkTransparency(bounds, fillTechnical && strokeTechnical);
    }

    @Override
    public void shadingFill(COSName shadingName) throws IOException {
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
        if (bounds != null && paintAreas.size() < MAX_PAINT_AREAS) {
            paintAreas.add(new PaintedArea(label, bounds, technical));
        }
        return label;
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
