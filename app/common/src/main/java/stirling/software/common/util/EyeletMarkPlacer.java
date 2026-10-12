package stirling.software.common.util;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties;

/**
 * Computes and paints eyelet (grommet) position marks: a cross centred on each position, drawn
 * along lines parallel to the trim edges at a per-side distance. Placement follows the
 * Fiery/PrintFactory convention — each side is driven by either a fixed count or a maximum spacing.
 * A corner where both adjacent sides are enabled carries exactly one mark (a diagonal cross) at the
 * intersection of their offset lines; a corner with a single enabled side gets that side's end mark
 * on the trim edge.
 *
 * <p>Offsets are signed: positive moves the mark line inside the TrimBox (printed on the product),
 * negative moves it into the slug (MediaBox/CropBox are grown to cover it). Marks are stroked in
 * pure black (DeviceCMYK K only) so they stay on the K plate.
 */
public final class EyeletMarkPlacer {

    public static final float DEFAULT_DIAMETER_MM = 10f;
    public static final float DEFAULT_STROKE_PT = 0.5f;
    public static final float DEFAULT_OFFSET_MM = 15f;
    public static final float DEFAULT_SPACING_MM = 500f;
    public static final int MAX_MARKS_PER_SIDE = 500;

    private static final float MM_TO_PT = 72f / 25.4f;
    private static final String RESOURCE_PREFIX = "SPDF_EM_";

    private static final PDColor MARK_COLOR =
            new PDColor(new float[] {0f, 0f, 0f, 1f}, PDDeviceCMYK.INSTANCE);

    public enum Mode {
        COUNT,
        MAX_SPACING
    }

    public enum Side {
        TOP,
        RIGHT,
        BOTTOM,
        LEFT
    }

    /** Per-side placement parameters. A side is disabled when its mode value is zero. */
    public static final class SideSettings {
        /** Signed distance from the trim edge in mm: positive inside, negative outside. */
        public float offsetMm = DEFAULT_OFFSET_MM;

        /** COUNT mode: marks on this side including both ends; 0 disables the side. */
        public int count = 0;

        /** MAX_SPACING mode: largest allowed gap between marks; <= 0 disables the side. */
        public float maxSpacingMm = DEFAULT_SPACING_MM;
    }

    public static final class Settings {
        public Mode mode = Mode.MAX_SPACING;
        public float eyeletDiameterMm = DEFAULT_DIAMETER_MM;
        public float strokeWidthPt = DEFAULT_STROKE_PT;

        /** Optional-content layer name; null/blank draws the marks as plain content. */
        public String layerName = null;

        public final EnumMap<Side, SideSettings> sides = new EnumMap<>(Side.class);

        public Settings() {
            for (Side side : Side.values()) {
                sides.put(side, new SideSettings());
            }
        }
    }

    /**
     * One mark centre in PDF user space. {@link #corner} is true when two enabled sides meet there
     * — it is painted as a diagonal cross so the corner reads differently from the straight crosses
     * along the sides.
     */
    public static final class Mark extends Point2D.Float {
        public final boolean corner;

        private Mark(float x, float y, boolean corner) {
            super(x, y);
            this.corner = corner;
        }
    }

    private EyeletMarkPlacer() {}

    /**
     * Every mark centre in PDF user space, deduplicated so a shared corner is painted once.
     * Iteration order is stable (top, right, bottom, left; each side from its start to end).
     * Assumes {@code /UserUnit = 1}; pass the page's user unit to the overload otherwise.
     */
    public static List<Mark> placements(PDRectangle trim, Settings s) {
        return placements(trim, s, 1f);
    }

    /**
     * {@code userUnit} is the page's {@code /UserUnit}: user-space coordinates are physically
     * scaled by it, so every millimetre/point parameter is divided down by it here and in paint.
     */
    public static List<Mark> placements(PDRectangle trim, Settings s, float userUnit) {
        Layout layout = layout(trim, s, userUnit);
        List<Mark> marks = new ArrayList<>(layout.points.size());
        for (Map.Entry<Long, Point2D.Float> entry : layout.points.entrySet()) {
            Point2D.Float p = entry.getValue();
            marks.add(new Mark(p.x, p.y, layout.shared.contains(entry.getKey())));
        }
        return List.copyOf(marks);
    }

    private static final class Layout {
        final LinkedHashMap<Long, Point2D.Float> points = new LinkedHashMap<>();

        /** Dedupe keys written by two sides: the corner mark where they meet. */
        final Set<Long> shared = new HashSet<>();
    }

