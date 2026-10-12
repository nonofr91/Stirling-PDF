package stirling.software.common.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.junit.jupiter.api.Test;

import stirling.software.common.util.EyeletMarkPlacer.Mark;
import stirling.software.common.util.EyeletMarkPlacer.Mode;
import stirling.software.common.util.EyeletMarkPlacer.Settings;
import stirling.software.common.util.EyeletMarkPlacer.Side;

class EyeletMarkPlacerTest {

    private static final float MM = 72f / 25.4f;
    private static final PDRectangle TRIM = new PDRectangle(0, 0, 600, 800);
    private static final float OFF = 15 * MM; // default 15 mm offset in points

    private static Settings settings() {
        Settings s = new Settings();
        s.mode = Mode.COUNT;
        for (Side side : Side.values()) {
            s.sides.get(side).offsetMm = 15;
            s.sides.get(side).count = 0;
            s.sides.get(side).maxSpacingMm = 0;
        }
        return s;
    }

    private static void assertMarkAt(List<Mark> marks, float x, float y) {
        assertTrue(
                marks.stream().anyMatch(p -> close(p.x, x) && close(p.y, y)),
                () -> "missing mark at (" + x + "," + y + ") in " + marks);
    }

    private static boolean close(float a, float b) {
        return Math.abs(a - b) < 0.05f;
    }

    @Test
    void countMode_fourSides_dedupsCorners() {
        Settings s = settings();
        for (Side side : Side.values()) {
            s.sides.get(side).count = 2;
        }
        List<Mark> marks = EyeletMarkPlacer.placements(TRIM, s);
        // One mark per corner, at the intersection of the two sides' offset lines.
        assertEquals(4, marks.size());
        assertMarkAt(marks, OFF, 800 - OFF);
        assertMarkAt(marks, 600 - OFF, 800 - OFF);
        assertMarkAt(marks, 600 - OFF, OFF);
        assertMarkAt(marks, OFF, OFF);
    }

    @Test
    void countMode_topOnly_spansFullWidth() {
        Settings s = settings();
        s.sides.get(Side.TOP).count = 4;
        List<Mark> marks = EyeletMarkPlacer.placements(TRIM, s);
        assertEquals(4, marks.size());
        assertMarkAt(marks, 0, 800 - OFF);
        assertMarkAt(marks, 200, 800 - OFF);
        assertMarkAt(marks, 400, 800 - OFF);
        assertMarkAt(marks, 600, 800 - OFF);
    }

    @Test
    void countMode_twoAdjacentSides_shareOneCorner() {
        Settings s = settings();
        s.sides.get(Side.TOP).count = 2;
        s.sides.get(Side.LEFT).count = 2;
        List<Mark> marks = EyeletMarkPlacer.placements(TRIM, s);
        assertEquals(3, marks.size());
        assertMarkAt(marks, OFF, 800 - OFF); // shared top-left corner
        assertMarkAt(marks, 600, 800 - OFF); // top-right, right side disabled → on the trim
        assertMarkAt(marks, OFF, 0); // bottom-left, bottom side disabled
    }

    @Test
    void sharedCorners_areFlaggedForDiagonalPaint() {
        Settings s = settings();
        s.sides.get(Side.TOP).count = 2;
        s.sides.get(Side.LEFT).count = 2;
        List<Mark> marks = EyeletMarkPlacer.placements(TRIM, s);
        assertEquals(3, marks.size());
        for (Mark m : marks) {
            boolean shared = close(m.x, OFF) && close(m.y, 800 - OFF);
            assertEquals(shared, m.corner, () -> "corner flag wrong at (" + m.x + "," + m.y + ")");
        }
    }

