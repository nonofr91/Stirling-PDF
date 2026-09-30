package stirling.software.SPDF.controller.api.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import java.awt.image.BufferedImage;
import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import stirling.software.SPDF.model.api.security.PrintPreflightReport;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Category;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Finding;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Severity;
import stirling.software.SPDF.model.api.security.PrintPreflightRequest;
import stirling.software.SPDF.service.preflight.PrintPreflightService;
import stirling.software.common.model.api.PDFFile;
import stirling.software.common.service.CustomPDFDocumentFactory;

@ExtendWith(MockitoExtension.class)
class PrintPreflightControllerTest {

    private static final float MM = 72f / 25.4f;

    @Mock private CustomPDFDocumentFactory pdfDocumentFactory;
    private PrintPreflightController controller;

    @BeforeEach
    void setUp() throws IOException {
        controller = new PrintPreflightController(new PrintPreflightService(), pdfDocumentFactory);
        lenient()
                .when(pdfDocumentFactory.load(any(PDFFile.class)))
                .thenAnswer(
                        inv ->
                                Loader.loadPDF(
                                        ((PDFFile) inv.getArgument(0)).getFileInput().getBytes()));
    }

    private static byte[] toBytes(PDDocument doc) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        doc.save(baos);
        doc.close();
        return baos.toByteArray();
    }

    private static PrintPreflightRequest request(byte[] pdf) {
        PrintPreflightRequest req = new PrintPreflightRequest();
        req.setFileInput(new MockMultipartFile("fileInput", "test.pdf", "application/pdf", pdf));
        return req;
    }

    private static boolean hasFinding(PrintPreflightReport r, String code) {
        return r.getFindings().stream().anyMatch(f -> code.equals(f.getCode()));
    }

    private static Finding finding(PrintPreflightReport r, String code) {
        return r.getFindings().stream()
                .filter(f -> code.equals(f.getCode()))
                .findFirst()
                .orElse(null);
    }

    /** Single A4 page, TrimBox inset 5mm, BleedBox = media, full-red painted content. */
    private static byte[] redBleedPdf() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(0.8f, 0f, 0f);
            cs.addRect(0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            cs.fill();
        }
        page.setTrimBox(
                new PDRectangle(
                        5 * MM,
                        5 * MM,
                        page.getMediaBox().getWidth() - 10 * MM,
                        page.getMediaBox().getHeight() - 10 * MM));
        page.setBleedBox(page.getMediaBox());
        return toBytes(doc);
    }

    @Test
    void testCleanPrintReadyDocument() throws Exception {
        byte[] pdf = redBleedPdf();
        ResponseEntity<PrintPreflightReport> response = controller.printPreflight(request(pdf));
        PrintPreflightReport report = response.getBody();
        assertNotNull(report);
        assertEquals(1, report.getPageCount());
        assertFalse(hasFinding(report, "BLEED_MISSING"));
        assertFalse(hasFinding(report, "BLEED_INSUFFICIENT"));
        assertFalse(hasFinding(report, "BLEED_UNPAINTED"));
        assertFalse(hasFinding(report, "FONT_NOT_EMBEDDED"));
        assertTrue(report.getCounts().getErrors() == 0, "expected no errors, got " + report);
    }

    @Test
    void testMissingTrimAndBleedBoxes() throws Exception {
        PDDocument doc = new PDDocument();
        doc.addPage(new PDPage(PDRectangle.A4));
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(hasFinding(report, "TRIMBOX_MISSING"));
        assertTrue(hasFinding(report, "BLEED_MISSING"));
        assertEquals(Severity.ERROR, finding(report, "BLEED_MISSING").getSeverity());
    }

    @Test
    void testInsufficientBleed() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        page.setTrimBox(
                new PDRectangle(
                        5 * MM,
                        5 * MM,
                        page.getMediaBox().getWidth() - 10 * MM,
                        page.getMediaBox().getHeight() - 10 * MM));
        // only 1mm bleed around the trim, required is 3mm
        page.setBleedBox(
                new PDRectangle(
                        4 * MM,
                        4 * MM,
                        page.getMediaBox().getWidth() - 8 * MM,
                        page.getMediaBox().getHeight() - 8 * MM));
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(hasFinding(report, "BLEED_INSUFFICIENT"));
    }

    @Test
    void testDeclaredBleedNotPainted() throws Exception {
        // bleed box declared but the page is white — coverage must flag it
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(1f, 1f, 1f);
            cs.addRect(0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            cs.fill();
        }
        page.setTrimBox(
                new PDRectangle(
                        5 * MM,
                        5 * MM,
                        page.getMediaBox().getWidth() - 10 * MM,
                        page.getMediaBox().getHeight() - 10 * MM));
        page.setBleedBox(page.getMediaBox());
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(hasFinding(report, "BLEED_UNPAINTED"));
    }

    @Test
    void testNonEmbeddedFont() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            cs.beginText();
            cs.newLineAtOffset(50, 700);
            cs.showText("Hello print");
            cs.endText();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        Finding f = finding(report, "FONT_NOT_EMBEDDED");
        assertNotNull(f);
        assertEquals(Severity.ERROR, f.getSeverity());
        assertEquals(Category.FONTS, f.getCategory());
        assertTrue(f.getPages().contains(1));
        assertFalse(report.getFacts().getFonts().isEmpty());
        assertFalse(report.getFacts().getFonts().get(0).isEmbedded());
    }

    @Test
    void testRgbContentFlagged() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(1f, 0f, 0f);
            cs.addRect(50, 50, 100, 100);
            cs.fill();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(hasFinding(report, "COLOR_RGB_USED"));
        assertTrue(report.getFacts().getColorSpaces().contains("DeviceRGB"));
    }

    @Test
    void testLowResolutionImage() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        PDImageXObject xo = LosslessFactory.createFromImage(doc, img);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(xo, 50, 50, 300, 300); // 10px over 300pt ≈ 2.4 dpi
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(hasFinding(report, "IMAGE_LOW_RES"));
        assertEquals(1, report.getFacts().getImageCount());
        assertTrue(report.getFacts().getLowResImageCount() > 0);
    }

    @Test
    void testHairlineDetection() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setLineWidth(0.1f);
            cs.moveTo(50, 50);
            cs.lineTo(400, 50);
            cs.stroke();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(hasFinding(report, "HAIRLINE"));
    }

    @Test
    void testTransparencyDetection() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(0.5f);
            cs.setGraphicsStateParameters(gs);
            cs.setNonStrokingColor(0f, 0f, 0f);
            cs.addRect(50, 50, 100, 100);
            cs.fill();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(hasFinding(report, "TRANSPARENCY"));
        assertTrue(report.getFacts().isTransparencyUsed());
    }

    @Test
    void testAnnotationInsideTrim() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        page.setTrimBox(
                new PDRectangle(
                        5 * MM,
                        5 * MM,
                        page.getMediaBox().getWidth() - 10 * MM,
                        page.getMediaBox().getHeight() - 10 * MM));
        PDAnnotationText note = new PDAnnotationText();
        note.setRectangle(new PDRectangle(50, 700, 20, 20));
        note.setContents("fix me");
        page.setAnnotations(java.util.List.of(note));
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(hasFinding(report, "ANNOTATION_IN_TRIM"));
    }

    @Test
    void testMixedPageSizes() throws Exception {
        PDDocument doc = new PDDocument();
        doc.addPage(new PDPage(PDRectangle.A4));
        doc.addPage(new PDPage(PDRectangle.LETTER));
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(hasFinding(report, "MIXED_PAGE_SIZES"));
        assertEquals(2, report.getFacts().getPageSizes().size());
    }

    @Test
    void testValidationRejectsBadParams() {
        PrintPreflightRequest req = request(new byte[] {1});
        req.setRequiredBleedMm(-1);
        assertThrows(IllegalArgumentException.class, () -> controller.printPreflight(req));
        req.setRequiredBleedMm(Float.NaN);
        assertThrows(IllegalArgumentException.class, () -> controller.printPreflight(req));
        req.setRequiredBleedMm(3);
        req.setMinImageDpi(0);
        assertThrows(IllegalArgumentException.class, () -> controller.printPreflight(req));
        req.setMinImageDpi(150);
        req.setHairlineThresholdPt(-0.5f);
        assertThrows(IllegalArgumentException.class, () -> controller.printPreflight(req));
    }

    @Test
    void testMissingFileRejected() {
        PrintPreflightRequest req = new PrintPreflightRequest();
        assertThrows(Exception.class, () -> controller.printPreflight(req));
    }
}
