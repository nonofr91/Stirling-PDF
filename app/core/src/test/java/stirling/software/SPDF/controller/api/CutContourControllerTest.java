package stirling.software.SPDF.controller.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import stirling.software.SPDF.model.api.general.CutContourPreview;
import stirling.software.SPDF.model.api.general.CutContourRequest;
import stirling.software.SPDF.model.api.security.PrintPreflightRequest;
import stirling.software.SPDF.service.preflight.PrintPreflightService;
import stirling.software.common.model.api.PDFFile;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.service.SubjectMattingService;
import stirling.software.common.util.TempFileManager;

@ExtendWith(MockitoExtension.class)
class CutContourControllerTest {

    private static final float MM = 72f / 25.4f;

    @Mock private CustomPDFDocumentFactory pdfDocumentFactory;
    @Mock private TempFileManager tempFileManager;
    @Mock private ObjectProvider<SubjectMattingService> mattingProvider;

    private CutContourController controller;

    @BeforeEach
    void setUp() throws IOException {
        lenient().when(mattingProvider.getIfAvailable()).thenReturn(null);
        controller = new CutContourController(pdfDocumentFactory, tempFileManager, mattingProvider);
        lenient()
                .when(tempFileManager.createTempFile(any()))
                .thenAnswer(inv -> java.io.File.createTempFile("cc-test", ".pdf"));
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

    private static BufferedImage transparentImage() {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(25, 25, 50, 50);
        g.dispose();
        return img;
    }

    /** A4 page with a 50%-opacity-free square PNG drawn at (200,300) size 200x200. */
    private static byte[] alphaArtworkPdf() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDImageXObject xo = LosslessFactory.createFromImage(doc, transparentImage());
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(xo, 200, 300, 200, 200);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.save(baos);
        doc.close();
        return baos.toByteArray();
    }

    private static byte[] uniformPagePdf() throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(1f, 1f, 1f);
            cs.addRect(0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            cs.fill();
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.save(baos);
        doc.close();
        return baos.toByteArray();
    }

    private static CutContourRequest request(byte[] pdf) {
        CutContourRequest req = new CutContourRequest();
        req.setFileInput(new MockMultipartFile("fileInput", "art.pdf", "application/pdf", pdf));
        req.setExtractionMode("ALPHA");
        req.setMinAreaMm2(0);
        return req;
    }

