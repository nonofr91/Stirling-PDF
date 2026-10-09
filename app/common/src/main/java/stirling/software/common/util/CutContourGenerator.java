package stirling.software.common.util;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.common.function.PDFunction;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDSeparation;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;

import lombok.extern.slf4j.Slf4j;

import stirling.software.common.util.SilhouetteTracer.TraceResult;

/**
 * Writes a production cutting path onto a page: a thin overprinting stroke in a spot colour
 * (default {@code CutContour}) on a dedicated optional-content group carrying ISO 19593-1
 * processing-step metadata ({@code Structural/Cutting}), so RIPs and cutters recognise it while
 * ordinary print output stays clean.
 *
 * <p>Optionally clips the artwork to the contour and extends it past the cut line by propagating
 * the nearest silhouette-edge colour into the bleed ring (the raster equivalent of pdfToolbox's
 * "repeat pixels" bleed for irregular shapes).
 */
@Slf4j
public final class CutContourGenerator {

    public static final String DEFAULT_SPOT_NAME = "CutContour";
    public static final float DEFAULT_STROKE_PT = 0.25f;

    private static final String RESOURCE_PREFIX = "SPDF_CC_";
    private static final float MM_TO_PT = 72f / 25.4f;

    public static final class Settings {
        /**
         * Spot colourant name RIPs key on; case-sensitive, keep {@code CutContour} unless the shop
         * asks otherwise.
         */
        public String spotName = DEFAULT_SPOT_NAME;

        public float strokeWidthPt = DEFAULT_STROKE_PT;

        /** Replace page content with the original artwork clipped to the cut path. */
        public boolean clipArtwork = false;

        /** Width of raster bleed painted beyond the cut line; 0 disables. */
        public float bleedMm = 0f;

        /** Set TrimBox to the contour bounding box (BleedBox follows when bleed is generated). */
        public boolean trimToContour = false;

        /** Attach ISO 19593-1 {@code Structural/Cutting} metadata and Usage print suppression. */
        public boolean processingSteps = true;

        /** Optional-content (layer) name; defaults to the spot name when null/blank. */
        public String layerName = null;
    }

    private CutContourGenerator() {}

    public static void apply(PDDocument document, int pageIndex, TraceResult trace, Settings s)
            throws IOException {
        PDPage page = document.getPage(pageIndex);
        String layerName =
                (s.layerName == null || s.layerName.isBlank()) ? s.spotName : s.layerName;
        PDOptionalContentGroup layer = ensureCutLayer(document, layerName, s.processingSteps);

        if (s.clipArtwork) {
            clipArtworkToContour(document, page, trace);
        }
        if (s.bleedMm > 0) {
            paintContourBleed(document, page, trace, s.bleedMm);
        }
        strokeCutPath(document, page, trace, s, layer);
        updateBoxes(page, trace, s);
    }

    /**
     * Finds (or creates) the layer the cut path lives on. Viewers see it by default; RIPs either
     * consume it as a cutting instruction or drop it because Usage/PrintState=OFF.
     */
    private static PDOptionalContentGroup ensureCutLayer(
            PDDocument document, String layerName, boolean processingSteps) {
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        PDOptionalContentProperties props = catalog.getOCProperties();
        if (props == null) {
            props = new PDOptionalContentProperties();
            catalog.setOCProperties(props);
        }
        PDOptionalContentGroup existing =
                props.hasGroup(layerName) ? props.getGroup(layerName) : null;
        if (existing != null) {
            return existing;
        }
        PDOptionalContentGroup group = new PDOptionalContentGroup(layerName);
        COSDictionary dict = group.getCOSObject();
        if (processingSteps) {
            COSDictionary gts = new COSDictionary();
            gts.setName("GTS_ProcStepsGroup", "Structural");
            gts.setName("GTS_ProcStepsType", "Cutting");
            dict.setItem(COSName.getPDFName("GTS_Metadata"), gts);

            COSDictionary print = new COSDictionary();
            print.setName(COSName.PRINT_STATE, "OFF");
            COSDictionary view = new COSDictionary();
            view.setName(COSName.VIEW_STATE, "ON");
            COSDictionary usage = new COSDictionary();
            usage.setItem(COSName.PRINT, print);
            usage.setItem(COSName.VIEW, view);
            dict.setItem(COSName.USAGE, usage);
        }
        props.addGroup(group);

        // Keep the layer visible in viewers (D/ON) and listed in the layer tree (D/Order).
        COSDictionary config = (COSDictionary) props.getCOSObject().getDictionaryObject(COSName.D);
        if (config == null) {
            config = new COSDictionary();
            props.getCOSObject().setItem(COSName.D, config);
        }
        COSArray on = (COSArray) config.getDictionaryObject(COSName.ON);
        if (on == null) {
            on = new COSArray();
            config.setItem(COSName.ON, on);
        }
        on.add(group);
        COSArray order = (COSArray) config.getDictionaryObject(COSName.ORDER);
        if (order == null) {
            order = new COSArray();
            config.setItem(COSName.ORDER, order);
        }
        order.add(group);
        return group;
    }

