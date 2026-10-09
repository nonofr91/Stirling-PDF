package stirling.software.common.util;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;

/**
 * Derives the TrimBox of a page from the crop (cut) marks painted around it. Printers' marks are
 * thin axis-aligned strokes: the vertical marks sit exactly on the trim's left/right edges and the
 * horizontal marks exactly on its bottom/top edges. The structural signature that separates them
 * from ruled lines: an edge's marks stand <em>outside</em> the area they bound — a vertical edge's
 * marks appear both above the top edge and below the bottom edge.
 *
 * <p>Detection is deliberately conservative: it returns {@code null} unless exactly one
 * self-consistent mark quad is found — a false positive trim is worse than asking the user for
 * explicit boxes.
 */
public final class CropMarkDetector {

    /** Marks shorter than this are almost certainly artifacts, not cut marks (~2 mm). */
    private static final float MIN_MARK_LENGTH_PT = 6f;

    /** Marks longer than this are ruled lines or frames, not cut marks (~32 mm). */
    private static final float MAX_MARK_LENGTH_PT = 90f;

    /** Cut marks are hairlines; anything heavier is content. */
    private static final float MAX_MARK_WIDTH_PT = 1.5f;

    /** Two segments closer than this on their fixed axis belong to the same mark cluster. */
    private static final float CLUSTER_TOLERANCE_PT = 1f;

    /**
     * A filled mark drawn as a freeform path is accepted as a strip only if one edge of the subpath
     * runs at least this fraction of its long axis — rejects thin zigzags.
     */
    private static final float MIN_LONG_EDGE_FRACTION = 0.7f;

    private static final float AXIS_TOLERANCE_PT = 0.3f;

    private CropMarkDetector() {}

    /**
     * Finds the trim rectangle defined by the page's crop marks, in the page's user space (the
     * space {@code TrimBox} is expressed in). Returns {@code null} when no unambiguous mark layout
     * is present. Annotation appearance streams are scanned too: some generators paint printer
     * marks there rather than in the page content.
     */
    public static PDRectangle detect(PDPage page) throws IOException {
        Collector collector = new Collector(page);
        collector.processPage(page);
        for (PDAnnotation annotation : page.getAnnotations()) {
            if (!annotation.isHidden() && !annotation.isInvisible()) {
                collector.showAnnotation(annotation);
            }
        }
        return collector.deriveTrim();
    }

    private static final class Segment {
        final float fixed; // x for vertical marks, y for horizontal
        final float mid; // midpoint along the segment axis

        Segment(boolean vertical, float x1, float y1, float x2, float y2) {
            if (vertical) {
                fixed = (x1 + x2) / 2f;
                mid = (y1 + y2) / 2f;
            } else {
                fixed = (y1 + y2) / 2f;
                mid = (x1 + x2) / 2f;
            }
        }
    }

    private static final class Cluster {
        final List<Segment> segments = new ArrayList<>();
        float fixedSum;

        void add(Segment segment) {
            segments.add(segment);
            fixedSum += segment.fixed;
        }

        float center() {
            return fixedSum / segments.size();
        }
    }

    private static List<Cluster> cluster(List<Segment> segments) {
        List<Segment> sorted = new ArrayList<>(segments);
        sorted.sort(Comparator.comparingDouble(segment -> segment.fixed));
        List<Cluster> clusters = new ArrayList<>();
        Cluster current = null;
        float lastFixed = Float.NaN;
        for (Segment segment : sorted) {
            if (current == null || segment.fixed - lastFixed > CLUSTER_TOLERANCE_PT) {
                current = new Cluster();
                clusters.add(current);
            }
            current.add(segment);
            lastFixed = segment.fixed;
        }
        return clusters;
    }

