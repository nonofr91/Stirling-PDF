package stirling.software.SPDF.service.preflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.junit.jupiter.api.Test;

class PreflightA4ScalerTest {

    private static final PDRectangle A4_LANDSCAPE =
            new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());

    @Test
    void portraitPageFitsToA4Portrait() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(0, 0, 300, 600));
            doc.addPage(page);

            PreflightA4Scaler.fitToA4(doc, List.of());

            assertEquals(PDRectangle.A4.getWidth(), page.getMediaBox().getWidth(), 0.01);
            assertEquals(PDRectangle.A4.getHeight(), page.getMediaBox().getHeight(), 0.01);
            assertWrapped(page);
        }
    }

    @Test
    void largeFormatPageFitsToA4Landscape() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(0, 0, 2400, 1200));
            doc.addPage(page);

            PreflightA4Scaler.fitToA4(doc, List.of());

            assertEquals(A4_LANDSCAPE.getWidth(), page.getMediaBox().getWidth(), 0.01);
            assertEquals(A4_LANDSCAPE.getHeight(), page.getMediaBox().getHeight(), 0.01);
            assertWrapped(page);
        }
    }

    @Test
    void a4PageIsLeftUntouched() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.moveTo(0, 0);
            }

            PreflightA4Scaler.fitToA4(doc, List.of());

            assertEquals(PDRectangle.A4.getWidth(), page.getMediaBox().getWidth(), 0.01);
            assertNotWrapped(page);
        }
    }

    @Test
    void croppedPageFillsWholeA4() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(0, 0, 1000, 1000));
            doc.addPage(page);
            page.setCropBox(new PDRectangle(250, 250, 500, 500));

            PreflightA4Scaler.fitToA4(doc, List.of());

            // Viewers print the cropBox: fitting the mediaBox alone would leave a
            // 500-unit crop shrunk inside the A4 page — both boxes must be A4.
            assertEquals(PDRectangle.A4.getWidth(), page.getMediaBox().getWidth(), 0.01);
            assertEquals(PDRectangle.A4.getHeight(), page.getMediaBox().getHeight(), 0.01);
            assertEquals(PDRectangle.A4.getWidth(), page.getCropBox().getWidth(), 0.01);
            assertEquals(PDRectangle.A4.getHeight(), page.getCropBox().getHeight(), 0.01);
        }
    }

    @Test
    void userUnitPageFitsPhysicalA4() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(0, 0, 1000, 1000));
            doc.addPage(page);
            page.getCOSObject().setFloat(COSName.USER_UNIT, 2f);

            PreflightA4Scaler.fitToA4(doc, List.of());

            // The page displays 2000x2000 physical points; the new user-space box
            // must be A4 divided by the user unit so viewers show a true A4.
            assertEquals(PDRectangle.A4.getWidth() / 2, page.getMediaBox().getWidth(), 0.5);
            assertEquals(PDRectangle.A4.getHeight() / 2, page.getMediaBox().getHeight(), 0.5);
        }
    }

    @Test
    void squareAnnotationFollowsTheScaledArtwork() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(0, 0, 1000, 1000));
            doc.addPage(page);
            PDAnnotationSquare square = new PDAnnotationSquare();
            square.setRectangle(new PDRectangle(400, 400, 200, 200));
            page.getAnnotations().add(square);
            square.constructAppearances(doc);

            PreflightA4Scaler.fitToA4(doc, List.of(square));

            // 1000x1000 on A4 portrait: scale=0.59528, centered horizontally,
            // y offset = (841.89 - 595.28) / 2 = 123.3
            PDRectangle rect = page.getAnnotations().get(0).getRectangle();
            float scale = PDRectangle.A4.getWidth() / 1000f;
            float yOffset = (PDRectangle.A4.getHeight() - 1000 * scale) / 2;
            // constructAppearances inflates the rect by half the border on each side.
            assertEquals(400 * scale, rect.getLowerLeftX(), 2);
            assertEquals(400 * scale + yOffset, rect.getLowerLeftY(), 2);
            assertEquals(200 * scale, rect.getWidth(), 3);
        }
    }

    @Test
    void textNoteIsReparkedTopRightNotScaledAway() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(0, 0, 3000, 600));
            doc.addPage(page);
            PDAnnotationText note = new PDAnnotationText();
            note.setRectangle(new PDRectangle(2900, 550, 20, 20));
            page.getAnnotations().add(note);

            PreflightA4Scaler.fitToA4(doc, List.of(note));

            PDRectangle rect = page.getAnnotations().get(0).getRectangle();
            assertEquals(20, rect.getWidth(), 0.01);
            assertTrue(rect.getUpperRightX() > A4_LANDSCAPE.getWidth() - 30);
            assertTrue(rect.getUpperRightY() > A4_LANDSCAPE.getHeight() - 30);
        }
    }

    @Test
    void foreignTextNoteKeepsItsScaledPosition() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(0, 0, 1000, 1000));
            doc.addPage(page);
            PDAnnotationText note = new PDAnnotationText();
            note.setRectangle(new PDRectangle(100, 500, 20, 20));
            page.getAnnotations().add(note);

            PreflightA4Scaler.fitToA4(doc, List.of());

            // Not a preflight mark: the rect follows the scaled artwork instead of
            // being parked in the corner with the report notes.
            PDRectangle rect = page.getAnnotations().get(0).getRectangle();
            float scale = PDRectangle.A4.getWidth() / 1000f;
            float yOffset = (PDRectangle.A4.getHeight() - 1000 * scale) / 2;
            assertEquals(100 * scale, rect.getLowerLeftX(), 0.5);
            assertEquals(500 * scale + yOffset, rect.getLowerLeftY(), 0.5);
        }
    }

    @Test
    void rotatedPageLandsOnA4AfterRotation() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(0, 0, 400, 200));
            doc.addPage(page);
            page.setRotation(90);

            PreflightA4Scaler.fitToA4(doc, List.of());

            // /Rotate 90 displays 200x400 -> portrait A4. The pre-rotation user space
            // carries the swapped box so the viewer's rotation restores A4 portrait.
            PDRectangle media = page.getMediaBox();
            assertTrue(media.getWidth() > media.getHeight());
            assertEquals(A4_LANDSCAPE.getWidth(), media.getWidth(), 0.01);
            assertEquals(A4_LANDSCAPE.getHeight(), media.getHeight(), 0.01);
            assertWrapped(page);
        }
    }

    @Test
    void trimBoxIsRemappedIntoTheNewPage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(0, 0, 2000, 2000));
            doc.addPage(page);
            page.setTrimBox(new PDRectangle(200, 200, 1600, 1600));

            PreflightA4Scaler.fitToA4(doc, List.of());

            PDRectangle trim = page.getTrimBox();
            float scale = PDRectangle.A4.getWidth() / 2000f;
            float yOffset = (PDRectangle.A4.getHeight() - 2000 * scale) / 2;
            assertEquals(200 * scale, trim.getLowerLeftX(), 0.5);
            assertEquals(200 * scale + yOffset, trim.getLowerLeftY(), 0.5);
            assertEquals(1600 * scale, trim.getWidth(), 0.5);
        }
    }

    private void assertWrapped(PDPage page) throws IOException {
        String content = new String(page.getContents().readAllBytes());
        assertTrue(content.trim().startsWith("q"), "content should open with saveGraphicsState");
        assertTrue(content.trim().endsWith("Q"), "content should close with restoreGraphicsState");
    }

    private void assertNotWrapped(PDPage page) throws IOException {
        String content = new String(page.getContents().readAllBytes());
        assertTrue(!content.trim().startsWith("q"), "unwrapped content keeps its operators");
    }
}
