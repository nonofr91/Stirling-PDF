package stirling.software.common.util;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import stirling.software.common.util.SilhouetteTracer.ExtractionMode;
import stirling.software.common.util.SilhouetteTracer.TraceResult;

class SilhouetteTracerTest {

    private static final float MM = 72f / 25.4f;

    private static BufferedImage whiteWithCenterSquare() {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 100, 100);
        g.setColor(Color.RED);
        g.fillRect(30, 30, 40, 40);
        g.dispose();
        return img;
    }

    private static BufferedImage transparentWithSquare() {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(30, 30, 40, 40);
        g.dispose();
        return img;
    }

    private static int count(boolean[] m) {
        int c = 0;
        for (boolean b : m) {
            if (b) {
                c++;
            }
        }
        return c;
    }

    @Test
    void backgroundMask_floodFillSeparatesSubject() {
        boolean[] fg = SilhouetteTracer.backgroundMask(whiteWithCenterSquare(), 24, 16);
        // the 40x40 red square stays foreground; white background is flooded out
        int fgCount = count(fg);
        assertTrue(fgCount > 1500 && fgCount < 1700, "fg count " + fgCount);
        assertTrue(fg[30 * 100 + 30]);
        assertFalse(fg[0]);
    }

    @Test
    void backgroundMask_whitePageMarginFloodsIntoUniformArtworkBackdrop() {
        // Pages render with an opaque white margin: the border reference is white, so a
        // uniform backdrop inside the artwork (e.g. a photo floated on the page) must flood
        // away through it instead of stopping at the image frame.
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 100, 100);
        g.setColor(new Color(251, 247, 236));
        g.fillRect(10, 10, 80, 80);
        g.setColor(new Color(140, 30, 30));
        g.fillRect(40, 40, 20, 20);
        g.dispose();

        boolean[] fg = SilhouetteTracer.backgroundMask(img, 24, 16);
        int fgCount = count(fg);
        assertTrue(fgCount > 350 && fgCount < 450, "fg count " + fgCount);
        assertTrue(fg[50 * 100 + 50]);
        assertFalse(fg[11 * 100 + 11], "cream backdrop inside the artwork must flood away");
    }

    @Test
    void backgroundMask_transparentMarginSamplesOpaqueFrontier() {
        // No opaque border at all: the ring of opaque pixels facing the flooded transparent
        // margin is the reference, so an image backdrop still separates from the subject.
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(251, 247, 236));
        g.fillRect(10, 10, 80, 80);
        g.setColor(new Color(140, 30, 30));
        g.fillRect(40, 40, 20, 20);
        g.dispose();

        boolean[] fg = SilhouetteTracer.backgroundMask(img, 24, 16);
        int fgCount = count(fg);
        assertTrue(fgCount > 350 && fgCount < 450, "fg count " + fgCount);
        assertFalse(fg[11 * 100 + 11], "cream backdrop inside the artwork must flood away");
    }

    @Test
    void backgroundMask_roiRingSamplesLocalBackground() {
        // Page edges split between two colours — the border reference is unusable. A rough
        // polygon drawn just outside the artwork samples its inner ring (the beige band)
        // instead, so the interior backdrop floods away and the subject survives.
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 50, 100);
        g.setColor(new Color(120, 120, 130));
        g.fillRect(50, 0, 50, 100);
        g.setColor(new Color(251, 247, 236));
        g.fillRect(10, 30, 30, 30); // artwork backdrop on the white half
        g.setColor(new Color(140, 30, 30));
        g.fillRect(18, 38, 14, 14);
        g.dispose();

        // rough perimeter hugging the artwork's beige frame edge, inside the white half —
        // the inner ring then samples beige, and the ring's own tight spread keeps the
        // flood from leaking through same-ish whites the way the user tolerance would
        boolean[] roi =
                SilhouetteTracer.rasterizeRoi(
                        new float[] {0.10f, 0.30f, 0.40f, 0.30f, 0.40f, 0.60f, 0.10f, 0.60f},
                        100,
                        100);
        boolean[] fg = SilhouetteTracer.backgroundMask(img, 24, 16, roi);
        int fgCount = count(fg);
        assertTrue(fgCount > 150 && fgCount < 250, "fg count " + fgCount);
        assertTrue(fg[45 * 100 + 25], "subject centre survives");
        assertFalse(fg[35 * 100 + 15], "beige backdrop inside ROI floods away");
        assertFalse(fg[50 * 100 + 70], "outside the ROI is always background");
    }

    @Test
    void trace_mergeGapJoinsElementsIntoOneContour() throws IOException {
        // Two shapes 10pt apart on white: mergeGapMm 0 keeps them separate, a generous gap
        // bridges them under a single outer contour.
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(0.1f, 0.1f, 0.4f);
            cs.addRect(100, 100, 50, 50);
            cs.addRect(160, 100, 50, 50); // 10pt gap between the squares
            cs.fill();
        }
        byte[] bytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            doc.save(baos);
            bytes = baos.toByteArray();
        }
        doc.close();

        try (PDDocument loaded = Loader.loadPDF(bytes)) {
            SilhouetteTracer.Settings s = new SilhouetteTracer.Settings();
            s.mode = SilhouetteTracer.ExtractionMode.BACKGROUND;
            s.minAreaMm2 = 0;

            s.mergeGapMm = 0;
            assertEquals(2, SilhouetteTracer.trace(loaded, 0, s, null).paths.size());

            s.mergeGapMm = 8; // ~22pt — wider than the 10pt gap
            SilhouetteTracer.TraceResult merged = SilhouetteTracer.trace(loaded, 0, s, null);
            assertEquals(1, merged.paths.size(), "merged mask must yield one outer contour");
        }
    }

    @Test
    void backgroundMask_uniformImageYieldsEmptyForeground() {
        BufferedImage img = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 50, 50);
        g.dispose();
        assertEquals(0, count(SilhouetteTracer.backgroundMask(img, 24, 16)));
    }

    @Test
    void removeSmallComponents_dropsNoise() {
        int w = 50;
        int h = 50;
        boolean[] m = new boolean[w * h];
        for (int y = 20; y < 40; y++) {
            for (int x = 20; x < 40; x++) {
                m[y * w + x] = true;
            }
        }
        m[0] = true; // single stray pixel
        SilhouetteTracer.removeSmallComponents(m, w, h, 10);
        assertFalse(m[0]);
        assertEquals(400, count(m));
    }

    @Test
    void fillInteriorHoles_fillsEnclosedGaps() {
        int w = 20;
        int h = 20;
        boolean[] m = new boolean[w * h];
        for (int y = 2; y < 18; y++) {
            for (int x = 2; x < 18; x++) {
                boolean border = x < 4 || x > 15 || y < 4 || y > 15;
                m[y * w + x] = border;
            }
        }
        assertFalse(m[10 * w + 10]);
        SilhouetteTracer.fillInteriorHoles(m, w, h);
        assertTrue(m[10 * w + 10]);
        assertFalse(m[0]); // exterior untouched
    }

    @Test
    void offsetMask_dilatesAndErodes() {
        int w = 100;
        int h = 100;
        boolean[] m = new boolean[w * h];
        for (int y = 30; y < 50; y++) {
            for (int x = 30; x < 50; x++) {
                m[y * w + x] = true;
            }
        }
        boolean[] dilated = SilhouetteTracer.offsetMask(m, w, h, 5);
        assertTrue(dilated[25 * w + 40]); // 5px left of the square
        assertFalse(dilated[24 * w + 40]);
        boolean[] eroded = SilhouetteTracer.offsetMask(m, w, h, -5);
        assertFalse(eroded[30 * w + 40]); // edge eroded
        assertTrue(eroded[40 * w + 40]); // center survives
    }

    @Test
    void chamferDistance_propagatesNearestSource() {
        int w = 20;
        int h = 20;
        boolean[] m = new boolean[w * h];
        m[10 * w + 10] = true;
        int[] src = new int[w * h];
        int[] d = SilhouetteTracer.chamferDistance(m, w, h, w * h * 10, src);
        assertEquals(0, d[10 * w + 10]);
        assertEquals(10, d[10 * w + 11]); // 1px ortho = 10 tenths
        assertEquals(10 * w + 10, src[0]);
        assertEquals(10 * w + 10, src[19 * w + 19]); // corner resolves to the only fg pixel
    }

    @Test
    void traceContours_squareAndHole() {
        int w = 40;
        int h = 40;
        boolean[] m = new boolean[w * h];
        for (int y = 10; y < 30; y++) {
            for (int x = 10; x < 30; x++) {
                m[y * w + x] = true;
            }
        }
        // carve a hole
        for (int y = 17; y < 23; y++) {
            for (int x = 17; x < 23; x++) {
                m[y * w + x] = false;
            }
        }
        var contours = SilhouetteTracer.traceContours(m, w, h);
        assertEquals(2, contours.size());
        // one outer ring and one interior hole
        assertTrue(contours.stream().anyMatch(c -> !c.hole()));
        assertTrue(contours.stream().anyMatch(SilhouetteTracer.Contour::hole));
    }

    @Test
    void trace_alphaMode_extractsImageSilhouette() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDImageXObject xo = LosslessFactory.createFromImage(doc, transparentWithSquare());
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(xo, 100, 300, 200, 200);
        }
        byte[] bytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            doc.save(baos);
            bytes = baos.toByteArray();
        }
        doc.close();
        try (PDDocument loaded = Loader.loadPDF(bytes)) {
            SilhouetteTracer.Settings s = new SilhouetteTracer.Settings();
            s.mode = ExtractionMode.ALPHA;
            s.minAreaMm2 = 0;
            s.smoothness = 0;
            TraceResult t = SilhouetteTracer.trace(loaded, 0, s, null);
            assertEquals(ExtractionMode.ALPHA, t.modeUsed);
            assertEquals(1, t.paths.size());
            assertFalse(t.holeFlags.get(0));
            PDRectangle bounds = t.bounds();
            // image occupies user space x∈[100,300] y∈[300,500]; the square covers
            // 60% of it → roughly x∈[160,280], y∈[340,460]
            assertTrue(
                    bounds.getLowerLeftX() > 140 && bounds.getLowerLeftX() < 180,
                    "llx " + bounds.getLowerLeftX());
            assertTrue(bounds.getWidth() > 70 && bounds.getWidth() < 90, "w " + bounds.getWidth());
        }
    }

    @Test
    void trace_blankPageThrows() throws IOException {
        PDDocument doc = new PDDocument();
        doc.addPage(new PDPage(PDRectangle.A4));
        byte[] bytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            doc.save(baos);
            bytes = baos.toByteArray();
        }
        doc.close();
        try (PDDocument loaded = Loader.loadPDF(bytes)) {
            SilhouetteTracer.Settings s = new SilhouetteTracer.Settings();
            s.mode = ExtractionMode.ALPHA;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SilhouetteTracer.trace(loaded, 0, s, null));
        }
    }

    @Test
    void trace_autoFallsBackToBackground() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(1f, 1f, 1f);
            cs.addRect(0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            cs.fill();
            cs.setNonStrokingColor(0f, 0.5f, 0f);
            cs.addRect(200, 300, 100, 150);
            cs.fill();
        }
        byte[] bytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            doc.save(baos);
            bytes = baos.toByteArray();
        }
        doc.close();
        try (PDDocument loaded = Loader.loadPDF(bytes)) {
            SilhouetteTracer.Settings s = new SilhouetteTracer.Settings();
            s.mode = ExtractionMode.AUTO;
            s.minAreaMm2 = 0;
            TraceResult t = SilhouetteTracer.trace(loaded, 0, s, null);
            assertEquals(ExtractionMode.BACKGROUND, t.modeUsed);
            PDRectangle bounds = t.bounds();
            assertTrue(Math.abs(bounds.getLowerLeftX() - 200) < 6);
            assertTrue(Math.abs(bounds.getWidth() - 100) < 8);
        }
    }

    @Test
    void trace_autoSkipsDegenerateRectangleMask() throws IOException {
        // An opaque raster frame on a transparent page gives ALPHA the frame rectangle, and
        // a two-tone backdrop defeats BACKGROUND's border/frontier references — AUTO must
        // keep walking to AI for the silhouette inside.
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        BufferedImage photo = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = photo.createGraphics();
        g.setColor(new Color(0xF5F0E0));
        g.fillRect(0, 0, 50, 100);
        g.setColor(new Color(0x10102A));
        g.fillRect(50, 0, 50, 100);
        g.setColor(Color.BLUE);
        g.fillOval(25, 25, 50, 50);
        g.dispose();
        PDImageXObject xo = LosslessFactory.createFromImage(doc, photo);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(xo, 150, 300, 300, 300);
        }
        byte[] bytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            doc.save(baos);
            bytes = baos.toByteArray();
        }
        doc.close();
        try (PDDocument loaded = Loader.loadPDF(bytes)) {
            SilhouetteTracer.Settings s = new SilhouetteTracer.Settings();
            s.mode = ExtractionMode.AUTO;
            s.minAreaMm2 = 0;
            java.util.function.Function<BufferedImage, float[]> engine =
                    img -> {
                        float[] m = new float[img.getWidth() * img.getHeight()];
                        int cx = img.getWidth() / 2, cy = img.getHeight() / 2;
                        int r = img.getWidth() / 5;
                        for (int y = 0; y < img.getHeight(); y++) {
                            for (int x = 0; x < img.getWidth(); x++) {
                                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) < r * r) {
                                    m[y * img.getWidth() + x] = 1f;
                                }
                            }
                        }
                        return m;
                    };
            TraceResult t = SilhouetteTracer.trace(loaded, 0, s, engine);
            assertEquals(ExtractionMode.AI, t.modeUsed);
        }
    }

    @Test
    void trace_rotatedPageMapsToUnrotatedSpace() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        page.setRotation(90);
        doc.addPage(page);
        PDImageXObject xo = LosslessFactory.createFromImage(doc, transparentWithSquare());
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(xo, 100, 300, 200, 200);
        }
        byte[] bytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            doc.save(baos);
            bytes = baos.toByteArray();
        }
        doc.close();
        try (PDDocument loaded = Loader.loadPDF(bytes)) {
            SilhouetteTracer.Settings s = new SilhouetteTracer.Settings();
            s.mode = ExtractionMode.ALPHA;
            s.minAreaMm2 = 0;
            TraceResult t = SilhouetteTracer.trace(loaded, 0, s, null);
            PDRectangle bounds = t.bounds();
            // coordinates must stay in the unrotated user space regardless of /Rotate
            assertTrue(
                    bounds.getLowerLeftX() > 140 && bounds.getLowerLeftX() < 180,
                    "llx " + bounds.getLowerLeftX());
            for (List<Point2D.Float> ring : t.paths) {
                for (Point2D.Float p : ring) {
                    assertTrue(p.x >= -1 && p.x <= PDRectangle.A4.getWidth() + 1);
                    assertTrue(p.y >= -1 && p.y <= PDRectangle.A4.getHeight() + 1);
                }
            }
        }
    }
}