    private static Layout layout(PDRectangle trim, Settings s, float unit) {
        boolean top = enabled(s, Side.TOP);
        boolean right = enabled(s, Side.RIGHT);
        boolean bottom = enabled(s, Side.BOTTOM);
        boolean left = enabled(s, Side.LEFT);
        if (!top && !right && !bottom && !left) {
            throw new IllegalArgumentException("At least one side must be enabled");
        }

        float lx = trim.getLowerLeftX();
        float ly = trim.getLowerLeftY();
        float rx = trim.getUpperRightX();
        float ty = trim.getUpperRightY();

        // Along-axis interval of a side is shortened by the corner marks of its enabled
        // neighbours, so every side's endpoints land exactly on the shared corner marks.
        float x0 = left ? lx + mmToPt(s.sides.get(Side.LEFT).offsetMm, unit) : lx;
        float x1 = right ? rx - mmToPt(s.sides.get(Side.RIGHT).offsetMm, unit) : rx;
        float y0 = bottom ? ly + mmToPt(s.sides.get(Side.BOTTOM).offsetMm, unit) : ly;
        float y1 = top ? ty - mmToPt(s.sides.get(Side.TOP).offsetMm, unit) : ty;

        Layout layout = new Layout();
        if (top) {
            float y = ty - mmToPt(s.sides.get(Side.TOP).offsetMm, unit);
            addSide(layout, s, Side.TOP, x0, y, x1, y, unit);
        }
        if (right) {
            float x = rx - mmToPt(s.sides.get(Side.RIGHT).offsetMm, unit);
            addSide(layout, s, Side.RIGHT, x, y0, x, y1, unit);
        }
        if (bottom) {
            float y = ly + mmToPt(s.sides.get(Side.BOTTOM).offsetMm, unit);
            addSide(layout, s, Side.BOTTOM, x0, y, x1, y, unit);
        }
        if (left) {
            float x = lx + mmToPt(s.sides.get(Side.LEFT).offsetMm, unit);
            addSide(layout, s, Side.LEFT, x, y0, x, y1, unit);
        }
        return layout;
    }

    /**
     * Paints a cross at every placement on {@code page} and grows MediaBox/CropBox when a mark
     * lands outside them. Returns the number of marks painted.
     */
    public static int apply(PDDocument document, PDPage page, Settings s) throws IOException {
        PDRectangle trim = resolveTrim(page);
        // Freeze the implicit trim before growBoxes expands the MediaBox: without an
        // explicit TrimBox the resolved trim is the MediaBox, and growing it would move
        // the trim out from under the marks on any later run.
        if (page.getCOSObject().getItem(COSName.TRIM_BOX) == null) {
            page.setTrimBox(trim);
        }
        float unit = page.getUserUnit() > 0 ? page.getUserUnit() : 1f;
        Layout layout = layout(trim, s, unit);
        PDOptionalContentGroup layer = layerFor(document, s.layerName);

        PDResources resources = page.getResources();
        if (layer != null) {
            if (resources == null) {
                resources = new PDResources();
                page.setResources(resources);
            }
            resources.put(uniqueName(resources.getPropertiesNames(), "EM"), layer);
        }

        float r = s.eyeletDiameterMm * MM_TO_PT / (2f * unit);
        float d = (float) (r / Math.sqrt(2.0));
        try (PDPageContentStream cs =
                new PDPageContentStream(
                        document, page, PDPageContentStream.AppendMode.APPEND, true)) {
            cs.saveGraphicsState();
            if (layer != null) {
                cs.beginMarkedContent(COSName.OC, layer);
            }
            cs.setStrokingColor(MARK_COLOR);
            cs.setLineWidth(s.strokeWidthPt / unit);
            for (Map.Entry<Long, Point2D.Float> entry : layout.points.entrySet()) {
                Point2D.Float p = entry.getValue();
                if (layout.shared.contains(entry.getKey())) {
                    cs.moveTo(p.x - d, p.y - d);
                    cs.lineTo(p.x + d, p.y + d);
                    cs.moveTo(p.x - d, p.y + d);
                    cs.lineTo(p.x + d, p.y - d);
                } else {
                    cs.moveTo(p.x - r, p.y);
                    cs.lineTo(p.x + r, p.y);
                    cs.moveTo(p.x, p.y - r);
                    cs.lineTo(p.x, p.y + r);
                }
            }
            cs.stroke();
            if (layer != null) {
                cs.endMarkedContent();
            }
            cs.restoreGraphicsState();
        }

        growBoxes(page, List.copyOf(layout.points.values()), r + s.strokeWidthPt / (2f * unit));
        return layout.points.size();
    }

    /**
     * Explicit TrimBox else MediaBox — the resolution the viewer's page-box snapshot applies, so
     * previewed marks match the painted ones. Mirrors {@link PageBoxUtils#resolvePageBox} minus its
     * warn: most PDFs carry no TrimBox, so it would log once per page on this per-page call.
     */
    private static PDRectangle resolveTrim(PDPage page) {
        PDRectangle trim = page.getTrimBox();
        return trim == null || page.getCOSObject().getItem(COSName.TRIM_BOX) == null
                ? page.getMediaBox()
                : trim;
    }