    private static final class Collector extends PDFGraphicsStreamEngine {
        private final List<Segment> vertical = new ArrayList<>();
        private final List<Segment> horizontal = new ArrayList<>();
        private final List<float[]> pendingSegments = new ArrayList<>();
        private final List<float[]> pendingRects = new ArrayList<>();
        // Start index of each open subpath in pendingSegments, recorded on moveTo.
        private final List<Integer> subpathBounds = new ArrayList<>();
        private Point2D currentPoint = new Point2D.Float();
        private Point2D subpathStart = new Point2D.Float();

        Collector(PDPage page) {
            super(page);
        }

        /**
         * Picks the unique trim quad consistent with the marks: each vertical edge carries marks
         * beyond <em>both</em> horizontal edges, and vice versa. Returns {@code null} unless
         * exactly one quad qualifies — extra mark columns (fold marks, imposition) make the layout
         * ambiguous rather than merely harder.
         */
        PDRectangle deriveTrim() {
            List<Cluster> verticals = cluster(vertical);
            List<Cluster> horizontals = cluster(horizontal);
            PDRectangle found = null;
            for (int l = 0; l < verticals.size(); l++) {
                for (int r = l + 1; r < verticals.size(); r++) {
                    for (int b = 0; b < horizontals.size(); b++) {
                        for (int t = b + 1; t < horizontals.size(); t++) {
                            PDRectangle quad =
                                    consistentQuad(
                                            verticals.get(l), verticals.get(r),
                                            horizontals.get(b), horizontals.get(t));
                            if (quad == null) {
                                continue;
                            }
                            if (found != null) {
                                return null;
                            }
                            found = quad;
                        }
                    }
                }
            }
            return found;
        }

        private static PDRectangle consistentQuad(
                Cluster left, Cluster right, Cluster bottom, Cluster top) {
            float xL = left.center();
            float xR = right.center();
            float yB = bottom.center();
            float yT = top.center();
            if (xR <= xL || yT <= yB) {
                return null;
            }
            if (!brackets(left, yB, yT)
                    || !brackets(right, yB, yT)
                    || !brackets(bottom, xL, xR)
                    || !brackets(top, xL, xR)) {
                return null;
            }
            return new PDRectangle(xL, yB, xR - xL, yT - yB);
        }

        /** The edge's marks must stand outside the opposite band: below lo and above hi. */
        private static boolean brackets(Cluster edge, float lo, float hi) {
            boolean below = false;
            boolean above = false;
            for (Segment segment : edge.segments) {
                below |= segment.mid < lo;
                above |= segment.mid > hi;
            }
            return below && above;
        }

        private void commitPending(boolean stroked, boolean filled) {
            boolean thin = transformWidth(getGraphicsState().getLineWidth()) <= MAX_MARK_WIDTH_PT;
            if (stroked && thin) {
                for (float[] segment : pendingSegments) {
                    addMark(segment[0], segment[1], segment[2], segment[3]);
                }
            }
            for (float[] rect : pendingRects) {
                float x = rect[0];
                float y = rect[1];
                float w = rect[2];
                float h = rect[3];
                if (Math.min(w, h) <= MAX_MARK_WIDTH_PT) {
                    // A hairline painted as a thin strip — filled, stroked or both — still
                    // marks an edge: keep its long axis' midline.
                    if (w >= h) {
                        addMark(x, y + h / 2f, x + w, y + h / 2f);
                    } else {
                        addMark(x + w / 2f, y, x + w / 2f, y + h);
                    }
                } else if (stroked && thin) {
                    addMark(x, y, x + w, y);
                    addMark(x + w, y, x + w, y + h);
                    addMark(x + w, y + h, x, y + h);
                    addMark(x, y + h, x, y);
                }
            }
            if (filled) {
                for (int i = 0; i < subpathBounds.size(); i++) {
                    int from = subpathBounds.get(i);
                    int to =
                            i + 1 < subpathBounds.size()
                                    ? subpathBounds.get(i + 1)
                                    : pendingSegments.size();
                    addFilledMark(from, to);
                }
            }
            pendingSegments.clear();
            pendingRects.clear();
            subpathBounds.clear();
        }

