package stirling.software.SPDF.controller.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import stirling.software.SPDF.config.EndpointConfiguration;
import stirling.software.SPDF.model.api.general.CropPdfForm;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.ProcessExecutor;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;

/**
 * Additional branch coverage for {@link CropController}: the Ghostscript routing decision (enabled
 * vs disabled when removeDataOutsideCrop is set), the Ghostscript execution path with the external
 * process mocked, and the large-image sampling-step branch of detectContentBounds. The gs binary is
 * never invoked.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CropController additional branch tests")
class CropControllerMoreTest {

    private static byte[] drainBody(ResponseEntity<Resource> response) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream in = response.getBody().getInputStream()) {
            in.transferTo(baos);
        }
        return baos.toByteArray();
    }

    @TempDir Path tempDir;
    @Mock private CustomPDFDocumentFactory pdfDocumentFactory;
    @Mock private TempFileManager tempFileManager;
    @Mock private EndpointConfiguration endpointConfiguration;
    @InjectMocks private CropController cropController;

    @BeforeEach
    void setUp() throws Exception {
        lenient()
                .when(tempFileManager.createManagedTempFile(anyString()))
                .thenAnswer(
                        inv -> {
                            File f =
                                    Files.createTempFile(
                                                    tempDir, "crop", inv.<String>getArgument(0))
                                            .toFile();
                            TempFile tf = mock(TempFile.class);
                            lenient().when(tf.getFile()).thenReturn(f);
                            lenient().when(tf.getPath()).thenReturn(f.toPath());
                            lenient().when(tf.getAbsolutePath()).thenReturn(f.getAbsolutePath());
                            return tf;
                        });
    }

    private MockMultipartFile pdf(int pages) throws IOException {
        Path p = tempDir.resolve("crop-src.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage(PDRectangle.LETTER));
            }
            doc.save(p.toFile());
        }
        return new MockMultipartFile(
                "fileInput", "src.pdf", MediaType.APPLICATION_PDF_VALUE, Files.readAllBytes(p));
    }

    private CropPdfForm form(MockMultipartFile file, boolean removeOutside) {
        CropPdfForm f = new CropPdfForm();
        f.setFileInput(file);
        f.setX(20f);
        f.setY(20f);
        f.setWidth(200f);
        f.setHeight(300f);
        f.setAutoCrop(false);
        f.setRemoveDataOutsideCrop(removeOutside);
        return f;
    }

    @Nested
    @DisplayName("Ghostscript routing")
    class GhostscriptRouting {

        @Test
        @DisplayName(
                "removeDataOutsideCrop with Ghostscript disabled falls back to the PDFBox path")
        void disabledFallsBackToPdfBox() throws Exception {
            MockMultipartFile file = pdf(1);
            CropPdfForm request = form(file, true);

            when(endpointConfiguration.isGroupEnabled("Ghostscript")).thenReturn(false);
            PDDocument source = mock(PDDocument.class);
            PDDocument out = mock(PDDocument.class);
            when(pdfDocumentFactory.load(request)).thenReturn(source);
            when(pdfDocumentFactory.createNewDocumentBasedOnOldDocument(source)).thenReturn(out);

            ResponseEntity<Resource> response = cropController.cropPdf(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            // PDFBox path constructs a new document; gs path would not.
            verify(pdfDocumentFactory).createNewDocumentBasedOnOldDocument(source);
            verify(source).close();
            verify(out).close();
        }

        @Test
        @DisplayName("removeDataOutsideCrop with Ghostscript enabled runs the gs command path")
        void enabledRunsGhostscript() throws Exception {
            MockMultipartFile file = pdf(2);
            CropPdfForm request = form(file, true);

            when(endpointConfiguration.isGroupEnabled("Ghostscript")).thenReturn(true);
            // Real document so setCropBox + save() succeed inside the gs branch.
            when(pdfDocumentFactory.load(request)).thenReturn(Loader.loadPDF(file.getBytes()));
            when(pdfDocumentFactory.createNewDocumentBasedOnOldDocument(any(PDDocument.class)))
                    .thenAnswer(invocation -> new PDDocument());

            ProcessExecutor executor = mock(ProcessExecutor.class);
            try (MockedStatic<ProcessExecutor> pe = Mockito.mockStatic(ProcessExecutor.class)) {
                pe.when(() -> ProcessExecutor.getInstance(ProcessExecutor.Processes.GHOSTSCRIPT))
                        .thenReturn(executor);
                // Identity Ghostscript: the mock copies its input to the -o
                // path so the merge step reads back a valid document.
                when(executor.runCommandWithOutputHandling(any()))
                        .thenAnswer(
                                invocation -> {
                                    List<String> command = invocation.getArgument(0);
                                    Path in = Path.of(command.get(command.size() - 1));
                                    Path out = Path.of(command.get(command.indexOf("-o") + 1));
                                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                                    return null;
                                });

                ResponseEntity<Resource> response = cropController.cropPdf(request);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(response.getBody()).isNotNull();
                verify(executor).runCommandWithOutputHandling(any());
            }
        }

        @Test
        @DisplayName("Ghostscript processes only selected pages and merges pristine originals")
        void ghostscriptMergesUnselectedPagesPristine() throws Exception {
            MockMultipartFile file = pdf(3);
            CropPdfForm request = form(file, true);
            request.setPageNumbers("2");

            when(endpointConfiguration.isGroupEnabled("Ghostscript")).thenReturn(true);
            when(pdfDocumentFactory.load(request)).thenReturn(Loader.loadPDF(file.getBytes()));
            when(pdfDocumentFactory.createNewDocumentBasedOnOldDocument(any(PDDocument.class)))
                    .thenAnswer(invocation -> new PDDocument());

            ProcessExecutor executor = mock(ProcessExecutor.class);
            try (MockedStatic<ProcessExecutor> pe = Mockito.mockStatic(ProcessExecutor.class)) {
                pe.when(() -> ProcessExecutor.getInstance(ProcessExecutor.Processes.GHOSTSCRIPT))
                        .thenReturn(executor);
                when(executor.runCommandWithOutputHandling(any()))
                        .thenAnswer(
                                invocation -> {
                                    List<String> command = invocation.getArgument(0);
                                    Path in = Path.of(command.get(command.size() - 1));
                                    Path out = Path.of(command.get(command.indexOf("-o") + 1));
                                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                                    return null;
                                });

                ResponseEntity<Resource> response = cropController.cropPdf(request);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(response.getBody()).isNotNull();
                verify(executor).runCommandWithOutputHandling(any());
            }
        }

        @Test
        @DisplayName("Ghostscript interruption is wrapped and surfaced")
        void interruptedIsWrapped() throws Exception {
            MockMultipartFile file = pdf(1);
            CropPdfForm request = form(file, true);

            when(endpointConfiguration.isGroupEnabled("Ghostscript")).thenReturn(true);
            when(pdfDocumentFactory.load(request)).thenReturn(Loader.loadPDF(file.getBytes()));

            ProcessExecutor executor = mock(ProcessExecutor.class);
            try (MockedStatic<ProcessExecutor> pe = Mockito.mockStatic(ProcessExecutor.class)) {
                pe.when(() -> ProcessExecutor.getInstance(ProcessExecutor.Processes.GHOSTSCRIPT))
                        .thenReturn(executor);
                when(executor.runCommandWithOutputHandling(any()))
                        .thenThrow(new InterruptedException("stop"));

                org.junit.jupiter.api.Assertions.assertThrows(
                        Exception.class, () -> cropController.cropPdf(request));
            }
        }
    }

    @Nested
    @DisplayName("detectContentBounds sampling step")
    class SamplingStep {

        private Method detect;

        @BeforeEach
        void setUp() throws Exception {
            detect =
                    CropController.class.getDeclaredMethod(
                            "detectContentBounds", BufferedImage.class);
            detect.setAccessible(true);
        }

        @Test
        @DisplayName("large images (>2000px) use a step of 2 and still locate content")
        void largeImageUsesStep2() throws Exception {
            // Width > 2000 triggers the step=2 sampling branch.
            BufferedImage image = new BufferedImage(2100, 50, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 2100; x++) {
                for (int y = 0; y < 50; y++) {
                    image.setRGB(x, y, 0xFFFFFF);
                }
            }
            // Dark block on even coordinates so the step-2 scan can see it.
            for (int x = 1000; x < 1040; x += 2) {
                for (int y = 10; y < 30; y += 2) {
                    image.setRGB(x, y, 0x000000);
                }
            }

            int[] bounds = (int[]) detect.invoke(null, image);
            assertThat(bounds).hasSize(4);
            assertThat(bounds[0]).isGreaterThanOrEqualTo(0);
            assertThat(bounds[2]).isGreaterThan(bounds[0]);
        }

        @Test
        @DisplayName("zero-size image returns degenerate bounds")
        void zeroSizeImage() throws Exception {
            BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, 0xFFFFFF);
            int[] bounds = (int[]) detect.invoke(null, image);
            assertThat(bounds).containsExactly(0, 0, 0, 0);
        }
    }

    @Nested
    @DisplayName("Non-zero MediaBox origin")
    class NonZeroOrigin {

        /** A red square at the MediaBox's lower-left corner, on a page that starts at (100,100). */
        private MockMultipartFile offsetOriginPdf() throws IOException {
            Path p = tempDir.resolve("offset.pdf");
            try (PDDocument doc = new PDDocument()) {
                PDPage page = new PDPage(new PDRectangle(100, 100, 400, 300));
                doc.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                    stream.setNonStrokingColor(new Color(200, 30, 30));
                    stream.addRect(100, 100, 50, 50);
                    stream.fill();
                }
                doc.save(p.toFile());
            }
            return new MockMultipartFile(
                    "fileInput",
                    "offset.pdf",
                    MediaType.APPLICATION_PDF_VALUE,
                    Files.readAllBytes(p));
        }

        @Test
        @DisplayName("manual crop keeps artwork at its absolute page coordinates")
        void manualCropKeepsAbsolutePlacement() throws Exception {
            MockMultipartFile file = offsetOriginPdf();
            CropPdfForm request = form(file, false);
            request.setX(100f);
            request.setY(100f);
            request.setWidth(400f);
            request.setHeight(300f);

            when(pdfDocumentFactory.load(request)).thenReturn(Loader.loadPDF(file.getBytes()));
            when(pdfDocumentFactory.createNewDocumentBasedOnOldDocument(any(PDDocument.class)))
                    .thenAnswer(inv -> new PDDocument());

            ResponseEntity<Resource> response = cropController.cropPdf(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            try (PDDocument result = Loader.loadPDF(drainBody(response))) {
                BufferedImage img =
                        new PDFRenderer(result).renderImageWithDPI(0, 72, ImageType.RGB);
                // The red square sits at the MediaBox lower-left: bottom-left pixels must be
                // red. A missing form-matrix compensation shifts it outside the clip.
                Color c = new Color(img.getRGB(10, img.getHeight() - 10));
                assertThat(c.getRed()).as("bottom-left pixel").isGreaterThan(120);
                assertThat(c.getGreen()).isLessThan(120);
                assertThat(c.getBlue()).isLessThan(120);
            }
        }

        @Test
        @DisplayName("crop to named CropBox with a non-zero origin keeps the selected area")
        void cropToBoxKeepsSelectedArea() throws Exception {
            Path p = tempDir.resolve("offset-crop.pdf");
            try (PDDocument doc = new PDDocument()) {
                PDPage page = new PDPage(new PDRectangle(100, 100, 400, 300));
                page.setCropBox(new PDRectangle(150, 150, 200, 200));
                doc.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                    stream.setNonStrokingColor(new Color(30, 30, 200));
                    stream.addRect(150, 150, 200, 200);
                    stream.fill();
                }
                doc.save(p.toFile());
            }
            MockMultipartFile file =
                    new MockMultipartFile(
                            "fileInput",
                            "offset-crop.pdf",
                            MediaType.APPLICATION_PDF_VALUE,
                            Files.readAllBytes(p));

            CropPdfForm request = form(file, false);
            request.setCropToBox(true);
            request.setPageBox("CROP_BOX");

            when(pdfDocumentFactory.load(request)).thenReturn(Loader.loadPDF(file.getBytes()));
            when(pdfDocumentFactory.createNewDocumentBasedOnOldDocument(any(PDDocument.class)))
                    .thenAnswer(inv -> new PDDocument());

            ResponseEntity<Resource> response = cropController.cropPdf(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            try (PDDocument result = Loader.loadPDF(drainBody(response))) {
                PDRectangle media = result.getPage(0).getMediaBox();
                assertThat(media.getLowerLeftX()).isEqualTo(150f);
                assertThat(media.getWidth()).isEqualTo(200f);
                BufferedImage img =
                        new PDFRenderer(result).renderImageWithDPI(0, 72, ImageType.RGB);
                Color c = new Color(img.getRGB(img.getWidth() / 2, img.getHeight() / 2));
                assertThat(c.getBlue()).as("center pixel").isGreaterThan(120);
                assertThat(c.getRed()).isLessThan(120);
            }
        }
    }
}