    private static boolean enabled(Settings s, Side side) {
        SideSettings ss = s.sides.get(side);
        return s.mode == Mode.COUNT ? ss.count > 0 : ss.maxSpacingMm > 0;
    }

    /**
     * Distributes a side's marks over the segment (a0 → a1), ends included, at a position count
     * resolved from the mode (COUNT = the side's count; MAX_SPACING = ceil(span/spacing) + 1). The
     * segment is axis-aligned, so the span is a signed coordinate delta — hypot() would hide a
     * reversed interval where the neighbouring offsets overlap and leave no room.
     */
    private static void addSide(
            Layout layout,
            Settings s,
            Side side,
            float a0x,
            float a0y,
            float a1x,
            float a1y,
            float unit) {
        float span = (a1x - a0x) + (a1y - a0y);
        if (span <= 0) {
            throw new IllegalArgumentException(
                    side + " side has no room for marks: offsets consume its whole length");
        }
        SideSettings ss = s.sides.get(side);
        int n;
        if (s.mode == Mode.COUNT) {
            if (ss.count == 1) {
                throw new IllegalArgumentException(
                        "count = 1 cannot honour the mandatory corner marks");
            }
            n = ss.count;
        } else {
            // The count must be bounded before the cast: a tiny maxSpacingMm otherwise
            // overflows the int loop into millions — or an endless — number of marks.
            double needed = Math.ceil(span / mmToPt(ss.maxSpacingMm, unit)) + 1;
            if (needed > MAX_MARKS_PER_SIDE) {
                throw new IllegalArgumentException(
                        side
                                + " side needs "
                                + needed
                                + " marks at "
                                + ss.maxSpacingMm
                                + " mm spacing (limit "
                                + MAX_MARKS_PER_SIDE
                                + ")");
            }
            n = (int) needed;
        }
        if (n > MAX_MARKS_PER_SIDE) {
            throw new IllegalArgumentException(
                    side + " side cannot carry more than " + MAX_MARKS_PER_SIDE + " marks");
        }
        for (int i = 0; i < n; i++) {
            float t = (float) i / (n - 1f);
            put(layout, a0x + t * (a1x - a0x), a0y + t * (a1y - a0y));
        }
    }

    private static void put(Layout layout, float x, float y) {
        // 0.001 pt granularity: adjacent sides emit the same corner position from different
        // float paths, and the dedupe key must treat them as one mark.
        long key = (Math.round(x * 1000.0) << 32) | (Math.round(y * 1000.0) & 0xFFFFFFFFL);
        if (layout.points.putIfAbsent(key, new Point2D.Float(x, y)) != null) {
            layout.shared.add(key);
        }
    }

    private static void growBoxes(PDPage page, List<Point2D.Float> marks, float halfExtent) {
        float lx = Float.MAX_VALUE;
        float ly = Float.MAX_VALUE;
        float rx = -Float.MAX_VALUE;
        float ry = -Float.MAX_VALUE;
        for (Point2D.Float p : marks) {
            lx = Math.min(lx, p.x - halfExtent);
            ly = Math.min(ly, p.y - halfExtent);
            rx = Math.max(rx, p.x + halfExtent);
            ry = Math.max(ry, p.y + halfExtent);
        }
        PDRectangle bounds = new PDRectangle(lx, ly, rx - lx, ry - ly);
        page.setMediaBox(PageBoxUtils.union(page.getMediaBox(), bounds));
        page.setCropBox(PageBoxUtils.union(page.getCropBox(), bounds));
    }

    /**
     * The marks layer is printable by construction — no Usage/PrintState=OFF here, unlike the
     * CutContour layer, because the marks guide a manual finishing step on the printed sheet.
     */
    private static PDOptionalContentGroup layerFor(PDDocument document, String layerName) {
        if (layerName == null || layerName.isBlank()) {
            return null;
        }
        PDOptionalContentProperties props = document.getDocumentCatalog().getOCProperties();
        if (props == null) {
            props = new PDOptionalContentProperties();
            document.getDocumentCatalog().setOCProperties(props);
        }
        PDOptionalContentGroup group = props.hasGroup(layerName) ? props.getGroup(layerName) : null;
        if (group != null) {
            return group;
        }
        group = new PDOptionalContentGroup(layerName);
        props.addGroup(group);
        return group;
    }

    private static COSName uniqueName(Iterable<COSName> existing, String prefix) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (COSName n : existing) {
            names.add(n.getName());
        }
        int i = 0;
        String candidate;
        do {
            candidate = RESOURCE_PREFIX + prefix + i++;
        } while (names.contains(candidate));
        return COSName.getPDFName(candidate);
    }

    private static float mmToPt(float mm, float unit) {
        return mm * MM_TO_PT / unit;
    }
}