    @Test
    void spacingMode_computesCountFromMaxGap() {
        Settings s = settings();
        s.mode = Mode.MAX_SPACING;
        s.sides.get(Side.TOP).maxSpacingMm = 100; // 283.46 pt, span 600 → 3 intervals
        List<Mark> marks = EyeletMarkPlacer.placements(TRIM, s);
        assertEquals(4, marks.size());
        assertMarkAt(marks, 0, 800 - OFF);
        assertMarkAt(marks, 200, 800 - OFF);
        assertMarkAt(marks, 400, 800 - OFF);
        assertMarkAt(marks, 600, 800 - OFF);
    }

    @Test
    void spacingMode_gapBeyondSpan_keepsEndsOnly() {
        Settings s = settings();
        s.mode = Mode.MAX_SPACING;
        s.sides.get(Side.TOP).maxSpacingMm = 500;
        List<Mark> marks = EyeletMarkPlacer.placements(TRIM, s);
        assertEquals(2, marks.size());
        assertMarkAt(marks, 0, 800 - OFF);
        assertMarkAt(marks, 600, 800 - OFF);
    }

    @Test
    void spacingMode_disabledSidesGetNoMarks() {
        Settings s = settings();
        s.mode = Mode.MAX_SPACING;
        s.sides.get(Side.TOP).maxSpacingMm = 250;
        s.sides.get(Side.LEFT).maxSpacingMm = 0; // disabled
        List<Mark> marks = EyeletMarkPlacer.placements(TRIM, s);
        assertTrue(marks.stream().allMatch(p -> close(p.y, 800 - OFF)));
    }

    @Test
    void negativeOffset_placesMarksOutside() {
        Settings s = settings();
        s.sides.get(Side.TOP).count = 2;
        s.sides.get(Side.TOP).offsetMm = -10;
        List<Mark> marks = EyeletMarkPlacer.placements(TRIM, s);
        assertTrue(marks.stream().allMatch(p -> close(p.y, 800 + 10 * MM)));
    }

    @Test
    void countOfOne_isRejected() {
        Settings s = settings();
        s.sides.get(Side.TOP).count = 1;
        assertThrows(IllegalArgumentException.class, () -> EyeletMarkPlacer.placements(TRIM, s));
    }

    @Test
    void noSideEnabled_isRejected() {
        Settings s = settings();
        assertThrows(IllegalArgumentException.class, () -> EyeletMarkPlacer.placements(TRIM, s));
    }

    @Test
    void offsetsConsumingSpan_areRejected() {
        Settings s = settings();
        s.sides.get(Side.TOP).count = 2;
        // Left/right offsets of 200 mm each exceed the 600 pt width → top interval < 0.
        s.sides.get(Side.LEFT).count = 2;
        s.sides.get(Side.RIGHT).count = 2;
        s.sides.get(Side.LEFT).offsetMm = 200;
        s.sides.get(Side.RIGHT).offsetMm = 200;
        assertThrows(IllegalArgumentException.class, () -> EyeletMarkPlacer.placements(TRIM, s));
    }

    @Test
    void apply_insideMarksLeavesBoxesUnchanged() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(TRIM);
            doc.addPage(page);
            Settings s = settings();
            // Four sides → corner marks inset by both offsets, arms fully on the sheet.
            for (Side side : Side.values()) {
                s.sides.get(side).count = 2;
            }

            int painted = EyeletMarkPlacer.apply(doc, page, s);