    /**
     * Replaces the page content with the original artwork repainted inside the contour clip. Even
     * -odd fill keeps enclosed holes (letter counters) open; the art itself is imported with the
     * page rotation neutralised so it lands in the same unrotated space the paths were traced in.
     */
    private static void clipArtworkToContour(PDDocument document, PDPage page, TraceResult trace)
            throws IOException {
        PDFormXObject form = importUnrotated(document, page, trace.userSpace);
        PDResources resources = resources(page);
        COSName formName = uniqueName(resources.getXObjectNames(), "Fm");
        resources.put(formName, form);
        page.setContents(clipStream(document, trace, formName));
    }

    private static PDStream clipStream(PDDocument document, TraceResult trace, COSName formName)
            throws IOException {

        StringBuilder body = new StringBuilder(256);
        body.append("q\n");
        appendPathOps(body, trace.paths);
        body.append("W* n\n");
        body.append('/').append(formName.getName()).append(" Do\n");
        body.append("Q\n");
        PDStream content = new PDStream(document);
        try (OutputStream out = content.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(body.toString().getBytes(StandardCharsets.US_ASCII));
        }
        return content;
    }

    private static PDFormXObject importUnrotated(
            PDDocument document, PDPage page, PDRectangle cover) throws IOException {
        int rotation = page.getRotation();
        COSDictionary dict = page.getCOSObject();
        org.apache.pdfbox.cos.COSBase originalCrop = dict.getItem(COSName.CROP_BOX);
        try {
            page.setRotation(0);
            page.setCropBox(PageBoxUtils.union(page.getCropBox(), cover));
            return new LayerUtility(document).importPageAsForm(document, page);
        } finally {
            page.setRotation(rotation);
            if (originalCrop != null) {
                dict.setItem(COSName.CROP_BOX, originalCrop);
            } else {
                dict.removeItem(COSName.CROP_BOX);
            }
        }
    }

