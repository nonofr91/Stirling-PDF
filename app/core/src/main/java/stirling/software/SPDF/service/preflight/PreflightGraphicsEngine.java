package stirling.software.SPDF.service.preflight;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
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
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.PDTextState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
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

        ImageUse(double effectiveDpi, boolean softMasked, String label) {
            this.effectiveDpi = effectiveDpi;
            this.softMasked = softMasked;
            this.colorSpaceLabel = label;
        }
    }

    private final Map<String, FontUse> fonts = new LinkedHashMap<>();
    private final Map<String, Integer> colorSpaceCounts = new LinkedHashMap<>();
    private final Set<String> spotColors = new LinkedHashSet<>();
    private final List<ImageUse> images = new ArrayList<>();
    private final List<Float> effectiveStrokeWidths = new ArrayList<>();
    private boolean transparencyUsed;
    private boolean optionalContentUsed;

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

    List<Float> getEffectiveStrokeWidths() {
        return effectiveStrokeWidths;
    }

    boolean isTransparencyUsed() {
        return transparencyUsed;
    }

    boolean isOptionalContentUsed() {
        return optionalContentUsed;
    }

    @Override
    protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement)
            throws IOException {
        if (font != null) {
            fonts.computeIfAbsent(font.getName() + "|" + font.getSubType(), k -> new FontUse(font));
            PDTextState ts = getGraphicsState().getTextState();
            RenderingMode mode = ts != null ? ts.getRenderingMode() : null;
            if (mode != null && mode.isStroke()) {
                recordPainted(getGraphicsState().getStrokingColorSpace());
            } else {
                recordPainted(getGraphicsState().getNonStrokingColorSpace());
            }
            checkTransparency();
        }
        super.showGlyph(textRenderingMatrix, font, code, displacement);
    }

    @Override
    public void drawImage(PDImage pdImage) throws IOException {
        PDGraphicsState state = getGraphicsState();
        Matrix ctm = state.getCurrentTransformationMatrix();
        double dpi = Double.NaN;
        if (ctm != null) {
            double sx = ctm.getScalingFactorX();
            double sy = ctm.getScalingFactorY();
            if (sx > 0 && sy > 0) {
                dpi = Math.min(pdImage.getWidth() * 72.0 / sx, pdImage.getHeight() * 72.0 / sy);
            }
        }
        boolean smasked = pdImage instanceof PDImageXObject xo && xo.getSoftMask() != null;
        if (smasked) {
            transparencyUsed = true;
        }
        String label = null;
        try {
            label = recordPainted(pdImage.getColorSpace());
            if (pdImage.isStencil()) {
                recordPainted(state.getNonStrokingColorSpace());
            }
        } catch (IOException ignored) {
            // unresolvable image color space is surfaced through other checks
        }
        images.add(new ImageUse(dpi, smasked, label));
        checkTransparency();
    }

    @Override
    public void strokePath() throws IOException {
        recordPainted(getGraphicsState().getStrokingColorSpace());
        recordStrokeWidth();
        checkTransparency();
    }

    @Override
    public void fillPath(int windingRule) throws IOException {
        recordPainted(getGraphicsState().getNonStrokingColorSpace());
        checkTransparency();
    }

    @Override
    public void fillAndStrokePath(int windingRule) throws IOException {
        recordPainted(getGraphicsState().getNonStrokingColorSpace());
        recordPainted(getGraphicsState().getStrokingColorSpace());
        recordStrokeWidth();
        checkTransparency();
    }

    @Override
    public void shadingFill(COSName shadingName) throws IOException {
        try {
            PDShading shading = getResources().getShading(shadingName);
            if (shading != null) {
                recordPainted(shading.getColorSpace());
            }
        } catch (IOException ignored) {
            // a shading that cannot be resolved is reported through the other checks
        }
        checkTransparency();
    }

    @Override
    public void showTransparencyGroup(PDTransparencyGroup form) throws IOException {
        transparencyUsed = true;
        super.showTransparencyGroup(form);
    }

    @Override
    public void beginMarkedContentSequence(COSName tag, COSDictionary properties) {
        if (COSName.OC.equals(tag)) {
            optionalContentUsed = true;
        }
        super.beginMarkedContentSequence(tag, properties);
    }

    @Override
    public void clip(int windingRule) throws IOException {}

    @Override
    public void moveTo(float x, float y) throws IOException {}

    @Override
    public void lineTo(float x, float y) throws IOException {}

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3)
            throws IOException {}

    @Override
    public Point2D getCurrentPoint() throws IOException {
        return new Point2D.Float();
    }

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3)
            throws IOException {}

    @Override
    public void closePath() throws IOException {}

    @Override
    public void endPath() throws IOException {}

    private void recordStrokeWidth() {
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
        effectiveStrokeWidths.add(state.getLineWidth() * scale);
    }

    private void checkTransparency() {
        PDGraphicsState state = getGraphicsState();
        if (state.getAlphaConstant() < 1
                || state.getNonStrokeAlphaConstant() < 1
                || (state.getBlendMode() != null && !BlendMode.NORMAL.equals(state.getBlendMode()))
                || state.getSoftMask() != null) {
            transparencyUsed = true;
        }
    }

    private String recordPainted(PDColorSpace cs) throws IOException {
        if (cs == null) {
            return null;
        }
        String label = label(cs);
        colorSpaceCounts.merge(label, 1, Integer::sum);
        if (cs instanceof PDSeparation sep) {
            spotColors.add(sep.getColorantName());
        } else if (cs instanceof PDDeviceN devN) {
            spotColors.addAll(devN.getColorantNames());
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
