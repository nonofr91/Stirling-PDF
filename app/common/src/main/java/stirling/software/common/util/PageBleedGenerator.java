package stirling.software.common.util;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType4;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.color.PDSeparation;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.util.Matrix;

import lombok.extern.slf4j.Slf4j;

/**
 * Paints real bleed content between a page's TrimBox and BleedBox — mirrored or repeated edge
 * content — so trimming the sheet leaves no white edge, the way PitStop's "Add Bleed" and
 * pdfToolbox's "Generate bleed at page edges" do. Also draws crop marks in the slug area beyond the
 * bleed.
 */
@Slf4j
public final class PageBleedGenerator {

    private PageBleedGenerator() {}

    public enum BleedMethod {
        MIRROR,
        MIRROR_IMAGE,
        PIXEL_REPEAT,
        UPSCALE;

        public static BleedMethod parse(String value) {
            if (value == null || value.isBlank()) {
                return MIRROR;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "bleedMethod must be one of MIRROR, MIRROR_IMAGE, PIXEL_REPEAT, UPSCALE,"
                                + " got: "
                                + value);
            }
        }
    }

    /** Bleed width in points on each side of the TrimBox. */
    public record BleedEdges(float left, float right, float bottom, float top) {

        public boolean any() {
            return left > 0 || right > 0 || bottom > 0 || top > 0;
        }

        /** Smallest rectangle the MediaBox must cover: trim + bleed + crop marks extent. */
        public PDRectangle unionWith(
                PDRectangle trim, boolean cropMarks, float markOffsetPt, float markLengthPt) {
            float marks = cropMarks ? markOffsetPt + markLengthPt : 0;
            float l = Math.max(left, marks);
            float r = Math.max(right, marks);
            float b = Math.max(bottom, marks);
            float t = Math.max(top, marks);
            return new PDRectangle(
                    trim.getLowerLeftX() - l,
                    trim.getLowerLeftY() - b,
                    trim.getWidth() + l + r,
                    trim.getHeight() + b + t);
        }
    }

    /**
     * Adds bleed content around {@code trim} on one page. Bleed strips are prepended so existing
     * content — e.g. bleed already present between TrimBox and CropBox — stays on top.
     */
    public static void generateBleed(
            PDDocument document,
            PDPage page,
            int pageIndex,
            PDRectangle trim,
            BleedEdges edges,
            BleedMethod method,
            boolean corners,
            int dpi,
            float insetPt)
            throws IOException {
        if (!edges.any()) {
            return;
        }
        if (!page.hasContents()
                && (method == BleedMethod.MIRROR || method == BleedMethod.UPSCALE)) {
            // A contentless page has nothing to mirror; a blank band would be identical.
            return;
        }
        switch (method) {
            case UPSCALE -> upscale(document, page, trim, edges);
            case MIRROR -> mirror(document, page, trim, edges, corners, insetPt);
            case MIRROR_IMAGE -> raster(document, page, pageIndex, trim, edges, corners, dpi, true);
            case PIXEL_REPEAT ->
                    raster(document, page, pageIndex, trim, edges, corners, dpi, false);
        }
    }

    /**
     * Vector mirror: reflects the page form across each trim edge (and around each corner) into the
     * bleed strip, in the page's unrotated user space.
     */
    private static void mirror(
            PDDocument document,
            PDPage page,
            PDRectangle trim,
            BleedEdges edges,
            boolean corners,
            float insetPt)
            throws IOException {
        PDFormXObject form = importUnrotated(document, page);

        try (PDPageContentStream stream =
                new PDPageContentStream(document, page, AppendMode.PREPEND, true, false)) {
            float left = trim.getLowerLeftX();
            float bottom = trim.getLowerLeftY();
            float right = trim.getUpperRightX();
            float top = trim.getUpperRightY();
            float width = trim.getWidth();
            float height = trim.getHeight();
            // Skipping insetPt of content inside the edge means mirroring about an axis moved
            // inward by half of it (the reflection covers the gap on both sides).
            float axis = insetPt / 2f;

            if (edges.left() > 0) {
                mirrorStrip(
                        stream,
                        form,
                        left - edges.left(),
                        bottom,
                        edges.left(),
                        height,
                        new Matrix(-1, 0, 0, 1, 2 * (left + axis), 0));
            }
            if (edges.right() > 0) {
                mirrorStrip(
                        stream,
                        form,
                        right,
                        bottom,
                        edges.right(),
                        height,
                        new Matrix(-1, 0, 0, 1, 2 * (right - axis), 0));
            }
            if (edges.bottom() > 0) {
                mirrorStrip(
                        stream,
                        form,
                        left,
                        bottom - edges.bottom(),
                        width,
                        edges.bottom(),
                        new Matrix(1, 0, 0, -1, 0, 2 * (bottom + axis)));
            }
            if (edges.top() > 0) {
                mirrorStrip(
                        stream,
                        form,
                        left,
                        top,
                        width,
                        edges.top(),
                        new Matrix(1, 0, 0, -1, 0, 2 * (top - axis)));
            }
            if (corners) {
                mirrorCorner(
                        stream,
                        form,
                        left - edges.left(),
                        bottom - edges.bottom(),
                        edges.left(),
                        edges.bottom(),
                        left + axis,
                        bottom + axis,
                        edges.left() > 0 && edges.bottom() > 0);
                mirrorCorner(
                        stream,
                        form,
                        right,
                        bottom - edges.bottom(),
                        edges.right(),
                        edges.bottom(),
                        right - axis,
                        bottom + axis,
                        edges.right() > 0 && edges.bottom() > 0);
                mirrorCorner(
                        stream,
                        form,
                        left - edges.left(),
                        top,
                        edges.left(),
                        edges.top(),
                        left + axis,
                        top - axis,
                        edges.left() > 0 && edges.top() > 0);
                mirrorCorner(
                        stream,
                        form,
                        right,
                        top,
                        edges.right(),
                        edges.top(),
                        right - axis,
                        top - axis,
                        edges.right() > 0 && edges.top() > 0);
            }
        }
    }

    private static void mirrorStrip(
            PDPageContentStream stream,
            PDFormXObject form,
            float x,
            float y,
            float width,
            float height,
            Matrix reflection)
            throws IOException {
        stream.saveGraphicsState();
        stream.addRect(x, y, width, height);
        stream.clip();
        stream.transform(reflection);
        stream.drawForm(form);
        stream.restoreGraphicsState();
    }

    private static void mirrorCorner(
            PDPageContentStream stream,
            PDFormXObject form,
            float x,
            float y,
            float width,
            float height,
            float axisX,
            float axisY,
            boolean active)
            throws IOException {
        if (!active) {
            return;
        }
        mirrorStrip(
                stream, form, x, y, width, height, new Matrix(-1, 0, 0, -1, 2 * axisX, 2 * axisY));
    }

    /**
     * Scales the page content about the trim center until it covers every requested bleed edge —
     * pdfToolbox's "generate bleed by upscaling". The trimmed piece ends up a slightly zoomed crop
     * of the original artwork.
     */
    private static void upscale(
            PDDocument document, PDPage page, PDRectangle trim, BleedEdges edges)
            throws IOException {
        float scale =
                Math.max(
                        (trim.getWidth() + edges.left() + edges.right()) / trim.getWidth(),
                        (trim.getHeight() + edges.bottom() + edges.top()) / trim.getHeight());
        if (scale <= 1f) {
            return;
        }
        PDFormXObject form = importUnrotated(document, page);

        float centerX = trim.getLowerLeftX() + trim.getWidth() / 2;
        float centerY = trim.getLowerLeftY() + trim.getHeight() / 2;
        try (PDPageContentStream stream =
                new PDPageContentStream(document, page, AppendMode.PREPEND, true, false)) {
            stream.saveGraphicsState();
            stream.transform(Matrix.getTranslateInstance(centerX, centerY));
            stream.transform(Matrix.getScaleInstance(scale, scale));
            stream.transform(Matrix.getTranslateInstance(-centerX, -centerY));
            stream.drawForm(form);
            stream.restoreGraphicsState();
        }
    }

    /**
     * Raster bleed: renders the page once, then stretches (PIXEL_REPEAT) or mirrors (MIRROR_IMAGE)
     * the outermost pixels into each bleed strip. The page rotation is zeroed for the render so
     * image space matches the page's unrotated user space.
     */
    private static void raster(
            PDDocument document,
            PDPage page,
            int pageIndex,
            PDRectangle trim,
            BleedEdges edges,
            boolean corners,
            int dpi,
            boolean mirrorImage)
            throws IOException {
        int rotation = page.getRotation();
        BufferedImage image;
        try {
            page.setRotation(0);
            image = new PDFRenderer(document).renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
        } finally {
            page.setRotation(rotation);
        }

        PDRectangle crop = page.getCropBox();
        float pxPerPt = dpi / 72f;
        int trimX0 =
                clamp((trim.getLowerLeftX() - crop.getLowerLeftX()) * pxPerPt, image.getWidth());
        int trimX1 =
                clamp((trim.getUpperRightX() - crop.getLowerLeftX()) * pxPerPt, image.getWidth());
        // Image rows are top-down: row 0 is the crop box's upper edge.
        int trimYTop =
                clamp(
                        image.getHeight()
                                - (trim.getUpperRightY() - crop.getLowerLeftY()) * pxPerPt,
                        image.getHeight());
        int trimYBottom =
                clamp(
                        image.getHeight() - (trim.getLowerLeftY() - crop.getLowerLeftY()) * pxPerPt,
                        image.getHeight());
        int trimWpx = trimX1 - trimX0;
        int trimHpx = trimYBottom - trimYTop;
        if (trimWpx < 2 || trimHpx < 2) {
            log.warn("TrimBox collapses to nothing in rendered page {}; bleed skipped", pageIndex);
            return;
        }

        float left = trim.getLowerLeftX();
        float bottom = trim.getLowerLeftY();
        float right = trim.getUpperRightX();
        float top = trim.getUpperRightY();

        try (PDPageContentStream stream =
                new PDPageContentStream(document, page, AppendMode.PREPEND, true, false)) {
            if (edges.left() > 0) {
                int band = mirrorImage ? px(edges.left(), pxPerPt) : 1;
                drawImageStrip(
                        stream,
                        document,
                        image,
                        trimX0,
                        trimYTop,
                        band,
                        trimHpx,
                        true,
                        false,
                        pxPerPt,
                        left - edges.left(),
                        bottom,
                        edges.left(),
                        trim.getHeight());
            }
            if (edges.right() > 0) {
                int band = mirrorImage ? px(edges.right(), pxPerPt) : 1;
                drawImageStrip(
                        stream,
                        document,
                        image,
                        trimX1 - band,
                        trimYTop,
                        band,
                        trimHpx,
                        true,
                        false,
                        pxPerPt,
                        right,
                        bottom,
                        edges.right(),
                        trim.getHeight());
            }
            if (edges.bottom() > 0) {
                int band = mirrorImage ? px(edges.bottom(), pxPerPt) : 1;
                drawImageStrip(
                        stream,
                        document,
                        image,
                        trimX0,
                        trimYBottom - band,
                        trimWpx,
                        band,
                        false,
                        true,
                        pxPerPt,
                        left,
                        bottom - edges.bottom(),
                        trim.getWidth(),
                        edges.bottom());
            }
            if (edges.top() > 0) {
                int band = mirrorImage ? px(edges.top(), pxPerPt) : 1;
                drawImageStrip(
                        stream,
                        document,
                        image,
                        trimX0,
                        trimYTop,
                        trimWpx,
                        band,
                        false,
                        true,
                        pxPerPt,
                        left,
                        top,
                        trim.getWidth(),
                        edges.top());
            }
            if (corners) {
                int lw = px(edges.left(), pxPerPt);
                int rw = px(edges.right(), pxPerPt);
                int bh = px(edges.bottom(), pxPerPt);
                int th = px(edges.top(), pxPerPt);
                // Mirror samples the trim-adjacent block; repeat samples the corner pixel.
                int rightSrcX = mirrorImage ? trimX1 - rw : trimX1 - 1;
                int bottomSrcY = mirrorImage ? trimYBottom - bh : trimYBottom - 1;
                drawImageCorner(
                        stream,
                        document,
                        image,
                        trimX0,
                        trimYTop,
                        lw,
                        th,
                        mirrorImage,
                        pxPerPt,
                        left - edges.left(),
                        top,
                        edges.left(),
                        edges.top());
                drawImageCorner(
                        stream,
                        document,
                        image,
                        rightSrcX,
                        trimYTop,
                        rw,
                        th,
                        mirrorImage,
                        pxPerPt,
                        right,
                        top,
                        edges.right(),
                        edges.top());
                drawImageCorner(
                        stream,
                        document,
                        image,
                        trimX0,
                        bottomSrcY,
                        lw,
                        bh,
                        mirrorImage,
                        pxPerPt,
                        left - edges.left(),
                        bottom - edges.bottom(),
                        edges.left(),
                        edges.bottom());
                drawImageCorner(
                        stream,
                        document,
                        image,
                        rightSrcX,
                        bottomSrcY,
                        rw,
                        bh,
                        mirrorImage,
                        pxPerPt,
                        right,
                        bottom - edges.bottom(),
                        edges.right(),
                        edges.bottom());
            }
        }
    }

    /**
     * Imports the page as a form XObject with /Rotate neutralized: bleed geometry is computed in
     * the page's unrotated user space, so the imported content must not carry the display rotation
     * that importPageAsForm would otherwise bake into the form matrix.
     */
    private static PDFormXObject importUnrotated(PDDocument document, PDPage page)
            throws IOException {
        int rotation = page.getRotation();
        try {
            page.setRotation(0);
            return new LayerUtility(document).importPageAsForm(document, page);
        } finally {
            page.setRotation(rotation);
        }
    }

    private static int px(float points, float pxPerPt) {
        return Math.max(1, Math.round(points * pxPerPt));
    }

    private static int clamp(float value, int max) {
        return Math.min(Math.max(Math.round(value), 0), Math.max(max, 1));
    }

    /**
     * Samples a band of the rendered page and draws it into a bleed strip. With a 1-px source band
     * the stretch gives PIXEL_REPEAT; sampling the full bleed-width band and flipping gives
     * MIRROR_IMAGE.
     */
    private static void drawImageStrip(
            PDPageContentStream stream,
            PDDocument document,
            BufferedImage image,
            int srcX,
            int srcY,
            int srcW,
            int srcH,
            boolean flipHorizontal,
            boolean flipVertical,
            float pxPerPt,
            float x,
            float y,
            float widthPt,
            float heightPt)
            throws IOException {
        int outW = Math.max(1, Math.round(widthPt * pxPerPt));
        int outH = Math.max(1, Math.round(heightPt * pxPerPt));
        BufferedImage strip = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = strip.createGraphics();
        int sx0 = Math.min(Math.max(srcX, 0), image.getWidth() - 1);
        int sy0 = Math.min(Math.max(srcY, 0), image.getHeight() - 1);
        int sx1 = Math.min(sx0 + srcW, image.getWidth());
        int sy1 = Math.min(sy0 + srcH, image.getHeight());
        g.drawImage(
                image,
                0,
                0,
                outW,
                outH,
                flipHorizontal ? sx1 : sx0,
                flipVertical ? sy1 : sy0,
                flipHorizontal ? sx0 : sx1,
                flipVertical ? sy0 : sy1,
                null);
        g.dispose();
        PDImageXObject xObject = LosslessFactory.createFromImage(document, strip);
        stream.drawImage(xObject, x, y, widthPt, heightPt);
    }

    /**
     * Bleed for one corner: PIXEL_REPEAT stretches the corner pixel, MIRROR_IMAGE mirrors the
     * trim-adjacent block on both axes. {@code srcX/srcY} is the pixel (repeat) or the block's
     * lower-resolution origin (mirror) inside the trim.
     */
    private static void drawImageCorner(
            PDPageContentStream stream,
            PDDocument document,
            BufferedImage image,
            int srcX,
            int srcY,
            int srcW,
            int srcH,
            boolean mirrorImage,
            float pxPerPt,
            float x,
            float y,
            float widthPt,
            float heightPt)
            throws IOException {
        if (widthPt <= 0 || heightPt <= 0) {
            return;
        }
        int wPx = px(widthPt, pxPerPt);
        int hPx = px(heightPt, pxPerPt);
        BufferedImage corner = new BufferedImage(wPx, hPx, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = corner.createGraphics();
        if (mirrorImage) {
            int sx0 = Math.min(Math.max(srcX, 0), Math.max(image.getWidth() - srcW, 0));
            int sy0 = Math.min(Math.max(srcY, 0), Math.max(image.getHeight() - srcH, 0));
            int sx1 = Math.min(sx0 + srcW, image.getWidth());
            int sy1 = Math.min(sy0 + srcH, image.getHeight());
            g.drawImage(image, 0, 0, wPx, hPx, sx1, sy1, sx0, sy0, null);
        } else {
            int sx = Math.min(Math.max(srcX, 0), image.getWidth() - 1);
            int sy = Math.min(Math.max(srcY, 0), image.getHeight() - 1);
            g.drawImage(image, 0, 0, wPx, hPx, sx, sy, sx + 1, sy + 1, null);
        }
        g.dispose();
        PDImageXObject xObject = LosslessFactory.createFromImage(document, corner);
        stream.drawImage(xObject, x, y, widthPt, heightPt);
    }

    /**
     * Draws crop marks at the four TrimBox corners, starting {@code offsetPt} outside the trim edge
     * and extending {@code lengthPt} into the slug. Stroked in the registration separation ("All")
     * so the marks print on every plate.
     */
    public static void drawCropMarks(
            PDDocument document,
            PDPage page,
            PDRectangle trim,
            float offsetPt,
            float lengthPt,
            float weightPt)
            throws IOException {
        if (offsetPt < 0 || lengthPt <= 0 || weightPt <= 0) {
            throw new IllegalArgumentException(
                    "Crop marks need a positive length and weight, and a non-negative offset");
        }
        float left = trim.getLowerLeftX();
        float bottom = trim.getLowerLeftY();
        float right = trim.getUpperRightX();
        float top = trim.getUpperRightY();
        float markEnd = offsetPt + lengthPt;

        try (PDPageContentStream stream =
                new PDPageContentStream(document, page, AppendMode.APPEND, true, true)) {
            stream.setLineWidth(weightPt);
            stream.setLineCapStyle(0);
            stream.setStrokingColor(registrationBlack(document));

            stream.moveTo(left - offsetPt, bottom);
            stream.lineTo(left - markEnd, bottom);
            stream.moveTo(left, bottom - offsetPt);
            stream.lineTo(left, bottom - markEnd);

            stream.moveTo(right + offsetPt, bottom);
            stream.lineTo(right + markEnd, bottom);
            stream.moveTo(right, bottom - offsetPt);
            stream.lineTo(right, bottom - markEnd);

            stream.moveTo(left - offsetPt, top);
            stream.lineTo(left - markEnd, top);
            stream.moveTo(left, top + offsetPt);
            stream.lineTo(left, top + markEnd);

            stream.moveTo(right + offsetPt, top);
            stream.lineTo(right + markEnd, top);
            stream.moveTo(right, top + offsetPt);
            stream.lineTo(right, top + markEnd);
            stream.stroke();
        }
    }

    /** Registration color: the "All" separation at 100% tint prints on every plate. */
    private static PDColor registrationBlack(PDDocument document) throws IOException {
        PDSeparation separation = new PDSeparation();
        separation.setColorantName("All");
        separation.setAlternateColorSpace(PDDeviceCMYK.INSTANCE);
        separation.setTintTransform(tintToCmyk(document));
        return new PDColor(new float[] {1f}, separation);
    }

    /** Type 4 function mapping tint t to DeviceCMYK (t, t, t, t). */
    private static PDFunctionType4 tintToCmyk(PDDocument document) throws IOException {
        PDStream stream = new PDStream(document);
        try (OutputStream out = stream.createOutputStream()) {
            out.write("{ dup dup dup }".getBytes(StandardCharsets.US_ASCII));
        }
        COSStream cos = stream.getCOSObject();
        cos.setInt(COSName.FUNCTION_TYPE, 4);
        cos.setItem(COSName.DOMAIN, floatArray(0, 1));
        cos.setItem(COSName.RANGE, floatArray(0, 1, 0, 1, 0, 1, 0, 1));
        return new PDFunctionType4(cos);
    }

    private static COSArray floatArray(float... values) {
        COSArray array = new COSArray();
        for (float v : values) {
            array.add(new COSFloat(v));
        }
        return array;
    }
}