    /**
     * Paints a raster under the artwork covering the bleed ring. Ring pixels diffuse the subject's
     * edge colours outward by repeated neighbour averaging — Voronoi-style "nearest edge pixel"
     * copy leaves hard wedge seams on jagged masks, diffusion stays smooth. The image goes in as
     * JPEG + SMask: photographic content as lossless PNG balloons into tens of MB per page.
     */
    private static void paintContourBleed(
            PDDocument document, PDPage page, TraceResult trace, float bleedMm) throws IOException {
        int imgW = trace.render.getWidth();
        int imgH = trace.render.getHeight();
        int bleedPx = SilhouetteTracer.mmToPx(bleedMm, trace.dpiEff);
        boolean[] bleedMask = SilhouetteTracer.offsetMask(trace.cutMask, imgW, imgH, bleedPx);

        int x0 = imgW;
        int y0 = imgH;
        int x1 = 0;
        int y1 = 0;
        for (int i = 0; i < bleedMask.length; i++) {
            if (bleedMask[i]) {
                int x = i % imgW;
                int y = i / imgW;
                x0 = Math.min(x0, x);
                y0 = Math.min(y0, y);
                x1 = Math.max(x1, x);
                y1 = Math.max(y1, y);
            }
        }
        if (x1 < x0) {
            return;
        }
        int bw = x1 - x0 + 1;
        int bh = y1 - y0 + 1;
        int[] render = trace.render.getRGB(0, 0, imgW, imgH, null, 0, imgW);
        int[] rgb = diffuseEdgeColours(render, trace.subjectMask, bleedMask, imgW, imgH);

        BufferedImage bleed = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_RGB);
        BufferedImage alpha = new BufferedImage(bw, bh, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                int i = (y0 + y) * imgW + (x0 + x);
                if (bleedMask[i]) {
                    bleed.setRGB(x, y, rgb[i] & 0xFFFFFF);
                    alpha.setRGB(x, y, 0xFFFFFF);
                }
            }
        }
        PDImageXObject mask = LosslessFactory.createFromImage(document, alpha);
        PDImageXObject xObject = JPEGFactory.createFromImage(document, bleed, 0.92f);
        xObject.getCOSObject().setItem(COSName.SMASK, mask.getCOSObject());
        PDRectangle space = trace.userSpace;
        float ptPerPxX = space.getWidth() / imgW;
        float ptPerPxY = space.getHeight() / imgH;
        float xPt = space.getLowerLeftX() + x0 * ptPerPxX;
        float yTop = space.getUpperRightY() - y0 * ptPerPxY;
        float wPt = bw * ptPerPxX;
        float hPt = bh * ptPerPxY;
        try (PDPageContentStream cs =
                new PDPageContentStream(
                        document, page, PDPageContentStream.AppendMode.PREPEND, true)) {
            cs.drawImage(xObject, xPt, yTop - hPt, wPt, hPt);
        }
    }

    /**
     * Floods the bleed ring with colour diffused from the subject: every ring pixel averages its
     * already-filled 4-neighbours, so hues flow smoothly outward instead of stair-stepping in
     * Voronoi wedges. Subject pixels keep their rendered colour.
     */
    private static int[] diffuseEdgeColours(
            int[] render, boolean[] subjectMask, boolean[] bleedMask, int w, int h) {
        int[] out = new int[w * h];
        boolean[] filled = new boolean[w * h];
        java.util.ArrayDeque<Integer> frontier = new java.util.ArrayDeque<>(w * 4);
        for (int i = 0; i < subjectMask.length; i++) {
            if (subjectMask[i]) {
                out[i] = render[i];
                filled[i] = true;
                continue;
            }
            if (!bleedMask[i]) {
                continue;
            }
            int x = i % w;
            boolean touchesSubject =
                    (x > 0 && subjectMask[i - 1])
                            || (x < w - 1 && subjectMask[i + 1])
                            || (i >= w && subjectMask[i - w])
                            || (i < w * (h - 1) && subjectMask[i + w]);
            if (touchesSubject) {
                frontier.add(i);
            }
        }
        while (!frontier.isEmpty()) {
            int i = frontier.poll();
            if (filled[i] || !bleedMask[i]) {
                continue;
            }
            long r = 0, g = 0, b = 0, n = 0;
            int x = i % w;
            int[] nb = {i - 1, i + 1, i - w, i + w};
            boolean[] edge = {x == 0, x == w - 1, i < w, i >= w * (h - 1)};
            for (int k = 0; k < 4; k++) {
                int j = nb[k];
                if (!edge[k] && filled[j]) {
                    r += (out[j] >> 16) & 0xFF;
                    g += (out[j] >> 8) & 0xFF;
                    b += out[j] & 0xFF;
                    n++;
                }
            }
            if (n == 0) {
                continue;
            }
            out[i] = (int) (((r / n) << 16) | ((g / n) << 8) | (b / n));
            filled[i] = true;
            for (int k = 0; k < 4; k++) {
                int j = nb[k];
                if (!edge[k] && bleedMask[j] && !filled[j]) {
                    frontier.add(j);
                }
            }
        }
        return out;
    }

    /** Strokes every traced ring in the spot colour on the cut layer, overprinting on. */
    private static void strokeCutPath(
            PDDocument document,
            PDPage page,
            TraceResult trace,
            Settings s,
            PDOptionalContentGroup layer)
            throws IOException {
        PDResources resources = resources(page);
        COSName propName = uniqueName(resources.getPropertiesNames(), "Cut");
        resources.put(propName, layer);

        try (PDPageContentStream cs =
                new PDPageContentStream(
                        document, page, PDPageContentStream.AppendMode.APPEND, true)) {
            cs.saveGraphicsState();
            cs.beginMarkedContent(COSName.OC, layer);
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            // PDFBox exposes overprint mode/control but not the OP flag itself.
            gs.getCOSObject().setBoolean(COSName.OP, true);
            cs.setGraphicsStateParameters(gs);
            cs.setStrokingColor(cutSpotColor(s.spotName));
            cs.setLineWidth(s.strokeWidthPt);
            cs.setLineJoinStyle(1); // round joins keep cutter motion smooth
            for (List<Point2D.Float> ring : trace.paths) {
                cs.moveTo(ring.get(0).x, ring.get(0).y);
                for (int i = 1; i < ring.size(); i++) {
                    cs.lineTo(ring.get(i).x, ring.get(i).y);
                }
                cs.closePath();
            }
            cs.stroke();
            cs.endMarkedContent();
            cs.restoreGraphicsState();
        }
    }

    /**
     * 100%-tint spot colour over DeviceCMYK magenta (0,1,0,0) — the de-facto CutContour preview.
     */
    private static PDColor cutSpotColor(String spotName) throws IOException {
        COSDictionary fn = new COSDictionary();
        fn.setInt(COSName.FUNCTION_TYPE, 2);
        COSArray domain = new COSArray();
        domain.add(COSInteger.get(0));
        domain.add(COSInteger.get(1));
        fn.setItem(COSName.DOMAIN, domain);
        COSArray c0 = new COSArray();
        c0.add(new COSFloat(0));
        c0.add(new COSFloat(0));
        c0.add(new COSFloat(0));
        c0.add(new COSFloat(0));
        COSArray c1 = new COSArray();
        c1.add(new COSFloat(0));
        c1.add(new COSFloat(1));
        c1.add(new COSFloat(0));
        c1.add(new COSFloat(0));
        fn.setItem(COSName.C0, c0);
        fn.setItem(COSName.C1, c1);
        fn.setFloat(COSName.N, 1f);

        COSArray sep = new COSArray();
        sep.add(COSName.SEPARATION);
        sep.add(COSName.getPDFName(spotName));
        sep.add(COSName.DEVICECMYK);
        COSBase fnObj = PDFunction.create(fn).getCOSObject();
        sep.add(fnObj);
        return new PDColor(new float[] {1f}, new PDSeparation(sep));
    }

    private static void updateBoxes(PDPage page, TraceResult trace, Settings s) {
        PDRectangle bounds = trace.bounds();
        if (s.trimToContour) {
            page.setTrimBox(bounds);
        }
        if (s.bleedMm > 0) {
            float b = s.bleedMm * MM_TO_PT;
            PDRectangle bleedBox =
                    new PDRectangle(
                            bounds.getLowerLeftX() - b,
                            bounds.getLowerLeftY() - b,
                            bounds.getWidth() + 2 * b,
                            bounds.getHeight() + 2 * b);
            page.setBleedBox(bleedBox);
            page.setMediaBox(PageBoxUtils.union(page.getMediaBox(), bleedBox));
            page.setCropBox(PageBoxUtils.union(page.getCropBox(), bleedBox));
        } else if (s.trimToContour) {
            page.setMediaBox(PageBoxUtils.union(page.getMediaBox(), bounds));
            page.setCropBox(PageBoxUtils.union(page.getCropBox(), bounds));
        }
    }

    private static void appendPathOps(StringBuilder body, List<List<Point2D.Float>> paths) {
        for (List<Point2D.Float> ring : paths) {
            body.append(fmt(ring.get(0).x)).append(' ').append(fmt(ring.get(0).y)).append(" m\n");
            for (int i = 1; i < ring.size(); i++) {
                body.append(fmt(ring.get(i).x))
                        .append(' ')
                        .append(fmt(ring.get(i).y))
                        .append(" l\n");
            }
            body.append("h\n");
        }
    }

    private static String fmt(float v) {
        return String.format(Locale.ROOT, "%.3f", v).replaceAll("\\.?0+$", "");
    }

    private static PDResources resources(PDPage page) {
        PDResources resources = page.getResources();
        if (resources == null) {
            resources = new PDResources();
            page.setResources(resources);
        }
        return resources;
    }

    private static COSName uniqueName(Iterable<COSName> existing, String prefix) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (COSName n : existing) {
            names.add(n.getName());
        }
        int i = 0;
        String candidate;
        do {
            candidate = RESOURCE_PREFIX + prefix + i++;
        } while (names.contains(candidate));
        return COSName.getPDFName(candidate);
    }
}
