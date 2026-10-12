package stirling.software.SPDF.controller.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import stirling.software.SPDF.model.api.general.EyeletMarksRequest;
import stirling.software.SPDF.service.prepress.PrepressArchiveService;
import stirling.software.common.model.api.PDFFile;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.TempFileManager;

@ExtendWith(MockitoExtension.class)
class EyeletMarksControllerTest {

    private static final float MM = 72f / 25.4f;

    @Mock private CustomPDFDocumentFactory pdfDocumentFactory;
    @Mock private TempFileManager tempFileManager;
    @Mock private PrepressArchiveService prepressArchive;

    private EyeletMarksController controller;

    @BeforeEach
    void setUp() throws IOException {
        controller =
                new EyeletMarksController(pdfDocumentFactory, tempFileManager, prepressArchive);
        lenient()
                .when(tempFileManager.createTempFile(any()))
                .thenAnswer(inv -> java.io.File.createTempFile("em-test", ".pdf"));
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

    private static byte[] blankPdf(int pages) throws IOException {
        PDDocument doc = new PDDocument();
        for (int i = 0; i < pages; i++) {
            doc.addPage(new PDPage(PDRectangle.A4));
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.save(baos);
        doc.close();
        return baos.toByteArray();
    }

    private static EyeletMarksRequest request(byte[] pdf) {
        EyeletMarksRequest req = new EyeletMarksRequest();
        req.setFileInput(new MockMultipartFile("fileInput", "banner.pdf", "application/pdf", pdf));
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

    private static int countCrossCenters(String content) {
        int moveTos = 0;
        for (String line : content.split("\n")) {
            if (line.trim().endsWith(" m")) {
                moveTos++;
            }
        }
        return moveTos / 2;
    }

    @Test
    void eyeletMarks_spacingModeDrawsKOnlyCrosses() throws IOException {
        ResponseEntity<Resource> response = controller.eyeletMarks(request(blankPdf(1)));
        assertEquals(200, response.getStatusCode().value());
        try (PDDocument out = Loader.loadPDF(response.getBody().getInputStream().readAllBytes())) {
            String content = contentText(out);
            assertTrue(content.contains("0 0 0 1 SC"), "pure-black stroking colour op missing");
            // 4 corners only: default 500 mm spacing exceeds the A4 sides' spans.
            assertEquals(4, countCrossCenters(content));
        }
    }

    @Test
    void eyeletMarks_countModeDrawsExactCount() throws IOException {
        EyeletMarksRequest req = request(blankPdf(1));
        req.setMode("COUNT");
        req.setCountTop(4);
        ResponseEntity<Resource> response = controller.eyeletMarks(req);
        try (PDDocument out = Loader.loadPDF(response.getBody().getInputStream().readAllBytes())) {
            assertEquals(4, countCrossCenters(contentText(out)));
        }
    }

    @Test
    void eyeletMarks_outsideOffsetGrowsMediaBox() throws IOException {
        EyeletMarksRequest req = request(blankPdf(1));
        req.setOffsetTopMm(-20);
        req.setMaxSpacingBottomMm(0);
        req.setMaxSpacingLeftMm(0);
        req.setMaxSpacingRightMm(0);
        ResponseEntity<Resource> response = controller.eyeletMarks(req);
        try (PDDocument out = Loader.loadPDF(response.getBody().getInputStream().readAllBytes())) {
            PDPage page = out.getPage(0);
            assertTrue(
                    page.getMediaBox().getUpperRightY() > PDRectangle.A4.getUpperRightY(),
                    "MediaBox should cover marks outside the trim");
        }
    }

    @Test
    void eyeletMarks_layerNameCreatesOcg() throws IOException {
        EyeletMarksRequest req = request(blankPdf(1));
        req.setLayerName("EyeletMarks");
        ResponseEntity<Resource> response = controller.eyeletMarks(req);
        try (PDDocument out = Loader.loadPDF(response.getBody().getInputStream().readAllBytes())) {
            PDOptionalContentGroup group =
                    out.getDocumentCatalog().getOCProperties().getGroup("EyeletMarks");
            assertNotNull(group, "EyeletMarks OCG missing");
            assertTrue(contentText(out).contains(" BDC"), "marked content missing");
        }
    }

    @Test
    void eyeletMarks_countOfOneRejected() throws IOException {
        EyeletMarksRequest req = request(blankPdf(1));
        req.setMode("COUNT");
        req.setCountTop(1);
        assertThrows(IllegalArgumentException.class, () -> controller.eyeletMarks(req));
    }

    @Test
    void eyeletMarks_allSidesDisabledRejected() throws IOException {
        EyeletMarksRequest req = request(blankPdf(1));
        req.setMaxSpacingTopMm(0);
        req.setMaxSpacingRightMm(0);
        req.setMaxSpacingBottomMm(0);
        req.setMaxSpacingLeftMm(0);
        assertThrows(IllegalArgumentException.class, () -> controller.eyeletMarks(req));
    }

    @Test
    void eyeletMarks_badModeRejected() throws IOException {
        EyeletMarksRequest req = request(blankPdf(1));
        req.setMode("BY_ANGLE");
        assertThrows(IllegalArgumentException.class, () -> controller.eyeletMarks(req));
    }

    @Test
    void eyeletMarks_setsChainHeadersWhenArchived() throws IOException {
        when(prepressArchive.recordVersion(any(), any(), any(Path.class), any(), any()))
                .thenReturn(Optional.of(new PrepressArchiveService.Handle("0123456789abcdef", 3)));

        ResponseEntity<Resource> response = controller.eyeletMarks(request(blankPdf(1)));

        assertEquals(
                "0123456789abcdef",
                response.getHeaders().getFirst(PrepressArchiveService.HEADER_CHAIN_ID));
        assertEquals("3", response.getHeaders().getFirst(PrepressArchiveService.HEADER_VERSION));
    }
}