            assertEquals(4, painted);
            assertEquals(TRIM.getLowerLeftX(), page.getMediaBox().getLowerLeftX(), 0.01);
            assertEquals(TRIM.getWidth(), page.getMediaBox().getWidth(), 0.01);
            assertEquals(TRIM.getHeight(), page.getMediaBox().getHeight(), 0.01);
        }
    }

    @Test
    void apply_outsideMarksGrowMediaAndCropBox() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(TRIM);
            doc.addPage(page);
            Settings s = settings();
            s.sides.get(Side.TOP).count = 2;
            s.sides.get(Side.TOP).offsetMm = -20; // marks 20 mm above the trim

            EyeletMarkPlacer.apply(doc, page, s);

            float margin = 20 * MM + s.eyeletDiameterMm * MM / 2f + s.strokeWidthPt / 2f;
            assertTrue(
                    page.getMediaBox().getUpperRightY() >= 800 + margin - 0.01,
                    "MediaBox should cover the painted marks");
            assertEquals(
                    page.getMediaBox().getUpperRightY(), page.getCropBox().getUpperRightY(), 0.01);
        }
    }

    @Test
    void apply_layerNameCreatesPrintableOcg() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(TRIM);
            doc.addPage(page);
            Settings s = settings();
            s.sides.get(Side.TOP).count = 2;
            s.layerName = "EyeletMarks";

            EyeletMarkPlacer.apply(doc, page, s);

            PDOptionalContentGroup group =
                    doc.getDocumentCatalog().getOCProperties().getGroup("EyeletMarks");
            assertNotNull(group, "EyeletMarks OCG missing");
            // The layer stays printable: no Usage/PrintState suppression like the cut layer.
            assertNull(group.getCOSObject().getDictionaryObject(COSName.USAGE));
        }
    }

    @Test
    void spacingMode_tinySpacing_isRejected() {
        Settings s = settings();
        s.mode = Mode.MAX_SPACING;
        // 0.001 mm over a 600 pt span would need ~200M marks — the per-side
        // limit must reject it before the loop runs.
        s.sides.get(Side.TOP).maxSpacingMm = 0.001f;
        assertThrows(IllegalArgumentException.class, () -> EyeletMarkPlacer.placements(TRIM, s));
    }

    @Test
    void countAbovePerSideLimit_isRejected() {
        Settings s = settings();
        s.sides.get(Side.TOP).count = EyeletMarkPlacer.MAX_MARKS_PER_SIDE + 1;
        assertThrows(IllegalArgumentException.class, () -> EyeletMarkPlacer.placements(TRIM, s));
    }

    @Test
    void apply_missingTrimBox_materializesItBeforeGrowing() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(TRIM);
            doc.addPage(page);
            Settings s = settings();
            s.sides.get(Side.TOP).count = 2;
            s.sides.get(Side.TOP).offsetMm = -20;

            int first = EyeletMarkPlacer.apply(doc, page, s);

            // The implicit trim must be frozen as an explicit TrimBox: otherwise the
            // grown MediaBox would become the new trim reference on a second run.
            assertNotNull(page.getCOSObject().getItem(COSName.TRIM_BOX));
            assertEquals(0f, page.getTrimBox().getLowerLeftY(), 0.01);
            assertEquals(800f, page.getTrimBox().getUpperRightY(), 0.01);
            assertEquals(first, EyeletMarkPlacer.apply(doc, page, s));
            assertEquals(800f, page.getTrimBox().getUpperRightY(), 0.01);
        }
    }

    @Test
    void placements_userUnit_scalesMillimetreParameters() {
        Settings s = settings();
        s.sides.get(Side.TOP).count = 2;
        // UserUnit = 10: every mm and pt parameter is divided down, so the
        // 15 mm offset becomes 15·MM/10 user units inside the trim.
        List<Mark> marks = EyeletMarkPlacer.placements(TRIM, s, 10f);
        assertEquals(2, marks.size());
        assertTrue(marks.stream().allMatch(p -> close(p.y, 800 - 1.5f * MM)));
    }

    @Test
    void apply_userUnit_keepsBoxesInUserSpace() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(TRIM);
            page.getCOSObject().setFloat(COSName.USER_UNIT, 10f);
            doc.addPage(page);
            Settings s = settings();
            s.sides.get(Side.TOP).count = 2;
            s.sides.get(Side.TOP).offsetMm = -20;

            EyeletMarkPlacer.apply(doc, page, s);

            // The mark line sits 20·MM/10 user units above the trim — physical 20 mm.
            float margin = 2 * MM + s.eyeletDiameterMm * MM / 20f + s.strokeWidthPt / 20f;
            assertTrue(
                    page.getMediaBox().getUpperRightY() >= 800 + margin - 0.01,
                    "MediaBox should cover the painted marks in user units");
        }
    }
}
