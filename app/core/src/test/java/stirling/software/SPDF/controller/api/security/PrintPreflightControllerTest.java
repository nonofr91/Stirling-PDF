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
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import stirling.software.SPDF.model.api.security.PrintPreflightReport;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Category;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Finding;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.FindingArea;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Severity;
import stirling.software.SPDF.model.api.security.PrintPreflightRequest;
import stirling.software.SPDF.service.preflight.PrintPreflightService;
import stirling.software.common.model.api.PDFFile;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.TempFileManager;

@ExtendWith(MockitoExtension.class)
class PrintPreflightControllerTest {

    private static final float MM = 72f / 25.4f;

    @Mock private CustomPDFDocumentFactory pdfDocumentFactory;
    @Mock private TempFileManager tempFileManager;
    private PrintPreflightController controller;

    @BeforeEach
    void setUp() throws IOException {
        controller =
                new PrintPreflightController(
                        new PrintPreflightService(), pdfDocumentFactory, tempFileManager);
        lenient()
                .when(tempFileManager.createTempFile(any()))
                .thenAnswer(inv -> java.io.File.createTempFile("pf-test", ".pdf"));
        lenient()
                .when(tempFileManager.createManagedTempFile(any()))
                .thenAnswer(
                        inv ->
                                new stirling.software.common.util.TempFile(
                                        tempFileManager, inv.getArgument(0)));
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
        // English report text keeps assertions independent of the test JVM's default locale.
        req.setReportLanguage("en");
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
        note.setPrinted(true);
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

    @Test
    void testLowResImageFindingCarriesArea() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        PDImageXObject xo = LosslessFactory.createFromImage(doc, img);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(xo, 50, 100, 300, 300);
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        Finding f = finding(report, "IMAGE_LOW_RES");
        assertNotNull(f);
        assertFalse(f.getAreas().isEmpty());
        FindingArea area = f.getAreas().get(0);
        assertEquals(1, area.getPage());
        assertEquals(50, area.getX(), 0.5);
        assertEquals(100, area.getY(), 0.5);
        assertEquals(300, area.getWidth(), 0.5);
        assertEquals(300, area.getHeight(), 0.5);
    }

    @Test
    void testHairlineFindingCarriesArea() throws Exception {
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
        Finding f = finding(report, "HAIRLINE");
        assertNotNull(f);
        assertFalse(f.getAreas().isEmpty());
        FindingArea area = f.getAreas().get(0);
        assertEquals(1, area.getPage());
        assertEquals(50, area.getX(), 0.5);
        assertEquals(350, area.getWidth(), 0.5);
    }

