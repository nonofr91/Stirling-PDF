package stirling.software.SPDF.service.preflight;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDSeparation;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import stirling.software.SPDF.config.EndpointConfiguration;
import stirling.software.SPDF.controller.api.security.PrintPreflightController;
import stirling.software.SPDF.model.api.security.PrintPreflightRequest;
import stirling.software.common.model.api.PDFFile;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.TempFileManager;

@ExtendWith(MockitoExtension.class)
class PreflightFixerTest {

    private static final float MM = 72f / 25.4f;

    @Mock private CustomPDFDocumentFactory pdfDocumentFactory;
    @Mock private TempFileManager tempFileManager;
    @Mock private EndpointConfiguration endpointConfiguration;
    private PrintPreflightController controller;

    @BeforeEach
    void setUp() throws IOException {
        controller =
                new PrintPreflightController(
                        new PrintPreflightService(),
                        pdfDocumentFactory,
                        tempFileManager,
                        new PreflightGhostscriptFixer(tempFileManager, endpointConfiguration),
                        new PreflightProfileService());
        lenient()
                .when(tempFileManager.createTempFile(any()))
                .thenAnswer(inv -> java.io.File.createTempFile("pf-fix", ".pdf"));
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
        lenient()
                .when(pdfDocumentFactory.load(any(java.nio.file.Path.class)))
                .thenAnswer(
                        inv -> Loader.loadPDF(((java.nio.file.Path) inv.getArgument(0)).toFile()));
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

    private static byte[] responseBytes(ResponseEntity<Resource> response) throws IOException {
        assertNotNull(response.getBody());
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        response.getBody().getInputStream().transferTo(baos);
        return baos.toByteArray();
    }

    private static byte[] basePdf() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(0.5f, 0.5f, 0.5f);
            cs.addRect(10, 10, 100, 100);
            cs.fill();
        }
        return toBytes(doc);
    }

    /** A page whose declared bleed is only 1mm around a 10mm-inset trim — below the 3mm default. */
    private static byte[] thinBleedPdf() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(0.8f, 0f, 0f);
            cs.addRect(0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            cs.fill();
        }
        float inset = 10 * MM;
        PDRectangle trim =
                new PDRectangle(
                        inset,
                        inset,
                        page.getMediaBox().getWidth() - 2 * inset,
                        page.getMediaBox().getHeight() - 2 * inset);
        page.setTrimBox(trim);
        page.setBleedBox(
                new PDRectangle(
                        trim.getLowerLeftX() - MM,
                        trim.getLowerLeftY() - MM,
                        trim.getWidth() + 2 * MM,
                        trim.getHeight() + 2 * MM));
        return toBytes(doc);
    }

    private static PDSeparation separation(String colorant) {
        PDSeparation sep = new PDSeparation();
        sep.setColorantName(colorant);
        sep.setAlternateColorSpace(org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK.INSTANCE);
        COSDictionary fn = new COSDictionary();
        fn.setInt(COSName.FUNCTION_TYPE, 2);
        org.apache.pdfbox.cos.COSArray c0 = new org.apache.pdfbox.cos.COSArray();
        org.apache.pdfbox.cos.COSArray c1 = new org.apache.pdfbox.cos.COSArray();
        for (int i = 0; i < 4; i++) {
            c0.add(org.apache.pdfbox.cos.COSInteger.ZERO);
            c1.add(org.apache.pdfbox.cos.COSInteger.ZERO);
        }
        c1.set(3, org.apache.pdfbox.cos.COSInteger.ONE);
        fn.setItem(COSName.C0, c0);
        fn.setItem(COSName.C1, c1);
        fn.setFloat(COSName.N, 1f);
        sep.setTintTransform(new org.apache.pdfbox.pdmodel.common.function.PDFunctionType2(fn));
        return sep;
    }

    /** Two spots that normalize to the same ink, painted in one stream — the alias check's case. */
    private static byte[] spotAliasPdf() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDSeparation sepA = separation("PANTONE 185 C");
        PDSeparation sepB = separation("PMS 185 CV");
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setStrokingColor(new PDColor(new float[] {1f}, sepA));
            cs.setLineWidth(2f);
            cs.moveTo(50, 50);
            cs.lineTo(400, 50);
            cs.stroke();
            cs.setStrokingColor(new PDColor(new float[] {1f}, sepB));
            cs.moveTo(50, 80);
            cs.lineTo(400, 80);
            cs.stroke();
        }
        return toBytes(doc);
    }

    private static byte[] formPdf() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDAcroForm form = new PDAcroForm(doc);
        PDResources resources = new PDResources();
        resources.put(
                COSName.getPDFName("Helv"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
        form.setDefaultResources(resources);
        doc.getDocumentCatalog().setAcroForm(form);
        PDTextField field = new PDTextField(form);
        field.setPartialName("customer");
        field.setDefaultAppearance("/Helv 12 Tf 0 g");
        field.setValue("Acme");
        form.getFields().add(field);
        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(new PDRectangle(50, 700, 200, 20));
        widget.setPage(page);
        widget.setPrinted(true);
        page.getAnnotations().add(widget);
        return toBytes(doc);
    }

    private static byte[] namesDictPdf(boolean javascript, boolean embedded) throws IOException {
        PDDocument doc = new PDDocument();
        doc.addPage(new PDPage(PDRectangle.A4));
        PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
        if (javascript) {
            names.getCOSObject().setItem(COSName.JAVA_SCRIPT, new COSDictionary());
        }
        if (embedded) {
            names.setEmbeddedFiles(new PDEmbeddedFilesNameTreeNode());
        }
        doc.getDocumentCatalog().setNames(names);
        return toBytes(doc);
    }

    private static byte[] oversizedImagePdf() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        BufferedImage img = new BufferedImage(1200, 1200, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.BLUE);
        g.fillRect(0, 0, 1200, 1200);
        g.dispose();
        PDImageXObject xo = LosslessFactory.createFromImage(doc, img);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            // 1200 px over 1 inch ≈ 1200 dpi — well above the 600 dpi cap.
            cs.drawImage(xo, 100, 100, 72, 72);
        }
        return toBytes(doc);
    }

    @Test
    void testFlattenFormRemovesFields() throws Exception {
        PrintPreflightRequest req = request(formPdf());
        req.setFixups(List.of("FLATTEN_FORM"));
        ResponseEntity<Resource> response = controller.printPreflightFix(req);
        try (PDDocument result = Loader.loadPDF(responseBytes(response))) {
            PDAcroForm form = result.getDocumentCatalog().getAcroForm();
            assertTrue(
                    form == null || form.getFields().isEmpty(),
                    "flattened document keeps no interactive fields");
            assertTrue(result.getPage(0).getAnnotations().isEmpty(), "widgets are consumed");
        }
        assertEquals(
                List.of("FLATTEN_FORM"),
                response.getHeaders().get("X-Preflight-Fixups"),
                "applied fixups are listed on the response");
    }

    @Test
    void testRemoveJavascriptAndAttachments() throws Exception {
        PrintPreflightRequest req = request(namesDictPdf(true, true));
        req.setFixups(List.of("REMOVE_JAVASCRIPT", "REMOVE_ATTACHMENTS"));
        try (PDDocument result = Loader.loadPDF(responseBytes(controller.printPreflightFix(req)))) {
            PDDocumentNameDictionary names = result.getDocumentCatalog().getNames();
            assertTrue(names == null || names.getJavaScript() == null);
            assertTrue(names == null || names.getEmbeddedFiles() == null);
        }
    }

    @Test
    void testSelectiveFixupsOnlyApplyRequested() throws Exception {
        PrintPreflightRequest req = request(namesDictPdf(true, true));
        req.setFixups(List.of("REMOVE_JAVASCRIPT"));
        try (PDDocument result = Loader.loadPDF(responseBytes(controller.printPreflightFix(req)))) {
            PDDocumentNameDictionary names = result.getDocumentCatalog().getNames();
            assertNotNull(names);
            assertNull(names.getJavaScript(), "requested fixup ran");
            assertNotNull(names.getEmbeddedFiles(), "unrequested fixup left untouched");
        }
    }

    @Test
    void testSetOutputIntentAttachesBundledProfile() throws Exception {
        PrintPreflightRequest req = request(basePdf());
        req.setFixups(List.of("SET_OUTPUT_INTENT"));
        try (PDDocument result = Loader.loadPDF(responseBytes(controller.printPreflightFix(req)))) {
            assertFalse(
                    result.getDocumentCatalog().getOutputIntents().isEmpty(),
                    "output intent is attached");
            assertEquals(
                    "sRGB2014",
                    result.getDocumentCatalog()
                            .getOutputIntents()
                            .get(0)
                            .getOutputConditionIdentifier());
        }
    }

    @Test
    void testMergeSpotAliasesUnifiesColorantNames() throws Exception {
        PrintPreflightRequest req = request(spotAliasPdf());
        req.setFixups(List.of("MERGE_SPOT_ALIASES"));
        try (PDDocument result = Loader.loadPDF(responseBytes(controller.printPreflightFix(req)))) {
            PDResources resources = result.getPage(0).getResources();
            java.util.Set<String> colorants = new java.util.LinkedHashSet<>();
            for (COSName name : resources.getColorSpaceNames()) {
                PDColorSpace cs = resources.getColorSpace(name);
                if (cs instanceof PDSeparation sep) {
                    colorants.add(sep.getColorantName());
                }
            }
            assertEquals(1, colorants.size(), "aliases collapse onto one plate: " + colorants);
            assertEquals("PANTONE 185 C", colorants.iterator().next());
        }
    }

    @Test
    void testExtendBleedGrowsBleedBox() throws Exception {
        PrintPreflightRequest req = request(thinBleedPdf());
        req.setFixups(List.of("EXTEND_BLEED"));
        try (PDDocument result = Loader.loadPDF(responseBytes(controller.printPreflightFix(req)))) {
            PDPage page = result.getPage(0);
            PDRectangle trim = page.getTrimBox();
            PDRectangle bleed = page.getBleedBox();
            float required = 3 * MM;
            assertTrue(
                    trim.getLowerLeftX() - bleed.getLowerLeftX() >= required - 0.5f,
                    "left bleed reaches the required width");
            assertTrue(bleed.getUpperRightX() - trim.getUpperRightX() >= required - 0.5f);
            assertTrue(bleed.getUpperRightY() - trim.getUpperRightY() >= required - 0.5f);
            assertTrue(
                    page.getMediaBox().getLowerLeftX() <= bleed.getLowerLeftX()
                            && page.getMediaBox().getLowerLeftY() <= bleed.getLowerLeftY()
                            && page.getMediaBox().getUpperRightX() >= bleed.getUpperRightX()
                            && page.getMediaBox().getUpperRightY() >= bleed.getUpperRightY(),
                    "media box covers the grown bleed so the area is reachable");
        }
    }

    @Test
    void testDownsampleImagesShrinksPixels() throws Exception {
        PrintPreflightRequest req = request(oversizedImagePdf());
        req.setFixups(List.of("DOWNSAMPLE_IMAGES"));
        try (PDDocument result = Loader.loadPDF(responseBytes(controller.printPreflightFix(req)))) {
            PDResources resources = result.getPage(0).getResources();
            PDImageXObject image = null;
            for (COSName name : resources.getXObjectNames()) {
                if (resources.getXObject(name) instanceof PDImageXObject pix) {
                    image = pix;
                }
            }
            assertNotNull(image, "the page still paints an image");
            assertTrue(
                    image.getWidth() <= 720,
                    "1200px downsampled toward 600dpi, got " + image.getWidth());
        }
    }

    @Test
    void testNormalizeUserUnitScalesBoxes() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        page.getCOSObject().setItem(COSName.USER_UNIT, new COSFloat(2f));
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.addRect(0, 0, 10, 10);
            cs.fill();
        }
        float mediaW = page.getMediaBox().getWidth();
        byte[] pdf = toBytes(doc);

        PrintPreflightRequest req = request(pdf);
        req.setFixups(List.of("NORMALIZE_USER_UNIT"));
        try (PDDocument result = Loader.loadPDF(responseBytes(controller.printPreflightFix(req)))) {
            PDPage fixed = result.getPage(0);
            assertNull(
                    fixed.getCOSObject().getItem(COSName.USER_UNIT),
                    "user unit is normalized away");
            assertEquals(mediaW * 2, fixed.getMediaBox().getWidth(), 0.5f);
        }
    }

    @Test
    void testRemoveEmptyPagesDropsBlankSheet() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage content = new PDPage(PDRectangle.A4);
        doc.addPage(content);
        doc.addPage(new PDPage(PDRectangle.A4));
        try (PDPageContentStream cs = new PDPageContentStream(doc, content)) {
            cs.addRect(10, 10, 50, 50);
            cs.fill();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightRequest req = request(pdf);
        req.setFixups(List.of("REMOVE_EMPTY_PAGES"));
        try (PDDocument result = Loader.loadPDF(responseBytes(controller.printPreflightFix(req)))) {
            assertEquals(1, result.getNumberOfPages(), "the blank page is removed");
        }
    }

    @Test
    void testDiscardCropBox() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        page.setCropBox(new PDRectangle(50, 50, 400, 700));
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.addRect(10, 10, 50, 50);
            cs.fill();
        }
        byte[] pdf = toBytes(doc);

        PrintPreflightRequest req = request(pdf);
        req.setFixups(List.of("DISCARD_CROPBOX"));
        try (PDDocument result = Loader.loadPDF(responseBytes(controller.printPreflightFix(req)))) {
            assertNull(
                    result.getPage(0).getCOSObject().getItem(COSName.CROP_BOX),
                    "declared crop box is discarded");
        }
    }

    @Test
    void testSetMissingBoxesDeclaresTrim() throws Exception {
        PrintPreflightRequest req = request(basePdf());
        req.setFixups(List.of("SET_MISSING_BOXES"));
        try (PDDocument result = Loader.loadPDF(responseBytes(controller.printPreflightFix(req)))) {
            assertNotNull(
                    result.getPage(0).getCOSObject().getItem(COSName.TRIM_BOX),
                    "trim box is declared even though it matched the crop box");
        }
    }

    @Test
    void testRemoveAnnotationsInTrimDropsPrintedNote() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        page.setTrimBox(new PDRectangle(20, 20, 550, 800));
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.addRect(10, 10, 50, 50);
            cs.fill();
        }
        PDAnnotationText inside = new PDAnnotationText();
        inside.setRectangle(new PDRectangle(30, 30, 40, 40));
        inside.setPrinted(true);
        inside.setContents("printer note inside trim");
        PDAnnotationText outside = new PDAnnotationText();
        outside.setRectangle(new PDRectangle(570, 810, 20, 20));
        outside.setPrinted(true);
        outside.setContents("slug note outside trim");
        page.getAnnotations().add(inside);
        page.getAnnotations().add(outside);
        byte[] pdf = toBytes(doc);

        PrintPreflightRequest req = request(pdf);
        req.setFixups(List.of("REMOVE_ANNOTATIONS_IN_TRIM"));
        try (PDDocument result = Loader.loadPDF(responseBytes(controller.printPreflightFix(req)))) {
            List<org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation> remaining =
                    result.getPage(0).getAnnotations();
            assertEquals(1, remaining.size(), "only the out-of-trim note survives");
            assertEquals("slug note outside trim", remaining.get(0).getContents());
        }
    }

    private List<String> findingCodes(byte[] pdf) throws IOException {
        stirling.software.SPDF.model.api.security.PrintPreflightReport report =
                controller.printPreflight(request(pdf)).getBody();
        assertNotNull(report);
        return report.getFindings().stream()
                .map(
                        stirling.software.SPDF.model.api.security.PrintPreflightReport.Finding
                                ::getCode)
                .toList();
    }

    private static byte[] invisibleTextPdf() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(font, 10);
            cs.newLineAtOffset(60, 150);
            cs.showText("visible");
            cs.setRenderingMode(org.apache.pdfbox.pdmodel.graphics.state.RenderingMode.NEITHER);
            cs.showText("hidden-ocr-layer");
            cs.endText();
        }
        return toBytes(doc);
    }

    /** Black text over a coloured underlay — the knockout-black check's case. */
    private static byte[] knockoutBlackTextPdf() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(0.2f, 0.5f, 0.8f, 0.1f);
            cs.addRect(40, 40, 400, 200);
            cs.fill();
            cs.beginText();
            cs.setFont(font, 10);
            cs.setNonStrokingColor(0f, 0f, 0f, 1f);
            cs.newLineAtOffset(60, 100);
            cs.showText("Black");
            cs.endText();
        }
        return toBytes(doc);
    }

    private static byte[] whiteOverprintPdf() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState gs =
                    new org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState();
            gs.setNonStrokingOverprintControl(true);
            cs.setGraphicsStateParameters(gs);
            cs.setNonStrokingColor(0f, 0f, 0f, 0f);
            cs.addRect(50, 50, 100, 100);
            cs.fill();
        }
        return toBytes(doc);
    }

    private static byte[] richBlackTextPdf() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(font, 10);
            cs.setNonStrokingColor(0.6f, 0.5f, 0.4f, 0.9f);
            cs.newLineAtOffset(60, 100);
            cs.showText("Rich black");
            cs.endText();
        }
        return toBytes(doc);
    }

    private static byte[] registrationSeparationPdf() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(new PDColor(new float[] {1f}, separation("All")));
            cs.addRect(50, 50, 100, 100);
            cs.fill();
        }
        return toBytes(doc);
    }

    private static byte[] printOffLayerPdf() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup ocg =
                new org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup(
                        "Finishing");
        COSDictionary usage = new COSDictionary();
        COSDictionary print = new COSDictionary();
        print.setItem(COSName.PRINT_STATE, COSName.OFF);
        usage.setItem(COSName.PRINT, print);
        ocg.getCOSObject().setItem(COSName.USAGE, usage);
        org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties ocProps =
                new org.apache.pdfbox.pdmodel.graphics.optionalcontent
                        .PDOptionalContentProperties();
        ocProps.addGroup(ocg);
        doc.getDocumentCatalog().setOCProperties(ocProps);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.addRect(10, 10, 50, 50);
            cs.fill();
        }
        return toBytes(doc);
    }

    @Test
    void testRemoveInvisibleTextDropsHiddenGlyphs() throws Exception {
        byte[] pdf = invisibleTextPdf();
        assertTrue(
                findingCodes(pdf).contains("INVISIBLE_TEXT"),
                "the OCR remnant is flagged before the fix");
        assertTrue(
                new org.apache.pdfbox.text.PDFTextStripper()
                        .getText(Loader.loadPDF(pdf))
                        .contains("hidden-ocr-layer"));

        PrintPreflightRequest req = request(pdf);
        req.setFixups(List.of("REMOVE_INVISIBLE_TEXT"));
        byte[] fixed = responseBytes(controller.printPreflightFix(req));
        try (PDDocument result = Loader.loadPDF(fixed)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(result);
            assertTrue(text.contains("visible"), "real text survives");
            assertFalse(text.contains("hidden-ocr-layer"), "invisible text is gone");
        }
        assertFalse(
                findingCodes(fixed).contains("INVISIBLE_TEXT"),
                "a re-preflight no longer reports invisible text");
    }

    @Test
    void testOverprintBlackTextWrapsShowOp() throws Exception {
        byte[] pdf = knockoutBlackTextPdf();
        assertTrue(
                findingCodes(pdf).contains("OVERPRINT_BLACK"),
                "knockout black text is flagged before the fix");

        PrintPreflightRequest req = request(pdf);
        req.setFixups(List.of("OVERPRINT_BLACK_TEXT"));
        byte[] fixed = responseBytes(controller.printPreflightFix(req));
        try (PDDocument result = Loader.loadPDF(fixed)) {
            PDResources resources = result.getPage(0).getResources();
            boolean hasOverprintState = false;
            for (COSName name : resources.getExtGStateNames()) {
                org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState gs =
                        resources.getExtGState(name);
                if (gs != null && gs.getNonStrokingOverprintControl()) {
                    hasOverprintState = true;
                }
            }
            assertTrue(hasOverprintState, "the wrap injected an overprinting ExtGState");
        }
        assertFalse(
                findingCodes(fixed).contains("OVERPRINT_BLACK"),
                "a re-preflight no longer reports knockout black text");
    }

    @Test
    void testKnockoutWhiteClearsOverprint() throws Exception {
        byte[] pdf = whiteOverprintPdf();
        assertTrue(
                findingCodes(pdf).contains("OVERPRINT_WHITE"),
                "white overprinting is flagged before the fix");

        PrintPreflightRequest req = request(pdf);
        req.setFixups(List.of("KNOCKOUT_WHITE"));
        byte[] fixed = responseBytes(controller.printPreflightFix(req));
        assertFalse(
                findingCodes(fixed).contains("OVERPRINT_WHITE"),
                "a re-preflight no longer reports the white overprint");
    }

    @Test
    void testPureBlackTextRewritesFill() throws Exception {
        byte[] pdf = richBlackTextPdf();
        assertTrue(
                findingCodes(pdf).contains("TEXT_RICH_BLACK"),
                "rich black text is flagged before the fix");

        PrintPreflightRequest req = request(pdf);
        req.setFixups(List.of("PURE_BLACK_TEXT"));
        byte[] fixed = responseBytes(controller.printPreflightFix(req));
        assertFalse(
                findingCodes(fixed).contains("TEXT_RICH_BLACK"),
                "a re-preflight no longer reports rich black text");
    }

    @Test
    void testRegistrationToBlackRewritesPaint() throws Exception {
        byte[] pdf = registrationSeparationPdf();
        assertTrue(
                findingCodes(pdf).contains("REGISTRATION_PAINT"),
                "painting the All colorant is flagged before the fix");

        PrintPreflightRequest req = request(pdf);
        req.setFixups(List.of("REGISTRATION_TO_BLACK"));
        byte[] fixed = responseBytes(controller.printPreflightFix(req));
        assertFalse(
                findingCodes(fixed).contains("REGISTRATION_PAINT"),
                "a re-preflight no longer reports registration paint");
    }

    @Test
    void testEnableLayerPrintingFlipsPrintState() throws Exception {
        byte[] pdf = printOffLayerPdf();
        assertTrue(
                findingCodes(pdf).contains("LAYERS_PRINT_OFF"),
                "the print-off layer is flagged before the fix");

        PrintPreflightRequest req = request(pdf);
        req.setFixups(List.of("ENABLE_LAYER_PRINTING"));
        try (PDDocument result = Loader.loadPDF(responseBytes(controller.printPreflightFix(req)))) {
            org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup group =
                    result.getDocumentCatalog()
                            .getOCProperties()
                            .getOptionalContentGroups()
                            .iterator()
                            .next();
            assertEquals(
                    org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup
                            .RenderState.ON,
                    group.getRenderState(org.apache.pdfbox.rendering.RenderDestination.PRINT),
                    "the layer prints again");
        }
    }

    @Test
    void testNoApplicableFixupReturnsUnchangedDocument() throws Exception {
        PrintPreflightRequest req = request(basePdf());
        req.setFixups(List.of("REMOVE_JAVASCRIPT", "FLATTEN_FORM"));
        ResponseEntity<Resource> response = controller.printPreflightFix(req);
        try (PDDocument result = Loader.loadPDF(responseBytes(response))) {
            assertEquals(1, result.getNumberOfPages());
        }
        assertEquals(
                List.of(""),
                response.getHeaders().get("X-Preflight-Fixups"),
                "header is present even when nothing applied");
    }

    @Test
    void testSpotToCmykConvertsSeparationTint() throws Exception {
        byte[] pdf = spotFillPdf();
        assertTrue(
                findingCodes(pdf).contains("COLOR_SPOT"),
                "the spot colorant is flagged before the fix");

        PrintPreflightRequest req = request(pdf);
        req.setFixups(List.of("SPOT_TO_CMYK"));
        byte[] fixed = responseBytes(controller.printPreflightFix(req));
        assertFalse(
                findingCodes(fixed).contains("COLOR_SPOT"),
                "a re-preflight no longer reports spot ink");
    }

    /** One cs, two scn — the pending spot conversion must survive the first paint. */
    @Test
    void testSpotToCmykAppliesToEveryPaintAfterTheSpaceIsSet() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDResources res = new PDResources();
        COSDictionary csDict = new COSDictionary();
        csDict.setItem(COSName.getPDFName("Spot"), separation("PANTONE 300 C").getCOSObject());
        res.getCOSObject().setItem(COSName.getPDFName("ColorSpace"), csDict);
        page.setResources(res);
        page.setContents(
                new org.apache.pdfbox.pdmodel.common.PDStream(
                        doc,
                        new java.io.ByteArrayInputStream(
                                "/Spot cs 0.5 scn 10 10 50 50 re f 0.9 scn 70 70 50 50 re f"
                                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
        byte[] pdf = toBytes(doc);
        assertTrue(findingCodes(pdf).contains("COLOR_SPOT"));

        PrintPreflightRequest req = request(pdf);
        req.setFixups(List.of("SPOT_TO_CMYK"));
        try (PDDocument result = Loader.loadPDF(responseBytes(controller.printPreflightFix(req)))) {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            result.getPage(0).getContentStreams().next().createInputStream().transferTo(baos);
            String stream = baos.toString(java.nio.charset.StandardCharsets.US_ASCII);
            assertFalse(stream.contains("scn"), "every spot paint was rewritten");
            assertEquals(2, stream.split(" k").length - 1, "both paints became DeviceCMYK");
        }
    }

    @Test
    void testReduceInkCoverageCapsTotalInk() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(0.9f, 0.9f, 0.9f, 0.9f);
            cs.addRect(50, 50, 100, 100);
            cs.fill();
        }
        byte[] pdf = toBytes(doc);
        assertTrue(
                findingCodes(pdf).contains("INK_COVERAGE_HIGH"),
                "a 360% fill is flagged before the fix");

        PrintPreflightRequest req = request(pdf);
        req.setMaxInkCoveragePercent(300);
        req.setFixups(List.of("REDUCE_INK_COVERAGE"));
        byte[] fixed = responseBytes(controller.printPreflightFix(req));
        assertFalse(
                findingCodes(fixed).contains("INK_COVERAGE_HIGH"),
                "the GCR remap brings the fill under the limit");
    }

    @Test
    void testGhostscriptWantedGatesImplicitFixupsOnFindings() {
        stirling.software.SPDF.model.api.security.PrintPreflightReport report =
                new stirling.software.SPDF.model.api.security.PrintPreflightReport();
        PrintPreflightRequest req = new PrintPreflightRequest();
        assertTrue(
                PreflightFixer.ghostscriptWanted(req, report).isEmpty(),
                "nothing to fix: a clean report requests no Ghostscript pass");

        stirling.software.SPDF.model.api.security.PrintPreflightReport.Finding finding =
                new stirling.software.SPDF.model.api.security.PrintPreflightReport.Finding();
        finding.setCode("COLOR_RGB_USED");
        report.setFindings(List.of(finding));
        assertEquals(
                java.util.Set.of(PreflightFixer.Code.RGB_TO_CMYK),
                PreflightFixer.ghostscriptWanted(req, report),
                "only the finding-backed fixup is applicable");

        req.setFixups(List.of("FLATTEN_TRANSPARENCY"));
        assertEquals(
                java.util.Set.of(PreflightFixer.Code.FLATTEN_TRANSPARENCY),
                PreflightFixer.ghostscriptWanted(req, report),
                "an explicit request runs regardless of the findings");
    }

    @Test
    void testRgbToCmykViaGhostscript() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(ghostscriptOnPath(), "gs binary not on PATH");
        org.mockito.Mockito.when(endpointConfiguration.isGroupEnabled("Ghostscript"))
                .thenReturn(true);
        byte[] pdf = basePdf(); // a DeviceRGB gray fill
        assertTrue(findingCodes(pdf).contains("COLOR_RGB_USED"));

        PrintPreflightRequest req = request(pdf);
        req.setFixups(List.of("RGB_TO_CMYK"));
        byte[] fixed = responseBytes(controller.printPreflightFix(req));
        assertFalse(
                findingCodes(fixed).contains("COLOR_RGB_USED"),
                "Ghostscript converted the RGB fill to CMYK");
    }

    @Test
    void testTextToOutlinesViaGhostscript() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(ghostscriptOnPath(), "gs binary not on PATH");
        org.mockito.Mockito.when(endpointConfiguration.isGroupEnabled("Ghostscript"))
                .thenReturn(true);
        byte[] pdf = invisibleTextPdf(); // Standard-14 Helvetica is never embedded
        assertTrue(findingCodes(pdf).contains("FONT_NOT_EMBEDDED"));

        PrintPreflightRequest req = request(pdf);
        req.setFixups(List.of("TEXT_TO_OUTLINES"));
        byte[] fixed = responseBytes(controller.printPreflightFix(req));
        assertFalse(
                findingCodes(fixed).contains("FONT_NOT_EMBEDDED"),
                "outlined text carries no font to embed");
    }

    private static boolean ghostscriptOnPath() {
        try {
            return new ProcessBuilder("gs", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] spotFillPdf() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(new PDColor(new float[] {0.7f}, separation("PANTONE 300 C")));
            cs.addRect(50, 50, 100, 100);
            cs.fill();
        }
        return toBytes(doc);
    }

    @Test
    void testFixPreviewReportsAppliedAndResolved() throws Exception {
        PrintPreflightRequest req = request(namesDictPdf(true, true));
        req.setFixups(List.of("REMOVE_JAVASCRIPT", "REMOVE_ATTACHMENTS"));

        stirling.software.SPDF.model.api.security.PrintPreflightFixAudit audit =
                controller.printPreflightFixPreview(req).getBody();
        assertNotNull(audit);
        assertEquals(List.of("REMOVE_JAVASCRIPT", "REMOVE_ATTACHMENTS"), audit.getAppliedFixups());
        assertTrue(
                codes(audit.getResolvedFindings())
                        .containsAll(List.of("JAVASCRIPT", "EMBEDDED_FILES")),
                "fixups that ran resolve their findings");
        assertTrue(
                audit.getCountsAfter().getWarnings() < audit.getCountsBefore().getWarnings(),
                "the after report carries fewer warnings");
        assertFalse(
                codes(audit.getRemainingFindings()).contains("JAVASCRIPT"),
                "resolved findings do not linger in the remaining set");
    }

    @Test
    void testFixPreviewWithNothingApplicable() throws Exception {
        PrintPreflightRequest req = request(basePdf());
        req.setFixups(List.of("REMOVE_JAVASCRIPT"));

        stirling.software.SPDF.model.api.security.PrintPreflightFixAudit audit =
                controller.printPreflightFixPreview(req).getBody();
        assertNotNull(audit);
        assertTrue(audit.getAppliedFixups().isEmpty(), "no fixup had work to do");
        assertTrue(audit.getResolvedFindings().isEmpty());
        assertTrue(audit.getIntroducedFindings().isEmpty());
        assertEquals(audit.getCountsBefore().getErrors(), audit.getCountsAfter().getErrors());
    }

    @Test
    void testFixPreviewRunsGhostscriptFixups() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(ghostscriptOnPath(), "gs binary not on PATH");
        org.mockito.Mockito.when(endpointConfiguration.isGroupEnabled("Ghostscript"))
                .thenReturn(true);
        PrintPreflightRequest req = request(basePdf());
        req.setFixups(List.of("RGB_TO_CMYK"));

        stirling.software.SPDF.model.api.security.PrintPreflightFixAudit audit =
                controller.printPreflightFixPreview(req).getBody();
        assertNotNull(audit);
        assertTrue(
                audit.getAppliedFixups().contains("RGB_TO_CMYK"),
                "Ghostscript codes join the applied list");
        assertTrue(
                codes(audit.getResolvedFindings()).contains("COLOR_RGB_USED"),
                "the re-analysed Ghostscript output no longer reports RGB");
    }

    @Test
    void testFixAuditDiffsByFindingCode() {
        stirling.software.SPDF.model.api.security.PrintPreflightReport before =
                new stirling.software.SPDF.model.api.security.PrintPreflightReport();
        before.addFinding(finding("X"));
        before.addFinding(finding("Y"));
        stirling.software.SPDF.model.api.security.PrintPreflightReport after =
                new stirling.software.SPDF.model.api.security.PrintPreflightReport();
        after.addFinding(finding("Y"));
        after.addFinding(finding("Z"));

        stirling.software.SPDF.model.api.security.PrintPreflightFixAudit audit =
                stirling.software.SPDF.model.api.security.PrintPreflightFixAudit.of(
                        before, after, List.of("SOME_FIXUP"));
        assertEquals(List.of("X"), codes(audit.getResolvedFindings()));
        assertEquals(List.of("Y"), codes(audit.getRemainingFindings()));
        assertEquals(List.of("Z"), codes(audit.getIntroducedFindings()));
    }

    private static stirling.software.SPDF.model.api.security.PrintPreflightReport.Finding finding(
            String code) {
        return new stirling.software.SPDF.model.api.security.PrintPreflightReport.Finding(
                stirling.software.SPDF.model.api.security.PrintPreflightReport.Severity.WARNING,
                stirling.software.SPDF.model.api.security.PrintPreflightReport.Category.DOCUMENT,
                code,
                code,
                List.of());
    }

    private static List<String> codes(
            List<stirling.software.SPDF.model.api.security.PrintPreflightReport.Finding> findings) {
        return findings.stream()
                .map(
                        stirling.software.SPDF.model.api.security.PrintPreflightReport.Finding
                                ::getCode)
                .toList();
    }

    /** A rect painted fully left of the page — dead content the crop edge never shows. */
    private static byte[] outsidePagePdf() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        writeContent(doc, page, "-500 -500 100 100 re\nf\n");
        return toBytes(doc);
    }

    /** Same off-page rect, already wrapped in a CropBox clip — nothing outside can paint. */
    private static byte[] clippedOutsidePagePdf() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDRectangle crop = page.getCropBox();
        String wrapped =
                String.format(
                        java.util.Locale.ROOT,
                        "q%n%.4f %.4f %.4f %.4f re%nW%nn%n-500 -500 100 100 re%nf%nQ%n",
                        crop.getLowerLeftX(),
                        crop.getLowerLeftY(),
                        crop.getWidth(),
                        crop.getHeight());
        writeContent(doc, page, wrapped);
        return toBytes(doc);
    }

    private static void writeContent(PDDocument doc, PDPage page, String tokens)
            throws IOException {
        org.apache.pdfbox.pdmodel.common.PDStream stream =
                new org.apache.pdfbox.pdmodel.common.PDStream(doc);
        try (java.io.OutputStream out = stream.createOutputStream()) {
            out.write(tokens.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        page.setContents(stream);
    }

    @Test
    void testClipToCropBoxSuppressesOutsidePageFinding() throws Exception {
        assertTrue(
                findingCodes(outsidePagePdf()).contains("OBJECT_OUTSIDE_PAGE"),
                "baseline: off-page paint is flagged");

        PrintPreflightRequest req = request(outsidePagePdf());
        req.setFixups(List.of("CLIP_TO_CROPBOX"));
        ResponseEntity<Resource> response = controller.printPreflightFix(req);
        assertEquals(
                List.of("CLIP_TO_CROPBOX"),
                response.getHeaders().get("X-Preflight-Fixups"),
                "the clip fixup reports itself applied");

        assertFalse(
                findingCodes(responseBytes(response)).contains("OBJECT_OUTSIDE_PAGE"),
                "clipped content can no longer paint outside the crop");
    }

    @Test
    void testPreClippedContentDoesNotFlag() throws Exception {
        assertFalse(
                findingCodes(clippedOutsidePagePdf()).contains("OBJECT_OUTSIDE_PAGE"),
                "an explicit CropBox clip suppresses the finding without any fixup");
    }

    @Test
    void testClipToCropBoxSkippedOnCleanPage() throws Exception {
        PrintPreflightRequest req = request(basePdf());
        req.setFixups(List.of("CLIP_TO_CROPBOX"));
        ResponseEntity<Resource> response = controller.printPreflightFix(req);
        assertEquals(
                "",
                response.getHeaders().getFirst("X-Preflight-Fixups"),
                "nothing outside the crop — the clip is not added");
    }
}
