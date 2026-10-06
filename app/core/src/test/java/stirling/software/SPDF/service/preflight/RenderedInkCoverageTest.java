package stirling.software.SPDF.service.preflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import stirling.software.SPDF.service.preflight.RenderedInkCoverage.Result;

class RenderedInkCoverageTest {

    private final RenderedInkCoverage coverage = new RenderedInkCoverage(null, null);

    private static PDDocument doc(int pages) {
        PDDocument doc = new PDDocument();
        for (int i = 0; i < pages; i++) {
            doc.addPage(new PDPage(new PDRectangle(612, 792)));
        }
        return doc;
    }

    private static byte[] pam(int width, int height, byte[] pixels) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(
                ("P7\nWIDTH "
                                + width
                                + "\nHEIGHT "
                                + height
                                + "\nDEPTH 4\nMAXVAL 255\n"
                                + "TUPLTYPE CMYK\nENDHDR\n")
                        .getBytes(StandardCharsets.US_ASCII));
        out.write(pixels);
        return out.toByteArray();
    }

    private static byte[] frame(int width, int height, int c, int m, int y, int k) {
        byte[] px = new byte[width * height * 4];
        for (int i = 0; i < px.length; i += 4) {
            px[i] = (byte) c;
            px[i + 1] = (byte) m;
            px[i + 2] = (byte) y;
            px[i + 3] = (byte) k;
        }
        return px;
    }

    @Test
    void testSinglePagePeak() throws IOException {
        try (PDDocument doc = doc(1)) {
            Result r =
                    coverage.parse(
                            new ByteArrayInputStream(pam(2, 1, frame(2, 1, 255, 255, 255, 255))),
                            doc,
                            320);
            assertEquals(400f, r.peakPercent(), 0.01f);
            assertEquals(1, r.areasByPage().get(1).size());
            assertEquals("400%", r.areasByPage().get(1).get(0).getLabel());
        }
    }

    @Test
    void testBelowThresholdReportsNoAreas() throws IOException {
        try (PDDocument doc = doc(1)) {
            Result r =
                    coverage.parse(
                            new ByteArrayInputStream(pam(2, 1, frame(2, 1, 64, 64, 64, 64))),
                            doc,
                            320);
            assertEquals(100f, r.peakPercent(), 0.4f);
            assertTrue(r.areasByPage().isEmpty());
        }
    }

    @Test
    void testMultiPageConcatenated() throws IOException {
        ByteArrayOutputStream both = new ByteArrayOutputStream();
        both.write(pam(2, 1, frame(2, 1, 0, 0, 0, 0)));
        both.write(pam(2, 1, frame(2, 1, 255, 255, 0, 0)));
        try (PDDocument doc = doc(2)) {
            Result r = coverage.parse(new ByteArrayInputStream(both.toByteArray()), doc, 150);
            assertEquals(200f, r.peakPercent(), 0.01f);
            assertNull(r.areasByPage().get(1), "page 1 stays under the threshold");
            assertEquals(1, r.areasByPage().get(2).size());
        }
    }

    @Test
    void testHeaderWithCommentsAndReorderedFields() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(
                ("P7\n# ghostscript comment\nMAXVAL 255\nTUPLTYPE CMYK\n"
                                + "DEPTH 4\nHEIGHT 1\nWIDTH 2\nENDHDR\n")
                        .getBytes(StandardCharsets.US_ASCII));
        out.write(frame(2, 1, 255, 255, 255, 255));
        try (PDDocument doc = doc(1)) {
            Result r = coverage.parse(new ByteArrayInputStream(out.toByteArray()), doc, 320);
            assertEquals(400f, r.peakPercent(), 0.01f);
        }
    }

    @Test
    void testMissingEndhdrReturnsNull() throws IOException {
        byte[] noEnd =
                "P7\nWIDTH 2\nHEIGHT 1\nDEPTH 4\nMAXVAL 255\n".getBytes(StandardCharsets.US_ASCII);
        try (PDDocument doc = doc(1)) {
            assertNull(coverage.parse(new ByteArrayInputStream(noEnd), doc, 320));
        }
    }

    @Test
    void testTruncatedPixelsThrow() throws IOException {
        byte[] truncated = pam(4, 4, new byte[10]);
        try (PDDocument doc = doc(1)) {
            assertThrows(
                    IOException.class,
                    () -> coverage.parse(new ByteArrayInputStream(truncated), doc, 320));
        }
    }

    @Test
    void testNonCmykFrameRejected() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(
                "P7\nWIDTH 2\nHEIGHT 1\nDEPTH 3\nMAXVAL 255\nENDHDR\n"
                        .getBytes(StandardCharsets.US_ASCII));
        out.write(new byte[6]);
        try (PDDocument doc = doc(1)) {
            assertNull(coverage.parse(new ByteArrayInputStream(out.toByteArray()), doc, 320));
        }
    }

    @Test
    void testExtraFramesBeyondPageCountStop() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(pam(1, 1, frame(1, 1, 255, 255, 255, 255)));
        out.write(pam(1, 1, frame(1, 1, 255, 255, 255, 255)));
        try (PDDocument doc = doc(1)) {
            Result r = coverage.parse(new ByteArrayInputStream(out.toByteArray()), doc, 320);
            assertEquals(400f, r.peakPercent(), 0.01f);
            assertEquals(1, r.areasByPage().size());
        }
    }

    @Test
    void testEmptyStreamReturnsNull() throws IOException {
        try (PDDocument doc = doc(1)) {
            assertNull(coverage.parse(new ByteArrayInputStream(new byte[0]), doc, 320));
        }
    }
}
