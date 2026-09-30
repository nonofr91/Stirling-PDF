package stirling.software.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PageBoxUtilsTest {

    private static void assertRectEquals(PDRectangle expected, PDRectangle actual) {
        assertEquals(expected.getLowerLeftX(), actual.getLowerLeftX(), 0.01);
        assertEquals(expected.getLowerLeftY(), actual.getLowerLeftY(), 0.01);
        assertEquals(expected.getUpperRightX(), actual.getUpperRightX(), 0.01);
        assertEquals(expected.getUpperRightY(), actual.getUpperRightY(), 0.01);
    }

    private PDPage pageWithTrimBox() {
        PDPage page = new PDPage(PDRectangle.A4);
        page.setTrimBox(new PDRectangle(20, 20, 400, 600));
        return page;
    }

    @Test
    @DisplayName("Null or blank pageBox resolves to MediaBox")
    void blankResolvesMediaBox() {
        PDPage page = pageWithTrimBox();
        assertRectEquals(PDRectangle.A4, PageBoxUtils.resolvePageBox(page, null));
        assertRectEquals(PDRectangle.A4, PageBoxUtils.resolvePageBox(page, "  "));
    }

    @Test
    @DisplayName("Named box resolves to its own rectangle")
    void namedBoxResolves() {
        PDPage page = pageWithTrimBox();
        PDRectangle trim = PageBoxUtils.resolvePageBox(page, "TRIM_BOX");
        assertEquals(20, trim.getLowerLeftX(), 0.01);
        assertEquals(400, trim.getWidth(), 0.01);
        assertEquals(600, trim.getHeight(), 0.01);
    }

    @ParameterizedTest
    @ValueSource(strings = {"trim_box", "Trim_Box"})
    @DisplayName("Resolution is case-insensitive")
    void caseInsensitive(String value) {
        PDPage page = pageWithTrimBox();
        assertEquals(400, PageBoxUtils.resolvePageBox(page, value).getWidth(), 0.01);
    }

    @Test
    @DisplayName("Missing named box falls back to MediaBox")
    void missingBoxFallsBackToMediaBox() {
        PDPage page = new PDPage(PDRectangle.A4);
        assertRectEquals(PDRectangle.A4, PageBoxUtils.resolvePageBox(page, PageBoxUtils.BLEED_BOX));
        assertRectEquals(PDRectangle.A4, PageBoxUtils.resolvePageBox(page, PageBoxUtils.ART_BOX));
    }

    @Test
    @DisplayName("Missing named box falls back to MediaBox even when a CropBox is set")
    void missingBoxFallsBackToMediaBoxNotCropBox() {
        // The PDPage getters fall back to CropBox when TrimBox/BleedBox/ArtBox are absent,
        // so resolution must check the page dictionary, not the getter result.
        PDPage page = new PDPage(PDRectangle.A4);
        page.setCropBox(new PDRectangle(10, 10, 300, 400));
        assertRectEquals(PDRectangle.A4, PageBoxUtils.resolvePageBox(page, PageBoxUtils.TRIM_BOX));
        assertRectEquals(PDRectangle.A4, PageBoxUtils.resolvePageBox(page, PageBoxUtils.BLEED_BOX));
        assertRectEquals(PDRectangle.A4, PageBoxUtils.resolvePageBox(page, PageBoxUtils.ART_BOX));
    }

    @Test
    @DisplayName("Invalid pageBox value throws")
    void invalidValueThrows() {
        PDPage page = new PDPage(PDRectangle.A4);
        assertThrows(
                IllegalArgumentException.class,
                () -> PageBoxUtils.resolvePageBox(page, "NOT_A_BOX"));
    }

    @Test
    @DisplayName("importPageAsFormCovering bounds the form to the requested area")
    void importCoveringGrowsFormBounds() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            page.setCropBox(new PDRectangle(10, 10, 300, 400));
            doc.addPage(page);
            PDRectangle cover = new PDRectangle(0, 0, 500, 700);

            PDFormXObject form =
                    PageBoxUtils.importPageAsFormCovering(new LayerUtility(doc), doc, page, cover);

            PDRectangle bbox = form.getBBox();
            assertNotNull(bbox);
            assertTrue(
                    bbox.getLowerLeftX() <= cover.getLowerLeftX()
                            && bbox.getLowerLeftY() <= cover.getLowerLeftY()
                            && bbox.getUpperRightX() >= cover.getUpperRightX()
                            && bbox.getUpperRightY() >= cover.getUpperRightY(),
                    "form BBox must cover " + cover + ", got " + bbox);
            // The page's own CropBox entry is restored to its original value.
            assertRectEquals(new PDRectangle(10, 10, 300, 400), page.getCropBox());
        }
    }

    @Test
    @DisplayName("importPageAsFormCovering without cover leaves the CropBox untouched")
    void importCoveringNullCoverKeepsCropBox() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PageBoxUtils.importPageAsFormCovering(new LayerUtility(doc), doc, page, null);

            // A page with no explicit CropBox must not gain a materialized one.
            assertNull(page.getCOSObject().getItem(COSName.CROP_BOX));
        }
    }
}