    @Test
    void testInsufficientBleedAreasPointAtDeficientSide() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        page.setTrimBox(
                new PDRectangle(
                        5 * MM,
                        5 * MM,
                        page.getMediaBox().getWidth() - 10 * MM,
                        page.getMediaBox().getHeight() - 10 * MM));
        // 1mm declared bleed on every side, 3mm required — all four bands flagged.
        page.setBleedBox(
                new PDRectangle(
                        4 * MM,
                        4 * MM,
                        page.getMediaBox().getWidth() - 8 * MM,
                        page.getMediaBox().getHeight() - 8 * MM));
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        Finding f = finding(report, "BLEED_INSUFFICIENT");
        assertNotNull(f);
        assertEquals(4, f.getAreas().size());
        // Left gap: strip from required edge (5mm-3mm=2mm) to declared edge (4mm).
        FindingArea left =
                f.getAreas().stream()
                        .filter(a -> "left: 1.0 mm declared".equals(a.getLabel()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(2 * MM, left.getX(), 0.5);
        assertEquals(2 * MM, left.getWidth(), 0.5);
    }

    @Test
    void testUnpaintedBleedAreasLocateWhiteBand() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            // Paint everything red except a white strip along the top bleed band.
            cs.setNonStrokingColor(0.8f, 0f, 0f);
            cs.addRect(0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            cs.fill();
            cs.setNonStrokingColor(1f, 1f, 1f);
            cs.addRect(
                    0,
                    page.getMediaBox().getHeight() - 3 * MM,
                    page.getMediaBox().getWidth(),
                    3 * MM);
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
        Finding f = finding(report, "BLEED_UNPAINTED");
        assertNotNull(f);
        assertFalse(f.getAreas().isEmpty());
        FindingArea top =
                f.getAreas().stream()
                        .filter(a -> a.getLabel() != null && a.getLabel().startsWith("top"))
                        .findFirst()
                        .orElseThrow(
                                () -> new AssertionError("no top-band area in " + f.getAreas()));
        // The white strip sits inside the top 3mm of the page.
        assertTrue(
                top.getY() > page.getMediaBox().getHeight() - 5 * MM,
                "white zone should sit near the top edge: " + top.getY());
    }

    @Test
    void testAnnotatedEndpointProducesMarkedCopy() throws Exception {
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

        PrintPreflightRequest req = request(pdf);
        req.setIncludeSummaryPage(false);
        ResponseEntity<Resource> response = controller.printPreflightAnnotated(req);
        assertEquals(200, response.getStatusCode().value());
        Resource body = response.getBody();
        assertNotNull(body);
        java.io.File tmp = java.io.File.createTempFile("annotated", ".pdf");
        body.getInputStream().transferTo(java.nio.file.Files.newOutputStream(tmp.toPath()));
        try (PDDocument result = Loader.loadPDF(tmp)) {
            java.util.List<?> annotations = result.getPage(0).getAnnotations();
            assertFalse(annotations.isEmpty(), "annotated copy should carry annotations");
        }
    }

    @Test
    void testAnnotatedEndpointAddsNoteForPageLevelFindings() throws Exception {
        // No TrimBox/BleedBox, no content: geometry findings carry no areas → note annotations.
        PDDocument doc = new PDDocument();
        doc.addPage(new PDPage(PDRectangle.A4));
        byte[] pdf = toBytes(doc);

        PrintPreflightRequest req = request(pdf);
        req.setIncludeSummaryPage(false);
        ResponseEntity<Resource> response = controller.printPreflightAnnotated(req);
        Resource body = response.getBody();
        assertNotNull(body);
        java.io.File tmp = java.io.File.createTempFile("annotated-notes", ".pdf");
        body.getInputStream().transferTo(java.nio.file.Files.newOutputStream(tmp.toPath()));
        try (PDDocument result = Loader.loadPDF(tmp)) {
            java.util.List<?> annotations = result.getPage(0).getAnnotations();
            assertTrue(
                    annotations.size() >= 2,
                    "expected notes for page-level findings, got " + annotations.size());
        }
    }

    /**
     * A thin stroke painted in a named separation — crop marks ("All") and die-cut paths
     * ("CutContour") are machine drivers, not spot ink on the artwork.
     */
    private static byte[] spotStrokePdf(String colorant) throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        org.apache.pdfbox.pdmodel.graphics.color.PDSeparation sep =
                new org.apache.pdfbox.pdmodel.graphics.color.PDSeparation();
        sep.setColorantName(colorant);
        sep.setAlternateColorSpace(org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK.INSTANCE);
        org.apache.pdfbox.cos.COSDictionary fn = new org.apache.pdfbox.cos.COSDictionary();
        fn.setInt(org.apache.pdfbox.cos.COSName.FUNCTION_TYPE, 2);
        org.apache.pdfbox.cos.COSArray c0 = new org.apache.pdfbox.cos.COSArray();
        org.apache.pdfbox.cos.COSArray c1 = new org.apache.pdfbox.cos.COSArray();
        for (int i = 0; i < 4; i++) {
            c0.add(org.apache.pdfbox.cos.COSInteger.ZERO);
            c1.add(org.apache.pdfbox.cos.COSInteger.ZERO);
        }
        c1.set(3, org.apache.pdfbox.cos.COSInteger.ONE);
        fn.setItem(org.apache.pdfbox.cos.COSName.C0, c0);
        fn.setItem(org.apache.pdfbox.cos.COSName.C1, c1);
        fn.setFloat(org.apache.pdfbox.cos.COSName.N, 1f);
        sep.setTintTransform(new org.apache.pdfbox.pdmodel.common.function.PDFunctionType2(fn));
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setStrokingColor(
                    new org.apache.pdfbox.pdmodel.graphics.color.PDColor(new float[] {1f}, sep));
            cs.setLineWidth(0.1f);
            cs.moveTo(50, 50);
            cs.lineTo(400, 50);
            cs.stroke();
        }
        return toBytes(doc);
    }

    @Test
    void testRegistrationAndCutPathSeparationsAreTechnical() throws Exception {
        for (String colorant : new String[] {"All", "CutContour"}) {
            PrintPreflightReport report =
                    controller.printPreflight(request(spotStrokePdf(colorant))).getBody();
            assertNotNull(report);
            assertFalse(
                    hasFinding(report, "COLOR_SPOT"),
                    colorant + " is a technical separation, not print ink");
            assertFalse(
                    hasFinding(report, "HAIRLINE"),
                    colorant + " paths drive finishing — never a hairline finding");
            assertTrue(
                    report.getFacts().getTechnicalSeparations().contains(colorant),
                    "expected " + colorant + " in technicalSeparations");
        }
    }

    @Test
    void testRealSpotInkStillFlags() throws Exception {
        PrintPreflightReport report =
                controller.printPreflight(request(spotStrokePdf("PANTONE 185 C"))).getBody();
        assertNotNull(report);
        assertTrue(hasFinding(report, "COLOR_SPOT"));
        assertTrue(hasFinding(report, "HAIRLINE"));
        assertTrue(report.getFacts().getTechnicalSeparations().isEmpty());
    }

    /** A hairline stroked inside an optional-content layer; printOff adds Usage/Print/OFF. */
    private static byte[] layerStrokePdf(
            String layerName,
            boolean printOff,
            org.apache.pdfbox.pdmodel.graphics.color.PDColor ink)
            throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup ocg =
                new org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup(
                        layerName);
        if (printOff) {
            org.apache.pdfbox.cos.COSDictionary usage = new org.apache.pdfbox.cos.COSDictionary();
            org.apache.pdfbox.cos.COSDictionary print = new org.apache.pdfbox.cos.COSDictionary();
            print.setItem(
                    org.apache.pdfbox.cos.COSName.getPDFName("PrintState"),
                    org.apache.pdfbox.cos.COSName.OFF);
            usage.setItem(org.apache.pdfbox.cos.COSName.getPDFName("Print"), print);
            ocg.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.getPDFName("Usage"), usage);
        }
        org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties ocProps =
                new org.apache.pdfbox.pdmodel.graphics.optionalcontent
                        .PDOptionalContentProperties();
        ocProps.addGroup(ocg);
        doc.getDocumentCatalog().setOCProperties(ocProps);
        page.setResources(new org.apache.pdfbox.pdmodel.PDResources());
        page.getResources().put(org.apache.pdfbox.cos.COSName.getPDFName("Layer"), ocg);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginMarkedContent(org.apache.pdfbox.cos.COSName.OC, ocg);
            cs.setStrokingColor(ink);
            cs.setLineWidth(0.1f);
            cs.moveTo(50, 50);
            cs.lineTo(400, 50);
            cs.stroke();
            cs.endMarkedContent();
        }
        return toBytes(doc);
    }

    @Test
    void testNonPrintingLayerContentIsTechnical() throws Exception {
        // A hairline inside an OCG whose usage turns print off drives finishing gear —
        // it must not surface as a print hairline.
        byte[] pdf =
                layerStrokePdf(
                        "Process",
                        true,
                        new org.apache.pdfbox.pdmodel.graphics.color.PDColor(
                                new float[] {0f, 0f, 0f, 1f},
                                org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK.INSTANCE));

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertFalse(
                hasFinding(report, "HAIRLINE"),
                "strokes on a print-off layer drive machines, not the press sheet");
    }

    @Test
    void testRgbInNonPrintingLayerIsNotFlagged() throws Exception {
        // RGB paint that never reaches the sheet is not a print risk.
        byte[] pdf =
                layerStrokePdf(
                        "Guides",
                        true,
                        new org.apache.pdfbox.pdmodel.graphics.color.PDColor(
                                new float[] {1f, 0f, 0f},
                                org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE));

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertFalse(
                hasFinding(report, "COLOR_RGB_USED"),
                "RGB on a print-off layer never reaches the press sheet");
    }

    @Test
    void testFinishingNamedLayerIsTechnical() throws Exception {
        // A layer named like a cut path is technical even when print stays ON.
        byte[] pdf =
                layerStrokePdf(
                        "dieline",
                        false,
                        new org.apache.pdfbox.pdmodel.graphics.color.PDColor(
                                new float[] {0f, 0f, 0f, 1f},
                                org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK.INSTANCE));

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertFalse(
                hasFinding(report, "HAIRLINE"),
                "a layer named 'dieline' drives the die, not the press sheet");
    }

    /** An OCG named {@code name} flagged off for the print destination, registered on the page. */
    private static org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup
            printOffOcg(PDDocument doc, PDPage page, String name) {
        org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup ocg =
                new org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup(name);
        org.apache.pdfbox.cos.COSDictionary usage = new org.apache.pdfbox.cos.COSDictionary();
        org.apache.pdfbox.cos.COSDictionary print = new org.apache.pdfbox.cos.COSDictionary();
        print.setItem(
                org.apache.pdfbox.cos.COSName.getPDFName("PrintState"),
                org.apache.pdfbox.cos.COSName.OFF);
        usage.setItem(org.apache.pdfbox.cos.COSName.getPDFName("Print"), print);
        ocg.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.getPDFName("Usage"), usage);
        page.setResources(new org.apache.pdfbox.pdmodel.PDResources());
        return ocg;
    }

    @Test
    void testTechnicalTransparencyIgnored() throws Exception {
        // Alpha on a print-off layer never reaches the sheet — nothing to flatten.
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup ocg =
                printOffOcg(doc, page, "Guides");
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginMarkedContent(org.apache.pdfbox.cos.COSName.OC, ocg);
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(0.5f);
            cs.setGraphicsStateParameters(gs);
            cs.setNonStrokingColor(0f, 0f, 0f);
            cs.addRect(50, 50, 100, 100);
            cs.fill();
            cs.endMarkedContent();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertFalse(
                hasFinding(report, "TRANSPARENCY"),
                "alpha on a print-off layer never reaches the press sheet");
        assertFalse(report.getFacts().isTransparencyUsed());
    }

    @Test
    void testTechnicalImageIgnored() throws Exception {
        // A raster guide on a print-off layer is not artwork — no low-res warning.
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup ocg =
                printOffOcg(doc, page, "Guides");
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        PDImageXObject xo = LosslessFactory.createFromImage(doc, img);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginMarkedContent(org.apache.pdfbox.cos.COSName.OC, ocg);
            cs.drawImage(xo, 50, 50, 300, 300);
            cs.endMarkedContent();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertFalse(hasFinding(report, "IMAGE_LOW_RES"));
        assertEquals(0, report.getFacts().getImageCount());
    }

    @Test
    void testMalformedPrintStateDoesNotAbort() throws Exception {
        // A PrintState outside the RenderState enum must not sink the page's whole pass.
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup ocg =
                new org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup(
                        "Weird");
        org.apache.pdfbox.cos.COSDictionary usage = new org.apache.pdfbox.cos.COSDictionary();
        org.apache.pdfbox.cos.COSDictionary print = new org.apache.pdfbox.cos.COSDictionary();
        print.setItem(
                org.apache.pdfbox.cos.COSName.getPDFName("PrintState"),
                org.apache.pdfbox.cos.COSName.getPDFName("Bogus"));
        usage.setItem(org.apache.pdfbox.cos.COSName.getPDFName("Print"), print);
        ocg.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.getPDFName("Usage"), usage);
        page.setResources(new org.apache.pdfbox.pdmodel.PDResources());
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginMarkedContent(org.apache.pdfbox.cos.COSName.OC, ocg);
            cs.setStrokingColor(0f, 0f, 0f, 1f);
            cs.setLineWidth(0.1f);
            cs.moveTo(50, 50);
            cs.lineTo(400, 50);
            cs.stroke();
            cs.endMarkedContent();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertFalse(
                hasFinding(report, "CONTENT_PARSE_ERROR"),
                "one malformed layer state must not abort the page's analysis");
        assertTrue(hasFinding(report, "HAIRLINE"), "content pass should survive a bogus state");
    }

    @Test
    void testOcmdTechnicalContext() throws Exception {
        // An OCMD grouping only print-off layers is technical too — ISO 19593-1 files wrap
        // finishing steps in membership dictionaries.
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList ocg =
                printOffOcg(doc, page, "Cut");
        org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentMembershipDictionary
                ocmd =
                        new org.apache.pdfbox.pdmodel.graphics.optionalcontent
                                .PDOptionalContentMembershipDictionary();
        ocmd.setOCGs(java.util.List.of(ocg));
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginMarkedContent(org.apache.pdfbox.cos.COSName.OC, ocmd);
            cs.setStrokingColor(0f, 0f, 0f, 1f);
            cs.setLineWidth(0.1f);
            cs.moveTo(50, 50);
            cs.lineTo(400, 50);
            cs.stroke();
            cs.endMarkedContent();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertFalse(
                hasFinding(report, "HAIRLINE"),
                "content under an OCMD of print-off layers drives machines, not the sheet");
    }

    private static byte[] toBytesAndLoad(ResponseEntity<Resource> response) throws IOException {
        Resource body = response.getBody();
        assertNotNull(body);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        body.getInputStream().transferTo(baos);
        return baos.toByteArray();
    }

    private static String pageText(PDDocument doc, int pageIndex) throws IOException {
        org.apache.pdfbox.text.PDFTextStripper stripper =
                new org.apache.pdfbox.text.PDFTextStripper();
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        return stripper.getText(doc);
    }

    @Test
    void testAnnotatedCopyStartsWithSummaryPage() throws Exception {
        byte[] pdf = redBleedPdf();
        ResponseEntity<Resource> response = controller.printPreflightAnnotated(request(pdf));
        try (PDDocument result = Loader.loadPDF(toBytesAndLoad(response))) {
            assertTrue(
                    result.getNumberOfPages() > 1, "report pages are prepended to the source page");
            String text = pageText(result, 0);
            assertTrue(text.contains("PRINT PREFLIGHT REPORT"), text);
            assertTrue(text.contains("test.pdf"), "file name belongs on the summary");
            assertTrue(text.contains("READY FOR PRINT") || text.contains("WARNING"), text);
            // The source is a single page: one report page in front, nothing duplicated behind.
            String last = pageText(result, result.getNumberOfPages() - 1);
            assertFalse(
                    last.contains("PRINT PREFLIGHT REPORT"),
                    "report pages must be moved, not copied, to the front");
        }
    }

    @Test
    void testStandaloneReportDocument() throws Exception {
        byte[] pdf = redBleedPdf();
        ResponseEntity<Resource> response = controller.printPreflightReport(request(pdf));
        assertEquals(200, response.getStatusCode().value());
        try (PDDocument result = Loader.loadPDF(toBytesAndLoad(response))) {
            String text = pageText(result, 0);
            assertTrue(text.contains("PRINT PREFLIGHT REPORT"), text);
            assertTrue(text.contains("Fonts"), "fact sections belong on the report");
            assertTrue(text.contains("Findings"), text);
        }
    }

    @Test
    void testWhiteOverprintFlagged() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(0.2f, 0.4f, 0.8f);
            cs.addRect(50, 50, 200, 200);
            cs.fill();
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingOverprintControl(true);
            cs.setGraphicsStateParameters(gs);
            cs.setNonStrokingColor(1f, 1f, 1f);
            cs.addRect(60, 60, 50, 50);
            cs.fill();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(
                hasFinding(report, "OVERPRINT_WHITE"),
                "white overprint prints nothing — a knockout trap");
        assertEquals(Severity.ERROR, finding(report, "OVERPRINT_WHITE").getSeverity());
    }

    @Test
    void testKnockoutBlackTextOverColour() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(0f, 0.6f, 0.8f);
            cs.addRect(50, 50, 300, 200);
            cs.fill();
            cs.setNonStrokingColor(0f, 0f, 0f, 1f);
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            cs.newLineAtOffset(60, 150);
            cs.showText("BLACK TEXT");
            cs.endText();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(
                hasFinding(report, "OVERPRINT_BLACK"),
                "black text knocking out colour needs overprint");
    }

    @Test
    void testBlackTextOnBarePaperIsNotKnockoutRisk() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(0f, 0f, 0f, 1f);
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            cs.newLineAtOffset(60, 150);
            cs.showText("BLACK TEXT");
            cs.endText();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertFalse(
                hasFinding(report, "OVERPRINT_BLACK"),
                "nothing underneath — knockout erases only blank paper");
    }

    @Test
    void testOverprintBlackTextAccepted() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(0f, 0.6f, 0.8f);
            cs.addRect(50, 50, 300, 200);
            cs.fill();
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingOverprintControl(true);
            cs.setGraphicsStateParameters(gs);
            cs.setNonStrokingColor(0f, 0f, 0f, 1f);
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            cs.newLineAtOffset(60, 150);
            cs.showText("BLACK TEXT");
            cs.endText();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertFalse(
                hasFinding(report, "OVERPRINT_BLACK"),
                "overprinting black text is the right setup");
    }

    @Test
    void testRichBlackTextFlagged() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(0.4f, 0.3f, 0.2f, 1f);
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 9);
            cs.newLineAtOffset(60, 150);
            cs.showText("RICH BLACK");
            cs.endText();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(
                hasFinding(report, "TEXT_RICH_BLACK"), "4C small text blurs at registration drift");
    }

    @Test
    void testSmallTextFlagged() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 3);
            cs.newLineAtOffset(60, 150);
            cs.showText("tiny");
            cs.endText();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(hasFinding(report, "TEXT_SMALL"));
        assertEquals(3f, report.getFacts().getMinFontSizeSeen(), 0.5f);
    }

    @Test
    void testSafetyMarginNearTrimEdge() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        page.setTrimBox(
                new PDRectangle(
                        10 * MM,
                        10 * MM,
                        page.getMediaBox().getWidth() - 20 * MM,
                        page.getMediaBox().getHeight() - 20 * MM));
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            // 1 mm inside the trim on the left, well under the 3 mm default margin.
            cs.setNonStrokingColor(0f, 0f, 0f, 1f);
            cs.addRect(11 * MM, 100, 5, 5);
            cs.fill();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(
                hasFinding(report, "SAFETY_MARGIN"),
                "content 1 mm from the trim edge risks the blade");
    }

    @Test
    void testContentWellInsideTrimIsSafe() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        page.setTrimBox(
                new PDRectangle(
                        10 * MM,
                        10 * MM,
                        page.getMediaBox().getWidth() - 20 * MM,
                        page.getMediaBox().getHeight() - 20 * MM));
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(0f, 0f, 0f, 1f);
            cs.addRect(
                    page.getMediaBox().getWidth() / 2, page.getMediaBox().getHeight() / 2, 20, 20);
            cs.fill();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertFalse(hasFinding(report, "SAFETY_MARGIN"));
    }

    @Test
    void testEmptyPage() throws Exception {
        PDDocument doc = new PDDocument();
        doc.addPage(new PDPage(PDRectangle.A4));
        PDPage painted = new PDPage(PDRectangle.A4);
        doc.addPage(painted);
        try (PDPageContentStream cs = new PDPageContentStream(doc, painted)) {
            cs.setNonStrokingColor(0f, 0f, 0f, 1f);
            cs.addRect(50, 50, 20, 20);
            cs.fill();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(hasFinding(report, "EMPTY_PAGE"));
        assertTrue(report.getFacts().getEmptyPages().contains(1));
    }

    @Test
    void testInkCoverageOverLimit() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(1f, 1f, 1f, 1f);
            cs.addRect(50, 50, 200, 200);
            cs.fill();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(hasFinding(report, "INK_COVERAGE_HIGH"), "400% TAC drowns the sheet");
        assertEquals(400f, report.getFacts().getMaxInkCoverageSeen(), 1f);
    }

    @Test
    void testInkCoverageUnderLimit() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(0.3f, 0.2f, 0.1f, 0.9f);
            cs.addRect(50, 50, 200, 200);
            cs.fill();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertFalse(hasFinding(report, "INK_COVERAGE_HIGH"));
    }

    @Test
    void testSpotAliasFlagged() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            for (String colorant : new String[] {"PANTONE 485 C", "pms 485cv"}) {
                org.apache.pdfbox.pdmodel.graphics.color.PDSeparation sep =
                        new org.apache.pdfbox.pdmodel.graphics.color.PDSeparation();
                sep.setColorantName(colorant);
                sep.setAlternateColorSpace(
                        org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK.INSTANCE);
                org.apache.pdfbox.cos.COSDictionary fn = new org.apache.pdfbox.cos.COSDictionary();
                fn.setInt(org.apache.pdfbox.cos.COSName.FUNCTION_TYPE, 2);
                org.apache.pdfbox.cos.COSArray c0 = new org.apache.pdfbox.cos.COSArray();
                org.apache.pdfbox.cos.COSArray c1 = new org.apache.pdfbox.cos.COSArray();
                for (int i = 0; i < 4; i++) {
                    c0.add(org.apache.pdfbox.cos.COSInteger.ZERO);
                    c1.add(org.apache.pdfbox.cos.COSInteger.ZERO);
                }
                c1.set(3, org.apache.pdfbox.cos.COSInteger.ONE);
                fn.setItem(org.apache.pdfbox.cos.COSName.C0, c0);
                fn.setItem(org.apache.pdfbox.cos.COSName.C1, c1);
                fn.setFloat(org.apache.pdfbox.cos.COSName.N, 1f);
                sep.setTintTransform(
                        new org.apache.pdfbox.pdmodel.common.function.PDFunctionType2(fn));
                cs.setNonStrokingColor(
                        new org.apache.pdfbox.pdmodel.graphics.color.PDColor(
                                new float[] {1f}, sep));
                cs.addRect(50, 50, 20, 20);
                cs.fill();
            }
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(
                hasFinding(report, "SPOT_ALIAS"),
                "same ink, two names — two plates where one would do");
    }

    @Test
    void testOutputIntentMissing() throws Exception {
        PrintPreflightReport report = controller.printPreflight(request(redBleedPdf())).getBody();
        assertNotNull(report);
        assertTrue(hasFinding(report, "OUTPUT_INTENT_MISSING"));
        assertNull(report.getFacts().getOutputIntent());
    }

    @Test
    void testEmbeddedFilesFlagged() throws Exception {
        PDDocument doc = new PDDocument();
        doc.addPage(new PDPage(PDRectangle.A4));
        org.apache.pdfbox.pdmodel.PDDocumentNameDictionary names =
                new org.apache.pdfbox.pdmodel.PDDocumentNameDictionary(doc.getDocumentCatalog());
        org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode tree =
                new org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode();
        org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification spec =
                new org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification();
        spec.setFile("attachment.txt");
        spec.setEmbeddedFile(
                new org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile(
                        doc, new java.io.ByteArrayInputStream("x".getBytes())));
        tree.setNames(java.util.Map.of("attachment.txt", spec));
        names.setEmbeddedFiles(tree);
        doc.getDocumentCatalog().setNames(names);
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(hasFinding(report, "EMBEDDED_FILES"));
        assertEquals(1, report.getFacts().getEmbeddedFileCount());
    }

    @Test
    void testAcroFormFlagged() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm form =
                new org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm(doc);
        doc.getDocumentCatalog().setAcroForm(form);
        org.apache.pdfbox.pdmodel.interactive.form.PDTextField field =
                new org.apache.pdfbox.pdmodel.interactive.form.PDTextField(form);
        field.setPartialName("field1");
        form.setFields(java.util.List.of(field));
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(hasFinding(report, "FORM_FIELDS"));
        assertTrue(report.getFacts().isHasAcroForm());
        assertEquals(1, report.getFacts().getFormFieldCount());
    }

    @Test
    void testUserUnitFlagged() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        page.getCOSObject().setFloat(org.apache.pdfbox.cos.COSName.getPDFName("UserUnit"), 2f);
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(hasFinding(report, "USER_UNIT"));
        assertTrue(report.getFacts().getNonStandardUserUnitPages().contains(1));
    }

    @Test
    void testRegistrationPaintReported() throws Exception {
        PrintPreflightReport report =
                controller.printPreflight(request(spotStrokePdf("All"))).getBody();
        assertNotNull(report);
        assertTrue(
                hasFinding(report, "REGISTRATION_PAINT"),
                "the All colorant paints every plate — worth listing");
        assertTrue(report.getFacts().getRegistrationPaintPages().contains(1));
    }

    @Test
    void testInvisibleTextReported() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setRenderingMode(org.apache.pdfbox.pdmodel.graphics.state.RenderingMode.NEITHER);
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
            cs.newLineAtOffset(60, 150);
            cs.showText("invisible");
            cs.endText();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(hasFinding(report, "INVISIBLE_TEXT"));
        assertTrue(report.getFacts().getInvisibleTextPages().contains(1));
    }

    @Test
    void testOversampledImage() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        BufferedImage img = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB);
        PDImageXObject xo = LosslessFactory.createFromImage(doc, img);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(xo, 50, 50, 50, 50);
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(hasFinding(report, "IMAGE_OVERSAMPLED"), "1440 effective dpi is dead weight");
    }

    @Test
    void testOneBitImageNeedsHigherDpi() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        BufferedImage img = new BufferedImage(50, 50, BufferedImage.TYPE_BYTE_BINARY);
        PDImageXObject xo = LosslessFactory.createFromImage(doc, img);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(xo, 50, 50, 50, 50);
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(
                hasFinding(report, "IMAGE_1BIT_LOW_RES"), "72 dpi line art stair-steps in print");
        assertFalse(
                hasFinding(report, "IMAGE_LOW_RES"),
                "1-bit images have their own threshold, not the contone one");
    }

    @Test
    void testObjectOutsidePage() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(0f, 0f, 0f, 1f);
            cs.addRect(-500, -500, 100, 100);
            cs.fill();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(hasFinding(report, "OBJECT_OUTSIDE_PAGE"));
    }

    @Test
    void testPrintOffLayerListed() throws Exception {
        byte[] pdf =
                layerStrokePdf(
                        "Guides",
                        true,
                        new org.apache.pdfbox.pdmodel.graphics.color.PDColor(
                                new float[] {0f, 0f, 0f, 1f},
                                org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK.INSTANCE));
        PrintPreflightReport report = controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        assertTrue(
                hasFinding(report, "LAYERS_PRINT_OFF"),
                "a print-off layer is a fact a print buyer wants to know");
        assertTrue(report.getFacts().getLayersDisabledForPrint().contains("Guides"));
    }

    @Test
    void testDisabledChecksSkipFindings() throws Exception {
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

        PrintPreflightRequest req = request(pdf);
        req.setDisabledChecks(
                java.util.List.of(
                        "HAIRLINE",
                        "TRIMBOX_MISSING",
                        "BLEED_MISSING",
                        "OUTPUT_INTENT_MISSING",
                        "EMPTY_PAGE",
                        "MIXED_PAGE_SIZES",
                        "CROPBOX_NE_MEDIA"));
        PrintPreflightReport report = controller.printPreflight(req).getBody();
        assertNotNull(report);
        assertFalse(hasFinding(report, "HAIRLINE"), "disabled checks stay silent");
        assertFalse(hasFinding(report, "TRIMBOX_MISSING"));
        assertFalse(hasFinding(report, "BLEED_MISSING"));
        assertFalse(hasFinding(report, "OUTPUT_INTENT_MISSING"));
    }

    @Test
    void testValidationRejectsBadNewParams() {
        PrintPreflightRequest req = request(new byte[] {1});
        req.setMinFontSizePt(-1);
        assertThrows(IllegalArgumentException.class, () -> controller.printPreflight(req));
        req.setMinFontSizePt(5);
        req.setSafetyMarginMm(-1);
        assertThrows(IllegalArgumentException.class, () -> controller.printPreflight(req));
        req.setSafetyMarginMm(3);
        req.setMaxInkCoveragePercent(-1);
        assertThrows(IllegalArgumentException.class, () -> controller.printPreflight(req));
        req.setMaxInkCoveragePercent(320);
        req.setMinImage1BitDpi(0);
        assertThrows(IllegalArgumentException.class, () -> controller.printPreflight(req));
    }
}