        /**
         * A mark filled as a freeform path (m/l/h f) rather than `re` or a stroke: when the subpath
         * is a thin axis-aligned strip, its midline marks an edge just like a stroked hairline.
         * Requires a real long edge so thin zigzags and wedges stay out.
         */
        private void addFilledMark(int from, int to) {
            if (to - from < 3) {
                return;
            }
            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            float longestH = 0;
            float longestV = 0;
            for (int i = from; i < to; i++) {
                float[] s = pendingSegments.get(i);
                minX = Math.min(minX, Math.min(s[0], s[2]));
                maxX = Math.max(maxX, Math.max(s[0], s[2]));
                minY = Math.min(minY, Math.min(s[1], s[3]));
                maxY = Math.max(maxY, Math.max(s[1], s[3]));
                float dx = Math.abs(s[2] - s[0]);
                float dy = Math.abs(s[3] - s[1]);
                if (dy <= AXIS_TOLERANCE_PT) {
                    longestH = Math.max(longestH, dx);
                }
                if (dx <= AXIS_TOLERANCE_PT) {
                    longestV = Math.max(longestV, dy);
                }
            }
            float w = maxX - minX;
            float h = maxY - minY;
            if (w >= h) {
                if (h > MAX_MARK_WIDTH_PT || longestH < w * MIN_LONG_EDGE_FRACTION) {
                    return;
                }
                addMark(minX, minY + h / 2f, maxX, minY + h / 2f);
            } else {
                if (w > MAX_MARK_WIDTH_PT || longestV < h * MIN_LONG_EDGE_FRACTION) {
                    return;
                }
                addMark(minX + w / 2f, minY, minX + w / 2f, maxY);
            }
        }

        private void addMark(float x1, float y1, float x2, float y2) {
            float dx = Math.abs(x2 - x1);
            float dy = Math.abs(y2 - y1);
            float length = Math.max(dx, dy);
            if (length < MIN_MARK_LENGTH_PT || length > MAX_MARK_LENGTH_PT) {
                return;
            }
            if (dx <= AXIS_TOLERANCE_PT) {
                vertical.add(new Segment(true, x1, y1, x2, y2));
            } else if (dy <= AXIS_TOLERANCE_PT) {
                horizontal.add(new Segment(false, x1, y1, x2, y2));
            }
        }

        @Override
        public void moveTo(float x, float y) {
            subpathBounds.add(pendingSegments.size());
            currentPoint = new Point2D.Float(x, y);
            subpathStart = currentPoint;
        }

        @Override
        public void lineTo(float x, float y) {
            pendingSegments.add(
                    new float[] {(float) currentPoint.getX(), (float) currentPoint.getY(), x, y});
            currentPoint = new Point2D.Float(x, y);
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            currentPoint = new Point2D.Float(x3, y3);
        }

        @Override
        public Point2D getCurrentPoint() {
            return currentPoint;
        }

        @Override
        public void closePath() {
            lineTo((float) subpathStart.getX(), (float) subpathStart.getY());
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (Point2D point : new Point2D[] {p0, p1, p2, p3}) {
                minX = (float) Math.min(minX, point.getX());
                maxX = (float) Math.max(maxX, point.getX());
                minY = (float) Math.min(minY, point.getY());
                maxY = (float) Math.max(maxY, point.getY());
            }
            pendingRects.add(new float[] {minX, minY, maxX - minX, maxY - minY});
        }

        @Override
        public void endPath() {
            pendingSegments.clear();
            pendingRects.clear();
            subpathBounds.clear();
        }

        @Override
        public void strokePath() {
            commitPending(true, false);
        }

        @Override
        public void fillPath(int windingRule) {
            commitPending(false, true);
        }

        @Override
        public void fillAndStrokePath(int windingRule) {
            commitPending(true, true);
        }

        @Override
        public void clip(int windingRule) {}

        @Override
        public void drawImage(PDImage pdImage) {}

        @Override
        public void shadingFill(COSName shadingName) {}
    }
}