    private static String contentText(PDDocument doc) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (PDPage page : doc.getPages()) {
            java.util.Iterator<PDStream> streams = page.getContentStreams();
            while (streams.hasNext()) {
                PDStream s = streams.next();
                sb.append(
                        new String(s.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1));
            }
        }
        return sb.toString();
    }

    @Test
    void cutContour_writesCutContourLayerAndSpotPath() throws IOException {
        ResponseEntity<Resource> response = controller.cutContour(request(alphaArtworkPdf()));
        assertEquals(200, response.getStatusCode().value());
        try (PDDocument out = Loader.loadPDF(response.getBody().getInputStream().readAllBytes())) {
            PDOptionalContentGroup group =
                    out.getDocumentCatalog().getOCProperties().getGroup("CutContour");
            assertNotNull(group, "CutContour OCG missing");

            COSDictionary dict = group.getCOSObject();
            COSDictionary gts =
                    (COSDictionary) dict.getDictionaryObject(COSName.getPDFName("GTS_Metadata"));
            assertNotNull(gts);
            assertEquals(
                    "Structural", gts.getNameAsString(COSName.getPDFName("GTS_ProcStepsGroup")));
            assertEquals("Cutting", gts.getNameAsString(COSName.getPDFName("GTS_ProcStepsType")));
            COSDictionary usage = (COSDictionary) dict.getDictionaryObject(COSName.USAGE);
            assertNotNull(usage);
            COSDictionary print = (COSDictionary) usage.getDictionaryObject(COSName.PRINT);
            assertEquals("OFF", print.getNameAsString(COSName.PRINT_STATE));

            String content = contentText(out);
            assertTrue(content.contains(" BDC"), "marked content missing");
            assertTrue(content.contains(" scn") || content.contains(" SCN"), "spot stroke missing");

            boolean spotFound = false;
            for (COSName name : out.getPage(0).getResources().getColorSpaceNames()) {
                var cs = out.getPage(0).getResources().getColorSpace(name);
                if (cs instanceof org.apache.pdfbox.pdmodel.graphics.color.PDSeparation sep
                        && "CutContour".equals(sep.getColorantName())) {
                    spotFound = true;
                }
            }
            assertTrue(spotFound, "CutContour separation not registered in page resources");
        }
    }

    @Test
    void cutContour_parsesAsSpotSeparationInPreflight() throws IOException {
        ResponseEntity<Resource> response = controller.cutContour(request(alphaArtworkPdf()));
        try (PDDocument out = Loader.loadPDF(response.getBody().getInputStream().readAllBytes())) {
            PrintPreflightRequest pf = new PrintPreflightRequest();
            pf.setFileInput(
                    new MockMultipartFile("f", "out.pdf", "application/pdf", new byte[] {0}));
            var report =
                    new PrintPreflightService()
                            .analyze(out, "out.pdf", response.getBody().contentLength(), pf);
            // Finishing separations are kept out of print spot colors and reported
            // as technical separations (see PreflightGraphicsEngine.TECHNICAL_TOKENS).
            assertTrue(
                    report.getFacts().getTechnicalSeparations().contains("CutContour"),
                    "CutContour not listed as a technical separation");
            assertFalse(report.getFacts().getSpotColors().contains("CutContour"));
        }
    }

    @Test
    void cutContour_trimAndBleedUpdateBoxes() throws IOException {
        CutContourRequest req = request(alphaArtworkPdf());
        req.setTrimToContour(true);
        req.setBleedMm(3);
        ResponseEntity<Resource> response = controller.cutContour(req);
        try (PDDocument out = Loader.loadPDF(response.getBody().getInputStream().readAllBytes())) {
            PDPage page = out.getPage(0);
            PDRectangle trim = page.getTrimBox();
            // subject ≈ x∈[250,350], y∈[350,450] (50% of a 200pt image at 200,300)
            assertTrue(
                    trim.getLowerLeftX() > 230 && trim.getLowerLeftX() < 270,
                    "trim llx " + trim.getLowerLeftX());
            PDRectangle bleed = page.getBleedBox();
            assertTrue(Math.abs(bleed.getLowerLeftX() - (trim.getLowerLeftX() - 3 * MM)) < 1.5);
            assertTrue(Math.abs(bleed.getWidth() - (trim.getWidth() + 6 * MM)) < 2);
        }
    }

    @Test
    void cutContour_blankPageRejected() throws IOException {
        CutContourRequest req = request(uniformPagePdf());
        req.setExtractionMode("ALPHA");
        assertThrows(IllegalArgumentException.class, () -> controller.cutContour(req));
    }

    @Test
    void cutContour_explicitAiWithoutEngineRejected() throws IOException {
        CutContourRequest req = request(alphaArtworkPdf());
        req.setExtractionMode("AI");
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> controller.cutContour(req));
        assertTrue(ex.getMessage().contains("AI extraction unavailable"));
    }

    @Test
    void preview_returnsJsonPaths() throws IOException {
        ResponseEntity<CutContourPreview> response =
                controller.cutContourPreview(request(alphaArtworkPdf()));
        assertEquals(200, response.getStatusCode().value());
        CutContourPreview body = response.getBody();
        assertNotNull(body);
        assertEquals(1, body.getPages().size());
        CutContourPreview.Page p = body.getPages().get(0);
        assertEquals("ALPHA", p.getMode());
        assertFalse(p.getPaths().isEmpty());
        // every path is a closed ring of at least 3 flattened point pairs
        for (java.util.List<Float> ring : p.getPaths()) {
            assertTrue(ring.size() >= 6 && ring.size() % 2 == 0);
        }
    }
}
