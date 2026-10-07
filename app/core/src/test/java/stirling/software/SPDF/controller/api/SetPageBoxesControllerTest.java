package stirling.software.SPDF.controller.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDAppearanceContentStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import stirling.software.SPDF.model.api.general.SetPageBoxesRequest;
import stirling.software.common.model.api.PDFFile;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;

@ExtendWith(MockitoExtension.class)
class SetPageBoxesControllerTest {

    private static final float MM = 72f / 25.4f;

    private static byte[] drainBody(ResponseEntity<Resource> response) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream in = response.getBody().getInputStream()) {
            in.transferTo(baos);
        }
        return baos.toByteArray();
    }

    private static void assertRectEquals(
            float x, float y, float width, float height, PDRectangle rect) {
        assertNotNull(rect);
        assertEquals(x, rect.getLowerLeftX(), 0.01);
        assertEquals(y, rect.getLowerLeftY(), 0.01);
        assertEquals(width, rect.getWidth(), 0.01);
        assertEquals(height, rect.getHeight(), 0.01);
    }

    @TempDir Path tempDir;
    @Mock private CustomPDFDocumentFactory pdfDocumentFactory;
    @Mock private TempFileManager tempFileManager;
    @Mock private stirling.software.SPDF.service.prepress.PrepressArchiveService prepressArchive;
    @InjectMocks private SetPageBoxesController controller;

    @BeforeEach
    void setUp() throws IOException {
        lenient()
                .when(pdfDocumentFactory.load(any(PDFFile.class)))
                .thenAnswer(
                        inv ->
                                Loader.loadPDF(
                                        ((PDFFile) inv.getArgument(0)).getFileInput().getBytes()));
        lenient()
                .when(tempFileManager.createManagedTempFile(anyString()))
                .thenAnswer(
                        inv -> {
                            java.io.File f =
                                    Files.createTempFile("test", inv.<String>getArgument(0))
                                            .toFile();
                            TempFile tf = mock(TempFile.class);
                            lenient().when(tf.getFile()).thenReturn(f);
                            lenient().when(tf.getPath()).thenReturn(f.toPath());
                            lenient().when(tf.getAbsolutePath()).thenReturn(f.getAbsolutePath());
                            return tf;
                        });
    }

    private MockMultipartFile createPdf(PDRectangle trimBox) throws IOException {
        Path pdfPath = tempDir.resolve("input.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            if (trimBox != null) {
                page.setTrimBox(trimBox);
            }
            doc.addPage(page);
            doc.save(pdfPath.toFile());
        }
        return new MockMultipartFile(
                "fileInput",
                "input.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                Files.readAllBytes(pdfPath));
    }

    /**
     * An A4 page carrying standard crop marks around {@code trim}: a horizontal and a vertical
     * hairline at each corner, drawn the way Phase 1's generator (and InDesign) paints them.
     */
    private MockMultipartFile createPdfWithCropMarks(PDRectangle trim) throws IOException {
        Path pdfPath = tempDir.resolve("marked.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream stream =
                    new PDPageContentStream(
                            doc, page, PDPageContentStream.AppendMode.APPEND, true)) {
                stream.setLineWidth(0.25f);
                float off = 3 * MM;
                float len = 5 * MM;
                float l = trim.getLowerLeftX();
                float r = trim.getUpperRightX();
                float b = trim.getLowerLeftY();
                float t = trim.getUpperRightY();
                stream.moveTo(l - off, b);
                stream.lineTo(l - off - len, b);
                stream.moveTo(l, b - off);
                stream.lineTo(l, b - off - len);
                stream.moveTo(r + off, b);
                stream.lineTo(r + off + len, b);
                stream.moveTo(r, b - off);
                stream.lineTo(r, b - off - len);
                stream.moveTo(l - off, t);
                stream.lineTo(l - off - len, t);
                stream.moveTo(l, t + off);
                stream.lineTo(l, t + off + len);
                stream.moveTo(r + off, t);
                stream.lineTo(r + off + len, t);
                stream.moveTo(r, t + off);
                stream.lineTo(r, t + off + len);
                stream.stroke();
            }
            doc.save(pdfPath.toFile());
        }
        return new MockMultipartFile(
                "fileInput",
                "marked.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                Files.readAllBytes(pdfPath));
    }

    private SetPageBoxesRequest request(MockMultipartFile file) {
        SetPageBoxesRequest request = new SetPageBoxesRequest();
        request.setFileInput(file);
        return request;
    }

    /**
     * A page filled with red, optionally with the lower half (in unrotated user space) painted
     * blue, optionally carrying a /Rotate entry.
     */
    private MockMultipartFile createColoredPdf(boolean splitColors, boolean rotate)
            throws IOException {
        Path pdfPath = tempDir.resolve("colored.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            if (rotate) {
                page.setRotation(90);
            }
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.setNonStrokingColor(new Color(200, 30, 30));
                stream.addRect(0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                stream.fill();
                if (splitColors) {
                    stream.setNonStrokingColor(new Color(30, 30, 200));
                    stream.addRect(0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight() / 2);
                    stream.fill();
                }
            }
            doc.save(pdfPath.toFile());
        }
        return new MockMultipartFile(
                "fileInput",
                "colored.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                Files.readAllBytes(pdfPath));
    }

    private static void assertRedish(int rgb, String where) {
        Color c = new Color(rgb);
        assertTrue(
                c.getRed() > 120 && c.getGreen() < 120 && c.getBlue() < 120,
                "expected red at " + where + " but got " + c);
    }

    private static void assertBlueish(int rgb, String where) {
        Color c = new Color(rgb);
        assertTrue(
                c.getBlue() > 120 && c.getRed() < 120,
                "expected blue at " + where + " but got " + c);
    }

    private static int darkestChannel(BufferedImage img, int x0, int y0, int w, int h) {
        int min = 255;
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                Color c = new Color(img.getRGB(x, y));
                min = Math.min(min, Math.min(c.getRed(), Math.min(c.getGreen(), c.getBlue())));
            }
        }
        return min;
    }

    @Test
    void testSetExplicitBoxes() throws Exception {
        SetPageBoxesRequest request = request(createPdf(null));
        request.setTrimBox("20,20,400,600");
        request.setBleedBox("10,10,420,620");

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            PDPage page = result.getPage(0);
            assertRectEquals(20, 20, 400, 600, page.getTrimBox());
            assertRectEquals(10, 10, 420, 620, page.getBleedBox());
            assertRectEquals(
                    0,
                    0,
                    PDRectangle.A4.getWidth(),
                    PDRectangle.A4.getHeight(),
                    page.getMediaBox());
        }
    }

    @Test
    void testBleedMmExpandsAroundTrimBox() throws Exception {
        SetPageBoxesRequest request = request(createPdf(new PDRectangle(20, 20, 400, 600)));
        request.setBleedMm(5);

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            PDRectangle bleed = result.getPage(0).getBleedBox();
            assertRectEquals(20 - 5 * MM, 20 - 5 * MM, 400 + 10 * MM, 600 + 10 * MM, bleed);
        }
    }

    @Test
    void testTrimMarginMmInsetsMediaBox() throws Exception {
        SetPageBoxesRequest request = request(createPdf(null));
        request.setTrimMarginMm(10);

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            PDRectangle trim = result.getPage(0).getTrimBox();
            assertRectEquals(
                    10 * MM,
                    10 * MM,
                    PDRectangle.A4.getWidth() - 20 * MM,
                    PDRectangle.A4.getHeight() - 20 * MM,
                    trim);
        }
    }

    @Test
    void testCopyMissingFromMediaBox() throws Exception {
        SetPageBoxesRequest request = request(createPdf(null));
        request.setCopyMissingFromMediaBox(true);

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            PDPage page = result.getPage(0);
            PDRectangle a4 = PDRectangle.A4;
            // The box getters fall back to CropBox/MediaBox, so the dictionary itself
            // must contain the entries for the copy to have really happened.
            for (COSName name :
                    new COSName[] {
                        COSName.CROP_BOX, COSName.TRIM_BOX, COSName.BLEED_BOX, COSName.ART_BOX
                    }) {
                assertNotNull(
                        page.getCOSObject().getItem(name), name.getName() + " was not written");
            }
            assertRectEquals(0, 0, a4.getWidth(), a4.getHeight(), page.getCropBox());
            assertRectEquals(0, 0, a4.getWidth(), a4.getHeight(), page.getTrimBox());
            assertRectEquals(0, 0, a4.getWidth(), a4.getHeight(), page.getBleedBox());
            assertRectEquals(0, 0, a4.getWidth(), a4.getHeight(), page.getArtBox());
        }
    }

    @Test
    void testNoParametersThrows() {
        SetPageBoxesRequest request = requestUnchecked();
        assertThrows(IllegalArgumentException.class, () -> controller.setPageBoxes(request));
    }

    @Test
    void testInvalidRectThrows() throws Exception {
        SetPageBoxesRequest request = request(createPdf(null));
        request.setTrimBox("1,2,3");
        assertThrows(IllegalArgumentException.class, () -> controller.setPageBoxes(request));
    }

    @Test
    void testNonFiniteRectThrows() throws Exception {
        SetPageBoxesRequest request = request(createPdf(null));
        request.setTrimBox("0,0,NaN,600");
        assertThrows(IllegalArgumentException.class, () -> controller.setPageBoxes(request));
        request.setTrimBox("0,0,Infinity,600");
        assertThrows(IllegalArgumentException.class, () -> controller.setPageBoxes(request));
    }

    @Test
    void testNonNumericRectThrows() throws Exception {
        SetPageBoxesRequest request = request(createPdf(null));
        request.setBleedBox("a,b,c,d");
        assertThrows(IllegalArgumentException.class, () -> controller.setPageBoxes(request));
    }

    private SetPageBoxesRequest requestUnchecked() {
        try {
            return request(createPdf(null));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testGenerateBleedPaintsNonWhiteBands() throws Exception {
        SetPageBoxesRequest request = request(createColoredPdf(false, false));
        request.setTrimMarginMm(5);
        request.setGenerateBleed(true);
        request.setBleedMm(5);
        request.setBleedMethod("MIRROR");

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            PDPage page = result.getPage(0);
            float m = 5 * MM;
            assertRectEquals(
                    m,
                    m,
                    PDRectangle.A4.getWidth() - 2 * m,
                    PDRectangle.A4.getHeight() - 2 * m,
                    page.getTrimBox());
            assertRectEquals(
                    0,
                    0,
                    PDRectangle.A4.getWidth(),
                    PDRectangle.A4.getHeight(),
                    page.getBleedBox());
            BufferedImage img = new PDFRenderer(result).renderImageWithDPI(0, 72, ImageType.RGB);
            // The bleed band is the ~14px ring between media edge and trim edge.
            int midY = img.getHeight() / 2;
            int midX = img.getWidth() / 2;
            assertRedish(img.getRGB(5, midY), "left band");
            assertRedish(img.getRGB(img.getWidth() - 5, midY), "right band");
            assertRedish(img.getRGB(midX, 5), "top band");
            assertRedish(img.getRGB(midX, img.getHeight() - 5), "bottom band");
            assertRedish(img.getRGB(7, 7), "top-left corner");
        }
    }

    @Test
    void testGenerateBleedAsymmetricSides() throws Exception {
        SetPageBoxesRequest request = request(createColoredPdf(false, false));
        request.setTrimMarginMm(10);
        request.setGenerateBleed(true);
        request.setBleedLeftMm(8);
        request.setBleedRightMm(2);
        request.setBleedBottomMm(4);
        request.setBleedTopMm(0);

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            PDRectangle trim = result.getPage(0).getTrimBox();
            PDRectangle bleed = result.getPage(0).getBleedBox();
            assertEquals(trim.getLowerLeftX() - 8 * MM, bleed.getLowerLeftX(), 0.01);
            assertEquals(trim.getUpperRightX() + 2 * MM, bleed.getUpperRightX(), 0.01);
            assertEquals(trim.getLowerLeftY() - 4 * MM, bleed.getLowerLeftY(), 0.01);
            assertEquals(trim.getUpperRightY(), bleed.getUpperRightY(), 0.01);
        }
    }

    @Test
    void testGenerateBleedOnRotatedPage() throws Exception {
        // Unrotated top half is red, bottom half blue; /Rotate=90 turns the page clockwise
        // for display, so the unrotated bottom (blue) shows on the viewer's left and the
        // unrotated top (red) on the viewer's right.
        SetPageBoxesRequest request = request(createColoredPdf(true, true));
        request.setTrimMarginMm(5);
        request.setGenerateBleed(true);
        request.setBleedMm(5);

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            assertEquals(90, result.getPage(0).getRotation());
            BufferedImage img = new PDFRenderer(result).renderImageWithDPI(0, 72, ImageType.RGB);
            int midY = img.getHeight() / 2;
            assertBlueish(img.getRGB(5, midY), "displayed left band");
            assertRedish(img.getRGB(img.getWidth() - 5, midY), "displayed right band");
        }
    }

    @Test
    void testGenerateBleedPixelRepeat() throws Exception {
        SetPageBoxesRequest request = request(createColoredPdf(false, false));
        request.setTrimMarginMm(5);
        request.setGenerateBleed(true);
        request.setBleedMm(5);
        request.setBleedMethod("PIXEL_REPEAT");

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            BufferedImage img = new PDFRenderer(result).renderImageWithDPI(0, 72, ImageType.RGB);
            assertRedish(img.getRGB(5, img.getHeight() / 2), "left band");
            assertRedish(img.getRGB(7, 7), "top-left corner");
        }
    }

    @Test
    void testCropMarksDrawnOutsideTrim() throws Exception {
        SetPageBoxesRequest request = request(createColoredPdf(false, false));
        request.setTrimMarginMm(5);
        request.setAddCropMarks(true);
        request.setCropMarkOffsetMm(3);
        request.setCropMarkLengthMm(5);
        request.setCropMarkWeightPt(1);

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            PDPage page = result.getPage(0);
            float m = 5 * MM;
            float markExtent = 8 * MM;
            // MediaBox grew just enough to hold the marks: trim.lly - (offset + length).
            assertEquals(m - markExtent, page.getMediaBox().getLowerLeftX(), 0.5);
            BufferedImage img = new PDFRenderer(result).renderImageWithDPI(0, 72, ImageType.RGB);
            // Bottom-left horizontal mark: y = trim.lly = 5mm, x in [trim.llx-8mm, trim.llx-3mm].
            // Crop origin is at media.llx = -3mm, so in pixels that is x in [0, 14] and
            // row imgH - 8mm.
            int row = Math.round(img.getHeight() - 8 * MM);
            int min = darkestChannel(img, 2, row - 1, 10, 3);
            assertTrue(min < 128, "no crop mark pixels found near bottom-left trim corner");
            // Marks live outside the trim: the artwork itself stays untouched.
            assertRedish(img.getRGB(img.getWidth() / 2, img.getHeight() / 2), "page center");
        }
    }

    @Test
    void testGenerateBleedUpscale() throws Exception {
        SetPageBoxesRequest request = request(createColoredPdf(false, false));
        request.setTrimMarginMm(5);
        request.setGenerateBleed(true);
        request.setBleedMm(5);
        request.setBleedMethod("UPSCALE");

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            BufferedImage img = new PDFRenderer(result).renderImageWithDPI(0, 72, ImageType.RGB);
            assertRedish(img.getRGB(5, img.getHeight() / 2), "left band");
            assertRedish(img.getRGB(img.getWidth() / 2, 5), "top band");
        }
    }

    @Test
    void testGenerateBleedIsReplayable() throws Exception {
        SetPageBoxesRequest first = request(createColoredPdf(false, false));
        first.setTrimMarginMm(5);
        first.setGenerateBleed(true);
        first.setBleedMm(5);
        byte[] once = drainBody(controller.setPageBoxes(first));

        // Feeding the result back in must not corrupt the bands or the boxes.
        MockMultipartFile again =
                new MockMultipartFile(
                        "fileInput", "once.pdf", MediaType.APPLICATION_PDF_VALUE, once);
        SetPageBoxesRequest second = request(again);
        second.setGenerateBleed(true);
        second.setBleedMm(5);

        ResponseEntity<Resource> response = controller.setPageBoxes(second);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            BufferedImage img = new PDFRenderer(result).renderImageWithDPI(0, 72, ImageType.RGB);
            assertRedish(img.getRGB(5, img.getHeight() / 2), "left band after replay");
            assertRedish(img.getRGB(img.getWidth() / 2, 5), "top band after replay");
        }
    }

    @Test
    void testGenerateBleedWithoutSourceThrows() throws Exception {
        SetPageBoxesRequest request = request(createPdf(null));
        request.setGenerateBleed(true);
        assertThrows(IllegalArgumentException.class, () -> controller.setPageBoxes(request));
    }

    @Test
    void testInvalidBleedMethodThrows() throws Exception {
        SetPageBoxesRequest request = request(createPdf(null));
        request.setGenerateBleed(true);
        request.setBleedMm(3);
        request.setBleedMethod("bogus");
        assertThrows(IllegalArgumentException.class, () -> controller.setPageBoxes(request));
    }

    @Test
    void testInvalidBleedDpiThrows() throws Exception {
        SetPageBoxesRequest request = request(createPdf(null));
        request.setGenerateBleed(true);
        request.setBleedMm(3);
        request.setBleedDpi(10);
        assertThrows(IllegalArgumentException.class, () -> controller.setPageBoxes(request));
    }

    @Test
    void testInvalidCropMarkParamsThrow() throws Exception {
        SetPageBoxesRequest request = request(createPdf(null));
        request.setAddCropMarks(true);
        request.setCropMarkLengthMm(0);
        assertThrows(IllegalArgumentException.class, () -> controller.setPageBoxes(request));
        request.setCropMarkLengthMm(5);
        request.setCropMarkOffsetMm(-1);
        assertThrows(IllegalArgumentException.class, () -> controller.setPageBoxes(request));
    }

    @Test
    void testNonFiniteBleedMmThrows() throws Exception {
        SetPageBoxesRequest request = request(createPdf(null));
        request.setTrimBox("0,0,100,100");
        request.setBleedMm(Float.NaN);
        assertThrows(IllegalArgumentException.class, () -> controller.setPageBoxes(request));
        request.setBleedMm(Float.POSITIVE_INFINITY);
        assertThrows(IllegalArgumentException.class, () -> controller.setPageBoxes(request));
    }

    @Test
    void testBleedBoxOutsideMediaGrowsMediaAndCrop() throws Exception {
        // A BleedBox sticking out of the MediaBox is dead geometry for viewers and
        // printers — MediaBox and CropBox must grow to cover it.
        SetPageBoxesRequest request = request(createPdf(null));
        request.setTrimBox("20,20,400,600");
        request.setBleedBox("-10,-10,440,640");

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            PDPage page = result.getPage(0);
            assertEquals(-10, page.getMediaBox().getLowerLeftX(), 0.01);
            assertEquals(-10, page.getMediaBox().getLowerLeftY(), 0.01);
            assertEquals(PDRectangle.A4.getWidth() + 10, page.getMediaBox().getWidth(), 0.01);
            assertEquals(PDRectangle.A4.getHeight() + 10, page.getMediaBox().getHeight(), 0.01);
            assertRectEquals(
                    page.getMediaBox().getLowerLeftX(),
                    page.getMediaBox().getLowerLeftY(),
                    page.getMediaBox().getWidth(),
                    page.getMediaBox().getHeight(),
                    page.getCropBox());
        }
    }

    @Test
    void testBleedMmAloneGrowsMediaAndCrop() throws Exception {
        // bleedMm without generated content still grows MediaBox/CropBox so the new
        // BleedBox stays inside the page.
        SetPageBoxesRequest request = request(createPdf(null));
        request.setBleedMm(5);

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            PDRectangle media = result.getPage(0).getMediaBox();
            PDRectangle crop = result.getPage(0).getCropBox();
            PDRectangle bleed = result.getPage(0).getBleedBox();
            assertEquals(-5 * MM, media.getLowerLeftX(), 0.01);
            assertEquals(PDRectangle.A4.getWidth() + 10 * MM, media.getWidth(), 0.01);
            assertRectEquals(
                    media.getLowerLeftX(),
                    media.getLowerLeftY(),
                    media.getWidth(),
                    media.getHeight(),
                    crop);
            assertRectEquals(
                    media.getLowerLeftX(),
                    media.getLowerLeftY(),
                    media.getWidth(),
                    media.getHeight(),
                    bleed);
        }
    }

    @Test
    void testOversizedBleedBandRejectedBeforeRender() throws Exception {
        // A large-format page at 600 DPI would raster to a gigabyte-scale image; the
        // band bound must reject the request instead of attempting the render.
        Path pdfPath = tempDir.resolve("huge.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(0, 0, 200_000, 200_000)));
            doc.save(pdfPath.toFile());
        }
        MockMultipartFile file =
                new MockMultipartFile(
                        "fileInput",
                        "huge.pdf",
                        MediaType.APPLICATION_PDF_VALUE,
                        Files.readAllBytes(pdfPath));

        SetPageBoxesRequest request = request(file);
        request.setGenerateBleed(true);
        request.setBleedMm(10);
        request.setBleedDpi(600);
        request.setBleedMethod("MIRROR_IMAGE");

        assertThrows(IllegalArgumentException.class, () -> controller.setPageBoxes(request));
    }

    @Test
    void testDeriveTrimBoxFromCropMarks() throws Exception {
        float m = 15 * MM;
        PDRectangle painted =
                new PDRectangle(
                        m,
                        m,
                        PDRectangle.A4.getWidth() - 2 * m,
                        PDRectangle.A4.getHeight() - 2 * m);
        SetPageBoxesRequest request = request(createPdfWithCropMarks(painted));
        request.setDeriveFromCropMarks(true);

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            assertRectEquals(
                    painted.getLowerLeftX(),
                    painted.getLowerLeftY(),
                    painted.getWidth(),
                    painted.getHeight(),
                    result.getPage(0).getTrimBox());
        }
    }

    @Test
    void testDeriveTrimBoxFromCropMarksAndBleed() throws Exception {
        float m = 15 * MM;
        PDRectangle painted =
                new PDRectangle(
                        m,
                        m,
                        PDRectangle.A4.getWidth() - 2 * m,
                        PDRectangle.A4.getHeight() - 2 * m);
        SetPageBoxesRequest request = request(createPdfWithCropMarks(painted));
        request.setDeriveFromCropMarks(true);
        request.setBleedMm(3);

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            PDPage page = result.getPage(0);
            assertRectEquals(
                    painted.getLowerLeftX() - 3 * MM,
                    painted.getLowerLeftY() - 3 * MM,
                    painted.getWidth() + 6 * MM,
                    painted.getHeight() + 6 * MM,
                    page.getBleedBox());
            assertRectEquals(
                    painted.getLowerLeftX(),
                    painted.getLowerLeftY(),
                    painted.getWidth(),
                    painted.getHeight(),
                    page.getTrimBox());
        }
    }

    @Test
    void testDeriveFromCropMarksNonePaintedThrows() throws Exception {
        SetPageBoxesRequest request = request(createPdf(null));
        request.setDeriveFromCropMarks(true);
        assertThrows(IllegalArgumentException.class, () -> controller.setPageBoxes(request));
    }

    @Test
    void testDeriveFromCropMarksExplicitTrimWins() throws Exception {
        float m = 15 * MM;
        PDRectangle painted =
                new PDRectangle(
                        m,
                        m,
                        PDRectangle.A4.getWidth() - 2 * m,
                        PDRectangle.A4.getHeight() - 2 * m);
        SetPageBoxesRequest request = request(createPdfWithCropMarks(painted));
        request.setDeriveFromCropMarks(true);
        request.setTrimBox("20,20,400,600");

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            assertRectEquals(20, 20, 400, 600, result.getPage(0).getTrimBox());
        }
    }

    @Test
    void testDeriveFromCropMarksAmbiguousFoldMarksThrows() throws Exception {
        float m = 15 * MM;
        PDRectangle painted =
                new PDRectangle(
                        m,
                        m,
                        PDRectangle.A4.getWidth() - 2 * m,
                        PDRectangle.A4.getHeight() - 2 * m);
        Path pdfPath = tempDir.resolve("folded.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream stream =
                    new PDPageContentStream(
                            doc, page, PDPageContentStream.AppendMode.APPEND, true)) {
                stream.setLineWidth(0.25f);
                float off = 3 * MM;
                float len = 5 * MM;
                float l = painted.getLowerLeftX();
                float r = painted.getUpperRightX();
                float b = painted.getLowerLeftY();
                float t = painted.getUpperRightY();
                stream.moveTo(l - off, b);
                stream.lineTo(l - off - len, b);
                stream.moveTo(l, b - off);
                stream.lineTo(l, b - off - len);
                stream.moveTo(r + off, b);
                stream.lineTo(r + off + len, b);
                stream.moveTo(r, b - off);
                stream.lineTo(r, b - off - len);
                stream.moveTo(l - off, t);
                stream.lineTo(l - off - len, t);
                stream.moveTo(l, t + off);
                stream.lineTo(l, t + off + len);
                stream.moveTo(r + off, t);
                stream.lineTo(r + off + len, t);
                stream.moveTo(r, t + off);
                stream.lineTo(r, t + off + len);
                // Third vertical mark column: a centre fold mark top and bottom — a real trim
                // edge has exactly two columns, so detection must refuse rather than guess.
                float cx = PDRectangle.A4.getWidth() / 2;
                stream.moveTo(cx, t + off);
                stream.lineTo(cx, t + off + len);
                stream.moveTo(cx, b - off);
                stream.lineTo(cx, b - off - len);
                stream.stroke();
            }
            doc.save(pdfPath.toFile());
        }
        SetPageBoxesRequest request =
                request(
                        new MockMultipartFile(
                                "fileInput",
                                "folded.pdf",
                                MediaType.APPLICATION_PDF_VALUE,
                                Files.readAllBytes(pdfPath)));
        request.setDeriveFromCropMarks(true);
        assertThrows(IllegalArgumentException.class, () -> controller.setPageBoxes(request));
    }

    @Test
    void testDeriveFromCropMarksFilledRectMarks() throws Exception {
        float m = 15 * MM;
        PDRectangle painted =
                new PDRectangle(
                        m,
                        m,
                        PDRectangle.A4.getWidth() - 2 * m,
                        PDRectangle.A4.getHeight() - 2 * m);
        Path pdfPath = tempDir.resolve("rectmarks.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream stream =
                    new PDPageContentStream(
                            doc, page, PDPageContentStream.AppendMode.APPEND, true)) {
                float off = 3 * MM;
                float len = 5 * MM;
                float w = 0.5f;
                float l = painted.getLowerLeftX();
                float r = painted.getUpperRightX();
                float b = painted.getLowerLeftY();
                float t = painted.getUpperRightY();
                stream.addRect(l - off - len, b - w / 2, len, w);
                stream.addRect(l - w / 2, b - off - len, w, len);
                stream.addRect(r + off, b - w / 2, len, w);
                stream.addRect(r - w / 2, b - off - len, w, len);
                stream.addRect(l - off - len, t - w / 2, len, w);
                stream.addRect(l - w / 2, t + off, w, len);
                stream.addRect(r + off, t - w / 2, len, w);
                stream.addRect(r - w / 2, t + off, w, len);
                stream.fill();
            }
            doc.save(pdfPath.toFile());
        }
        SetPageBoxesRequest request =
                request(
                        new MockMultipartFile(
                                "fileInput",
                                "rectmarks.pdf",
                                MediaType.APPLICATION_PDF_VALUE,
                                Files.readAllBytes(pdfPath)));
        request.setDeriveFromCropMarks(true);

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            PDRectangle detected = result.getPage(0).getTrimBox();
            assertEquals(painted.getLowerLeftX(), detected.getLowerLeftX(), 0.5);
            assertEquals(painted.getUpperRightX(), detected.getUpperRightX(), 0.5);
            assertEquals(painted.getLowerLeftY(), detected.getLowerLeftY(), 0.5);
            assertEquals(painted.getUpperRightY(), detected.getUpperRightY(), 0.5);
        }
    }

    @Test
    void testDeriveFromCropMarksStrokedRectMarks() throws Exception {
        float m = 15 * MM;
        PDRectangle painted =
                new PDRectangle(
                        m,
                        m,
                        PDRectangle.A4.getWidth() - 2 * m,
                        PDRectangle.A4.getHeight() - 2 * m);
        Path pdfPath = tempDir.resolve("strokedrectmarks.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream stream =
                    new PDPageContentStream(
                            doc, page, PDPageContentStream.AppendMode.APPEND, true)) {
                stream.setLineWidth(0.25f);
                float off = 3 * MM;
                float len = 5 * MM;
                float w = 0.5f;
                float l = painted.getLowerLeftX();
                float r = painted.getUpperRightX();
                float b = painted.getLowerLeftY();
                float t = painted.getUpperRightY();
                stream.addRect(l - off - len, b - w / 2, len, w);
                stream.addRect(l - w / 2, b - off - len, w, len);
                stream.addRect(r + off, b - w / 2, len, w);
                stream.addRect(r - w / 2, b - off - len, w, len);
                stream.addRect(l - off - len, t - w / 2, len, w);
                stream.addRect(l - w / 2, t + off, w, len);
                stream.addRect(r + off, t - w / 2, len, w);
                stream.addRect(r - w / 2, t + off, w, len);
                stream.stroke();
            }
            doc.save(pdfPath.toFile());
        }
        SetPageBoxesRequest request =
                request(
                        new MockMultipartFile(
                                "fileInput",
                                "strokedrectmarks.pdf",
                                MediaType.APPLICATION_PDF_VALUE,
                                Files.readAllBytes(pdfPath)));
        request.setDeriveFromCropMarks(true);

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            PDRectangle detected = result.getPage(0).getTrimBox();
            assertRectEquals(
                    painted.getLowerLeftX(),
                    painted.getLowerLeftY(),
                    painted.getWidth(),
                    painted.getHeight(),
                    detected);
        }
    }

    @Test
    void testDeriveFromCropMarksRotatedPage() throws Exception {
        float m = 15 * MM;
        PDRectangle painted =
                new PDRectangle(
                        m,
                        m,
                        PDRectangle.A4.getWidth() - 2 * m,
                        PDRectangle.A4.getHeight() - 2 * m);
        Path pdfPath = tempDir.resolve("rotated.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            page.setRotation(90);
            doc.addPage(page);
            try (PDPageContentStream stream =
                    new PDPageContentStream(
                            doc, page, PDPageContentStream.AppendMode.APPEND, true)) {
                stream.setLineWidth(0.25f);
                float off = 3 * MM;
                float len = 5 * MM;
                float l = painted.getLowerLeftX();
                float r = painted.getUpperRightX();
                float b = painted.getLowerLeftY();
                float t = painted.getUpperRightY();
                stream.moveTo(l - off, b);
                stream.lineTo(l - off - len, b);
                stream.moveTo(l, b - off);
                stream.lineTo(l, b - off - len);
                stream.moveTo(r + off, b);
                stream.lineTo(r + off + len, b);
                stream.moveTo(r, b - off);
                stream.lineTo(r, b - off - len);
                stream.moveTo(l - off, t);
                stream.lineTo(l - off - len, t);
                stream.moveTo(l, t + off);
                stream.lineTo(l, t + off + len);
                stream.moveTo(r + off, t);
                stream.lineTo(r + off + len, t);
                stream.moveTo(r, t + off);
                stream.lineTo(r, t + off + len);
                stream.stroke();
            }
            doc.save(pdfPath.toFile());
        }
        SetPageBoxesRequest request =
                request(
                        new MockMultipartFile(
                                "fileInput",
                                "rotated.pdf",
                                MediaType.APPLICATION_PDF_VALUE,
                                Files.readAllBytes(pdfPath)));
        request.setDeriveFromCropMarks(true);

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            PDRectangle detected = result.getPage(0).getTrimBox();
            // Page boxes live in unrotated user space; detection must return the painted box
            // unchanged even though the page carries /Rotate 90.
            assertRectEquals(
                    painted.getLowerLeftX(),
                    painted.getLowerLeftY(),
                    painted.getWidth(),
                    painted.getHeight(),
                    detected);
        }
    }

    @Test
    void testDeriveFromCropMarksSmallTrim() throws Exception {
        // A business-card trim centred on an A4 sheet: well under 30% of the page extent, so
        // the former span-fraction heuristic could not see it; the quad consistency check can.
        PDRectangle painted = new PDRectangle(220, 320, 120, 160);
        Path pdfPath = tempDir.resolve("smalltrim.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream stream =
                    new PDPageContentStream(
                            doc, page, PDPageContentStream.AppendMode.APPEND, true)) {
                stream.setLineWidth(0.25f);
                float off = 3 * MM;
                float len = 5 * MM;
                float l = painted.getLowerLeftX();
                float r = painted.getUpperRightX();
                float b = painted.getLowerLeftY();
                float t = painted.getUpperRightY();
                stream.moveTo(l - off, b);
                stream.lineTo(l - off - len, b);
                stream.moveTo(l, b - off);
                stream.lineTo(l, b - off - len);
                stream.moveTo(r + off, b);
                stream.lineTo(r + off + len, b);
                stream.moveTo(r, b - off);
                stream.lineTo(r, b - off - len);
                stream.moveTo(l - off, t);
                stream.lineTo(l - off - len, t);
                stream.moveTo(l, t + off);
                stream.lineTo(l, t + off + len);
                stream.moveTo(r + off, t);
                stream.lineTo(r + off + len, t);
                stream.moveTo(r, t + off);
                stream.lineTo(r, t + off + len);
                stream.stroke();
            }
            doc.save(pdfPath.toFile());
        }
        SetPageBoxesRequest request =
                request(
                        new MockMultipartFile(
                                "fileInput",
                                "smalltrim.pdf",
                                MediaType.APPLICATION_PDF_VALUE,
                                Files.readAllBytes(pdfPath)));
        request.setDeriveFromCropMarks(true);

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            assertRectEquals(
                    painted.getLowerLeftX(),
                    painted.getLowerLeftY(),
                    painted.getWidth(),
                    painted.getHeight(),
                    result.getPage(0).getTrimBox());
        }
    }

    @Test
    void testDeriveFromCropMarksFreeformFilledMarks() throws Exception {
        // Some generators fill thin quadrilateral paths (m/l/h f) instead of using `re` or
        // stroking a hairline; the strip's midline still marks the trim edge.
        float m = 15 * MM;
        PDRectangle painted =
                new PDRectangle(
                        m,
                        m,
                        PDRectangle.A4.getWidth() - 2 * m,
                        PDRectangle.A4.getHeight() - 2 * m);
        Path pdfPath = tempDir.resolve("freeform.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream stream =
                    new PDPageContentStream(
                            doc, page, PDPageContentStream.AppendMode.APPEND, true)) {
                float off = 3 * MM;
                float len = 5 * MM;
                float w = 0.5f;
                float l = painted.getLowerLeftX();
                float r = painted.getUpperRightX();
                float b = painted.getLowerLeftY();
                float t = painted.getUpperRightY();
                for (float[] mark :
                        new float[][] {
                            {l - off - len, b - w / 2, l - off, b + w / 2},
                            {l - w / 2, b - off - len, l + w / 2, b - off},
                            {r + off, b - w / 2, r + off + len, b + w / 2},
                            {r - w / 2, b - off - len, r + w / 2, b - off},
                            {l - off - len, t - w / 2, l - off, t + w / 2},
                            {l - w / 2, t + off, l + w / 2, t + off + len},
                            {r + off, t - w / 2, r + off + len, t + w / 2},
                            {r - w / 2, t + off, r + w / 2, t + off + len},
                        }) {
                    stream.moveTo(mark[0], mark[1]);
                    stream.lineTo(mark[2], mark[1]);
                    stream.lineTo(mark[2], mark[3]);
                    stream.lineTo(mark[0], mark[3]);
                    stream.closePath();
                }
                stream.fill();
            }
            doc.save(pdfPath.toFile());
        }
        SetPageBoxesRequest request =
                request(
                        new MockMultipartFile(
                                "fileInput",
                                "freeform.pdf",
                                MediaType.APPLICATION_PDF_VALUE,
                                Files.readAllBytes(pdfPath)));
        request.setDeriveFromCropMarks(true);

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            assertRectEquals(
                    painted.getLowerLeftX(),
                    painted.getLowerLeftY(),
                    painted.getWidth(),
                    painted.getHeight(),
                    result.getPage(0).getTrimBox());
        }
    }

    @Test
    void testDeriveFromCropMarksInAnnotationAppearance() throws Exception {
        // Printer's marks painted inside an annotation's normal appearance stream still count:
        // the detector scans appearance streams in page user space.
        float m = 15 * MM;
        PDRectangle painted =
                new PDRectangle(
                        m,
                        m,
                        PDRectangle.A4.getWidth() - 2 * m,
                        PDRectangle.A4.getHeight() - 2 * m);
        Path pdfPath = tempDir.resolve("annotmarks.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDAnnotationRubberStamp annotation = new PDAnnotationRubberStamp();
            annotation.setRectangle(PDRectangle.A4);
            PDAppearanceStream appearance = new PDAppearanceStream(doc);
            appearance.setBBox(PDRectangle.A4);
            try (PDAppearanceContentStream appearanceStream =
                    new PDAppearanceContentStream(appearance)) {
                appearanceStream.setLineWidth(0.25f);
                float off = 3 * MM;
                float len = 5 * MM;
                float l = painted.getLowerLeftX();
                float r = painted.getUpperRightX();
                float b = painted.getLowerLeftY();
                float t = painted.getUpperRightY();
                appearanceStream.moveTo(l - off, b);
                appearanceStream.lineTo(l - off - len, b);
                appearanceStream.moveTo(l, b - off);
                appearanceStream.lineTo(l, b - off - len);
                appearanceStream.moveTo(r + off, b);
                appearanceStream.lineTo(r + off + len, b);
                appearanceStream.moveTo(r, b - off);
                appearanceStream.lineTo(r, b - off - len);
                appearanceStream.moveTo(l - off, t);
                appearanceStream.lineTo(l - off - len, t);
                appearanceStream.moveTo(l, t + off);
                appearanceStream.lineTo(l, t + off + len);
                appearanceStream.moveTo(r + off, t);
                appearanceStream.lineTo(r + off + len, t);
                appearanceStream.moveTo(r, t + off);
                appearanceStream.lineTo(r, t + off + len);
                appearanceStream.stroke();
            }
            PDAppearanceDictionary appearanceDictionary = new PDAppearanceDictionary();
            appearanceDictionary.setNormalAppearance(appearance);
            annotation.setAppearance(appearanceDictionary);
            page.getAnnotations().add(annotation);
            doc.save(pdfPath.toFile());
        }
        SetPageBoxesRequest request =
                request(
                        new MockMultipartFile(
                                "fileInput",
                                "annotmarks.pdf",
                                MediaType.APPLICATION_PDF_VALUE,
                                Files.readAllBytes(pdfPath)));
        request.setDeriveFromCropMarks(true);

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            assertRectEquals(
                    painted.getLowerLeftX(),
                    painted.getLowerLeftY(),
                    painted.getWidth(),
                    painted.getHeight(),
                    result.getPage(0).getTrimBox());
        }
    }

    @Test
    void testDeriveFromCropMarksBeyondPageGrowsBoxes() throws Exception {
        // Marks on a sheet larger than the declared MediaBox: the detected trim extends 5 mm
        // past every edge, so MediaBox and CropBox must grow to keep it visible.
        PDRectangle painted =
                new PDRectangle(
                        -5 * MM,
                        -5 * MM,
                        PDRectangle.A4.getWidth() + 10 * MM,
                        PDRectangle.A4.getHeight() + 10 * MM);
        Path pdfPath = tempDir.resolve("oversized.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream stream =
                    new PDPageContentStream(
                            doc, page, PDPageContentStream.AppendMode.APPEND, true)) {
                stream.setLineWidth(0.25f);
                float off = 3 * MM;
                float len = 5 * MM;
                float l = painted.getLowerLeftX();
                float r = painted.getUpperRightX();
                float b = painted.getLowerLeftY();
                float t = painted.getUpperRightY();
                stream.moveTo(l - off, b);
                stream.lineTo(l - off - len, b);
                stream.moveTo(l, b - off);
                stream.lineTo(l, b - off - len);
                stream.moveTo(r + off, b);
                stream.lineTo(r + off + len, b);
                stream.moveTo(r, b - off);
                stream.lineTo(r, b - off - len);
                stream.moveTo(l - off, t);
                stream.lineTo(l - off - len, t);
                stream.moveTo(l, t + off);
                stream.lineTo(l, t + off + len);
                stream.moveTo(r + off, t);
                stream.lineTo(r + off + len, t);
                stream.moveTo(r, t + off);
                stream.lineTo(r, t + off + len);
                stream.stroke();
            }
            doc.save(pdfPath.toFile());
        }
        SetPageBoxesRequest request =
                request(
                        new MockMultipartFile(
                                "fileInput",
                                "oversized.pdf",
                                MediaType.APPLICATION_PDF_VALUE,
                                Files.readAllBytes(pdfPath)));
        request.setDeriveFromCropMarks(true);

        ResponseEntity<Resource> response = controller.setPageBoxes(request);

        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(drainBody(response))) {
            PDPage page = result.getPage(0);
            assertRectEquals(
                    painted.getLowerLeftX(),
                    painted.getLowerLeftY(),
                    painted.getWidth(),
                    painted.getHeight(),
                    page.getTrimBox());
            assertRectEquals(
                    painted.getLowerLeftX(),
                    painted.getLowerLeftY(),
                    painted.getWidth(),
                    painted.getHeight(),
                    page.getMediaBox());
            assertRectEquals(
                    painted.getLowerLeftX(),
                    painted.getLowerLeftY(),
                    painted.getWidth(),
                    painted.getHeight(),
                    page.getCropBox());
        }
    }
}
