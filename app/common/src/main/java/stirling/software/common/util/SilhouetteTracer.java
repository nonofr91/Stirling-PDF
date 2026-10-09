package stirling.software.common.util;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import lombok.extern.slf4j.Slf4j;

/**
 * Extracts a closed, cuttable silhouette from a PDF page: the page is rasterized (rotation
 * neutralized so image space maps 1:1 onto user space), reduced to a binary subject mask, cleaned,
 * offset, then traced into polygons expressed in page points.
 *
 * <p>Mask sources: ALPHA (artwork already has transparency), BACKGROUND (uniform background
 * flood-filled from the page edges), AI (external subject-matting engine, optional) or AUTO which
 * tries the requested sources in order.
 */
@Slf4j
public final class SilhouetteTracer {

    public static final int MIN_DPI = 72;
    public static final int MAX_DPI = 600;
    public static final int DEFAULT_DPI = 150;

    /**
     * Upper bound on rendered pixels. Beyond the ARGB image (4 B/px) the pipeline holds a subject
     * mask, a cut mask, two label arrays and a distance field (~20 B/px transient), so the cap
     * keeps a worst-case page under ~500 MB of working memory.
     */
    private static final long MAX_PIXELS = 20_000_000;

    /**
     * Coverage outside [lo,hi] means the mask found "everything" or "nothing" — unusable either
     * way.
     */
    private static final float MIN_COVERAGE = 0.001f;

    private static final float MAX_COVERAGE = 0.995f;

    /** Chamfer weights are in 1/10 px so distances stay integer. */
    private static final int CHAMFER_ORTHO = 10;

    private static final int CHAMFER_DIAG = 14;

    private static final float MM_TO_PT = 72f / 25.4f;

    public enum ExtractionMode {
        ALPHA,
        BACKGROUND,
        AI,
        AUTO
    }

    public static final class Settings {
        /** Render resolution used to derive the mask; effective dpi is clamped by MAX_PIXELS. */
        public int dpi = DEFAULT_DPI;

        public ExtractionMode mode = ExtractionMode.AUTO;

        /** AUTO tries sources in this order; entries other than the chosen one are ignored. */
        public List<ExtractionMode> autoOrder =
                List.of(ExtractionMode.ALPHA, ExtractionMode.BACKGROUND, ExtractionMode.AI);

        /**
         * Gaps between artwork elements narrower than this (mm) are bridged so neighbouring pieces
         * merge under a single outer contour; 0 keeps every piece separate.
         */
        public float mergeGapMm = 8f;

        /** Alpha threshold 0-255; pixels more transparent than this are background. */
        public int alphaThreshold = 16;

        /** Max per-channel RGB distance from the border reference colour treated as background. */
        public int backgroundTolerance = 24;

        /** Connected components smaller than this (mm²) are dropped as noise. */
        public float minAreaMm2 = 1.0f;

        /** 0..100: drives Douglas-Peucker epsilon and the number of Chaikin smoothing passes. */
        public float smoothness = 20f;

        /** Positive values move the cut path outward from the silhouette, negative inward. */
        public float offsetMm = 0f;

        /** Keep fully enclosed holes (counter of an "o") as inner subpaths. */
        public boolean keepHoles = false;

        /** Confidence threshold applied to AI masks. */
        public float aiThreshold = 0.4f;

        /** Matting model id passed through to the AI engine when AI participates. */
        public String aiModelId = null;

        /**
         * Rough user-drawn perimeter as flat x,y pairs in page fractions (top-left origin); null
         * when unset. The ring just inside the polygon samples the intended background colour, the
         * flood starts there, and the mask is bounded by the polygon.
         */
        public float[] roi = null;

        /** 1-based page {@link #roi} applies to; 0 applies it to every page. */
        public int roiPage = 0;
    }

    /** One traced page: masks and render stay available so bleed can sample edge colours. */
    public static final class TraceResult {
        public final int pageIndex;

        /** Zero-rotation render; pixel (0,0) is user-space (llx, ury). */
        public final BufferedImage render;

        /** Unrotated box the render covers (crop ∩ media at rotation 0). */
        public final PDRectangle userSpace;

        public final float dpiEff;
        public final boolean[] subjectMask;
        public final boolean[] cutMask;

        /** Closed rings in user-space points; parallel {@link #holeFlags}. */
        public final List<List<Point2D.Float>> paths;

        public final List<Boolean> holeFlags;
        public final ExtractionMode modeUsed;

        TraceResult(
                int pageIndex,
                BufferedImage render,
                PDRectangle userSpace,
                float dpiEff,
                boolean[] subjectMask,
                boolean[] cutMask,
                List<List<Point2D.Float>> paths,
                List<Boolean> holeFlags,
                ExtractionMode modeUsed) {
            this.pageIndex = pageIndex;
            this.render = render;
            this.userSpace = userSpace;
            this.dpiEff = dpiEff;
            this.subjectMask = subjectMask;
            this.cutMask = cutMask;
            this.paths = paths;
            this.holeFlags = holeFlags;
            this.modeUsed = modeUsed;
        }

        /** Union of all traced rings, in user-space points. */
        public PDRectangle bounds() {
            float llx = Float.MAX_VALUE, lly = Float.MAX_VALUE;
            float urx = -Float.MAX_VALUE, ury = -Float.MAX_VALUE;
            for (List<Point2D.Float> ring : paths) {
                for (Point2D.Float p : ring) {
                    llx = Math.min(llx, p.x);
                    lly = Math.min(lly, p.y);
                    urx = Math.max(urx, p.x);
                    ury = Math.max(ury, p.y);
                }
            }
            return new PDRectangle(llx, lly, urx - llx, ury - lly);
        }
    }

    private SilhouetteTracer() {}

    /**
     * @param mattingEngine maps a rendered page to a [0,1] mask; only invoked when AI is tried.
     *     {@code null} disables the AI source.
     * @throws IllegalArgumentException when no source yields a usable mask
     */
    public static TraceResult trace(
            PDDocument document,
            int pageIndex,
            Settings settings,
            Function<BufferedImage, float[]> mattingEngine)
            throws IOException {
        PDPage page = document.getPage(pageIndex);
        int savedRotation = page.getRotation();
        BufferedImage img;
        PDRectangle space;
        float dpiEff;
        page.setRotation(0);
        try {
            space = page.getBBox();
            dpiEff = clampDpi(space, settings.dpi);
            img = new PDFRenderer(document).renderImageWithDPI(pageIndex, dpiEff, ImageType.ARGB);
        } finally {
            page.setRotation(savedRotation);
        }

        int w = img.getWidth();
        int h = img.getHeight();
        boolean[] roiMask =
                settings.roiPage == 0 || settings.roiPage == pageIndex + 1
                        ? rasterizeRoi(settings.roi, w, h)
                        : null;
        MaskWithMode found = buildSubjectMask(img, settings, mattingEngine, roiMask);
        boolean[] subject = found.mask;
        if (roiMask != null) {
            for (int i = 0; i < subject.length; i++) {
                subject[i] &= roiMask[i];
            }
        }
        if (found.speckled) {
            // Colour- and matte-derived masks fragment into speckle fields around textured
            // art; ~0.3 mm closing seals the cracks and opening drops the dust, otherwise
            // boundary tracing explodes on interior noise.
            consolidateMask(subject, w, h, Math.max(1, Math.round(0.3f / 25.4f * dpiEff)));
        }

        if (settings.mergeGapMm > 0) {
            // Closing with radius = half the gap joins elements without growing the outline:
            // the erode pass restores the dilated outer edge except across narrow gaps.
            morphClose(
                    subject,
                    w,
                    h,
                    Math.max(1, Math.round(settings.mergeGapMm / 2f / 25.4f * dpiEff)));
        }

        int minPx = mm2ToPx2(settings.minAreaMm2, dpiEff);
        if (minPx > 1) {
            removeSmallComponents(subject, w, h, minPx);
        }
        if (!settings.keepHoles) {
            fillInteriorHoles(subject, w, h);
        }
        requireCoverage(subject, "subject");

        int offsetPx = Math.round(settings.offsetMm / 25.4f * dpiEff);
        boolean[] cutMask = offsetPx == 0 ? subject : offsetMask(subject, w, h, offsetPx);
        requireCoverage(cutMask, "offset silhouette");

        List<Contour> contours = traceContours(cutMask, w, h);
        List<List<Point2D.Float>> paths = new ArrayList<>(contours.size());
        List<Boolean> holeFlags = new ArrayList<>(contours.size());
        float dpEpsPx = 0.15f + settings.smoothness / 100f * 6f * (dpiEff / DEFAULT_DPI);
        int chaikinIters = Math.min(3, Math.round(settings.smoothness / 25f));
        for (Contour c : contours) {
            List<int[]> ring = simplifyClosed(c.px, dpEpsPx);
            ring = chaikinClosed(ring, chaikinIters);
            if (ring.size() < 3) {
                continue;
            }
            List<Point2D.Float> pts = new ArrayList<>(ring.size());
            for (int[] p : ring) {
                pts.add(toUserSpace(p[0], p[1], space, w, h));
            }
            paths.add(pts);
            holeFlags.add(c.hole);
        }
        if (paths.isEmpty()) {
            throw new IllegalArgumentException(
                    "Silhouette produced no closed contour on page " + (pageIndex + 1));
        }
        return new TraceResult(
                pageIndex, img, space, dpiEff, subject, cutMask, paths, holeFlags, found.mode);
    }

    private static float clampDpi(PDRectangle space, int requested) {
        float wPx = space.getWidth() / 72f * requested;
        float hPx = space.getHeight() / 72f * requested;
        if (wPx * hPx <= MAX_PIXELS) {
            return requested;
        }
        double eff = requested * Math.sqrt(MAX_PIXELS / (wPx * hPx));
        float clamped = (float) Math.max(MIN_DPI / 2f, Math.floor(eff));
        log.warn(
                "Cut contour render clamped to {} dpi (page too large for {} dpi)",
                clamped,
                requested);
        return clamped;
    }

    private record MaskWithMode(boolean[] mask, ExtractionMode mode, boolean speckled) {}

    private static MaskWithMode buildSubjectMask(
            BufferedImage img,
            Settings s,
            Function<BufferedImage, float[]> mattingEngine,
            boolean[] roiMask) {
        List<ExtractionMode> order = s.mode == ExtractionMode.AUTO ? s.autoOrder : List.of(s.mode);
        List<String> tried = new ArrayList<>();
        // A filled-rectangle mask means the source found an embedded raster frame, not a
        // silhouette (e.g. a photo dropped on the page). In AUTO keep walking to smarter
        // modes; held as fallback — a rectangle still beats no answer.
        MaskWithMode rectangleFallback = null;
        for (ExtractionMode mode : order) {
            boolean[] m = null;
            switch (mode) {
                case ALPHA -> m = alphaMask(img, s.alphaThreshold);
                case BACKGROUND -> {
                    if (roiMask == null
                            && !bordersUniform(img, s.alphaThreshold, s.backgroundTolerance)) {
                        tried.add("BACKGROUND(edges not uniform)");
                        continue;
                    }
                    m = backgroundMask(img, s.backgroundTolerance, s.alphaThreshold, roiMask);
                }
                case AI -> {
                    if (mattingEngine == null) {
                        tried.add("AI(engine unavailable)");
                        continue;
                    }
                    float[] matte = mattingEngine.apply(img);
                    m = new boolean[matte.length];
                    for (int i = 0; i < matte.length; i++) {
                        m[i] = matte[i] >= s.aiThreshold;
                    }
                }
                case AUTO ->
                        throw new IllegalArgumentException("AUTO cannot nest inside autoOrder");
            }
            if (!usableCoverage(m)) {
                tried.add(mode + "(no usable silhouette)");
                continue;
            }
            if (s.mode == ExtractionMode.AUTO
                    && (isFilledRectangle(m, img.getWidth()) || isFilledRoi(m, roiMask))) {
                if (rectangleFallback == null) {
                    rectangleFallback = new MaskWithMode(m, mode, mode != ExtractionMode.ALPHA);
                }
                tried.add(mode + "(degenerate rectangle)");
                continue;
            }
            return new MaskWithMode(m, mode, mode != ExtractionMode.ALPHA);
        }
        if (rectangleFallback != null) {
            return rectangleFallback;
        }
        throw new IllegalArgumentException(
                "No usable silhouette: "
                        + String.join("; ", tried)
                        + ". Provide artwork with transparency, a uniform background, or an AI model.");
    }

    /**
     * True when the mask is a single filled rectangle: mask pixels occupy ≥98.5% of their own
     * bounding box, so the contour carries no silhouette a page crop wouldn't give.
     */
    private static boolean isFilledRectangle(boolean[] mask, int w) {
        int x0 = w, x1 = -1, y0 = mask.length / w, y1 = -1, count = 0;
        for (int i = 0; i < mask.length; i++) {
            if (mask[i]) {
                int x = i % w, y = i / w;
                x0 = Math.min(x0, x);
                x1 = Math.max(x1, x);
                y0 = Math.min(y0, y);
                y1 = Math.max(y1, y);
                count++;
            }
        }
        if (x1 < x0) {
            return false;
        }
        long boxArea = (long) (x1 - x0 + 1) * (y1 - y0 + 1);
        return count >= boxArea * 0.985;
    }

    /** True when coverage sits between "nothing" and "the whole page". */
    private static boolean usableCoverage(boolean[] mask) {
        int count = 0;
        for (boolean b : mask) {
            if (b) {
                count++;
            }
        }
        float cov = (float) count / mask.length;
        return cov >= MIN_COVERAGE && cov <= MAX_COVERAGE;
    }

    private static void requireCoverage(boolean[] mask, String what) {
        if (!usableCoverage(mask)) {
            throw new IllegalArgumentException(
                    "Mask for " + what + " is empty or covers the whole page");
        }
    }

    private static boolean[] alphaMask(BufferedImage img, int threshold) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        boolean[] m = new boolean[px.length];
        for (int i = 0; i < px.length; i++) {
            m[i] = (px[i] >>> 24) > threshold;
        }
        return m;
    }

    /** Rasterizes the normalized ROI polygon; null ROI or degenerate input yields null. */
    static boolean[] rasterizeRoi(float[] roi, int w, int h) {
        if (roi == null || roi.length < 6 || roi.length % 2 != 0) {
            return null;
        }
        java.awt.Polygon poly = new java.awt.Polygon();
        for (int i = 0; i + 1 < roi.length; i += 2) {
            poly.addPoint(Math.round(roi[i] * w), Math.round(roi[i + 1] * h));
        }
        boolean[] inside = new boolean[w * h];
        java.awt.geom.Path2D.Float path = new java.awt.geom.Path2D.Float(poly);
        java.awt.Rectangle b = poly.getBounds();
        for (int y = Math.max(0, b.y); y < Math.min(h, b.y + b.height); y++) {
            for (int x = Math.max(0, b.x); x < Math.min(w, b.x + b.width); x++) {
                if (path.contains(x + 0.5f, y + 0.5f)) {
                    inside[y * w + x] = true;
                }
            }
        }
        return inside;
    }

    /**
     * True when the mask covers ≥98.5% of the ROI polygon — the flood found no silhouette inside
     * the perimeter, so AUTO should keep walking to the next source.
     */
    private static boolean isFilledRoi(boolean[] mask, boolean[] roiMask) {
        if (roiMask == null) {
            return false;
        }
        int roiCount = 0, filled = 0;
        for (int i = 0; i < mask.length; i++) {
            if (roiMask[i]) {
                roiCount++;
                if (mask[i]) {
                    filled++;
                }
            }
        }
        return roiCount > 0 && filled >= roiCount * 0.985f;
    }

    /**
     * Reference background colour: mean of opaque pixels along the border. Corner blocks can be
     * fully transparent (artwork floated on the page) — reading them yields black and the flood
     * stops dead at the artwork frame, producing a rectangle instead of the silhouette. Returns
     * null when the opaque border samples disagree beyond tolerance; {@link #backgroundMask} still
     * floods transparent margins without a colour reference.
     */
    private static float[] borderReference(BufferedImage img, int alphaThreshold, int tolerance) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        double[] sum = new double[3];
        int n = 0;
        int[] min = {255, 255, 255};
        int[] max = {0, 0, 0};
        for (int i = 0; i < px.length; i++) {
            int x = i % w, y = i / w;
            if (x > 0 && x < w - 1 && y > 0 && y < h - 1) {
                continue;
            }
            int rgb = px[i];
            if ((rgb >>> 24) <= alphaThreshold) {
                continue;
            }
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
            sum[0] += r;
            sum[1] += g;
            sum[2] += b;
            min[0] = Math.min(min[0], r);
            max[0] = Math.max(max[0], r);
            min[1] = Math.min(min[1], g);
            max[1] = Math.max(max[1], g);
            min[2] = Math.min(min[2], b);
            max[2] = Math.max(max[2], b);
            n++;
        }
        if (n < 8) {
            return null;
        }
        float[] ref = {(float) (sum[0] / n), (float) (sum[1] / n), (float) (sum[2] / n)};
        for (int k = 0; k < 3; k++) {
            if (ref[k] - min[k] > tolerance || max[k] - ref[k] > tolerance) {
                return null;
            }
        }
        return ref;
    }

    private static boolean bordersUniform(BufferedImage img, int alphaThreshold, int tolerance) {
        return borderReference(img, alphaThreshold, tolerance) != null
                || borderOpaqueCount(img, alphaThreshold) == 0;
    }

    private static int borderOpaqueCount(BufferedImage img, int alphaThreshold) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        int n = 0;
        for (int i = 0; i < px.length; i++) {
            int x = i % w, y = i / w;
            if ((x == 0 || x == w - 1 || y == 0 || y == h - 1) && (px[i] >>> 24) > alphaThreshold) {
                n++;
            }
        }
        return n;
    }

    /**
     * Flood-fills background from the borders: transparent margins plus opaque pixels matching the
     * border reference colour.
     */
    static boolean[] backgroundMask(BufferedImage img, int tolerance, int alphaThreshold) {
        return backgroundMask(img, tolerance, alphaThreshold, null);
    }

    /**
     * When {@code roiMask} is given, everything outside the polygon is exterior by user intent: the
     * flood seeds there and samples its reference colour from the ring just inside the drawn
     * perimeter — this is how an ambiguous beige-outside/white-inside artwork gets told apart.
     * Otherwise seeds the image borders as usual.
     */
    static boolean[] backgroundMask(
            BufferedImage img, int tolerance, int alphaThreshold, boolean[] roiMask) {
        int w = img.getWidth();
        int h = img.getHeight();
        float[] ref;
        int effectiveTolerance = tolerance;
        boolean[] bg = new boolean[w * h];
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        Deque<Integer> queue = new ArrayDeque<>();
        if (roiMask != null) {
            RingReference ring = roiRingReference(px, roiMask, w, h, tolerance);
            ref = ring == null ? null : ring.color();
            if (ring != null) {
                effectiveTolerance = Math.min(tolerance, Math.max(6, 4 * ring.spread() + 6));
            }
            for (int i = 0; i < px.length; i++) {
                if (!roiMask[i] && !bg[i]) {
                    bg[i] = true;
                    queue.add(i);
                }
            }
        } else {
            ref = borderReference(img, alphaThreshold, tolerance);
            for (int x = 0; x < w; x++) {
                spread(px, bg, queue, x, ref, tolerance, alphaThreshold);
                spread(px, bg, queue, (h - 1) * w + x, ref, tolerance, alphaThreshold);
            }
            for (int y = 0; y < h; y++) {
                spread(px, bg, queue, y * w, ref, tolerance, alphaThreshold);
                spread(px, bg, queue, y * w + w - 1, ref, tolerance, alphaThreshold);
            }
        }
        drain(px, bg, queue, ref, effectiveTolerance, alphaThreshold, w, h);
        if (ref == null) {
            // Transparent margins gave no colour reference: the artwork's own frame (the opaque
            // ring facing the flooded background) is the next best sample — e.g. a photo's
            // uniform backdrop inside an image floated on the page. The flood is applied to a
            // copy and only kept when it still leaves a silhouette: for solid artwork the
            // frontier IS the subject edge and the second pass would swallow it whole.
            float[] frontierRef = frontierReference(px, bg, w, h, tolerance);
            if (frontierRef != null) {
                boolean[] bg2 = bg.clone();
                Deque<Integer> queue2 = new ArrayDeque<>();
                for (int i = 0; i < px.length; i++) {
                    if (!bg2[i]) {
                        spread(px, bg2, queue2, i, frontierRef, tolerance, alphaThreshold);
                    }
                }
                drain(px, bg2, queue2, frontierRef, tolerance, alphaThreshold, w, h);
                int fgCount = 0;
                for (int i = 0; i < bg2.length; i++) {
                    if (!bg2[i]) {
                        fgCount++;
                    }
                }
                float cov = (float) fgCount / bg2.length;
                if (cov >= MIN_COVERAGE && cov <= MAX_COVERAGE) {
                    bg = bg2;
                }
            }
        }
        boolean[] fg = new boolean[w * h];
        for (int i = 0; i < fg.length; i++) {
            fg[i] = !bg[i];
        }
        return fg;
    }

    private static void drain(
            int[] px,
            boolean[] bg,
            Deque<Integer> queue,
            float[] ref,
            float tolerance,
            int alphaThreshold,
            int w,
            int h) {
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int x = i % w;
            int y = i / w;
            if (x > 0) {
                spread(px, bg, queue, i - 1, ref, tolerance, alphaThreshold);
            }
            if (x < w - 1) {
                spread(px, bg, queue, i + 1, ref, tolerance, alphaThreshold);
            }
            if (y > 0) {
                spread(px, bg, queue, i - w, ref, tolerance, alphaThreshold);
            }
            if (y < h - 1) {
                spread(px, bg, queue, i + w, ref, tolerance, alphaThreshold);
            }
        }
    }

    /**
     * Median colour of a several-pixel band just inside the ROI edge — what the user drew around is
     * background. Median absorbs artwork pixels the rough trace grazes; a mean or homogeneity check
     * would reject the whole ring in that common case.
     */
    private record RingReference(float[] color, int spread) {}

    private static RingReference roiRingReference(
            int[] px, boolean[] roiMask, int w, int h, int tolerance) {
        int band = 4;
        java.util.List<Integer> rs = new ArrayList<>();
        java.util.List<Integer> gs = new ArrayList<>();
        java.util.List<Integer> bs = new ArrayList<>();
        for (int i = 0; i < px.length; i++) {
            if (!roiMask[i]) {
                continue;
            }
            int x = i % w, y = i / w;
            int bandEdge =
                    Math.max(
                            0,
                            Math.min(
                                    band,
                                    Math.min(x, Math.min(y, Math.min(w - 1 - x, h - 1 - y)))));
            boolean onRing = false;
            for (int d = 0; d <= bandEdge && !onRing; d++) {
                onRing =
                        (x - d >= 0 && !roiMask[i - d])
                                || (x + d < w && !roiMask[i + d])
                                || (y - d >= 0 && !roiMask[i - d * w])
                                || (y + d < h && !roiMask[i + d * w]);
            }
            if (!onRing || (px[i] >>> 24) == 0) {
                continue;
            }
            int rgb = px[i];
            rs.add((rgb >> 16) & 0xFF);
            gs.add((rgb >> 8) & 0xFF);
            bs.add(rgb & 0xFF);
        }
        if (rs.size() < 16) {
            return null;
        }
        float[] color = {median(rs), median(gs), median(bs)};
        java.util.List<Integer> dists = new ArrayList<>(rs.size());
        for (int i = 0; i < rs.size(); i++) {
            dists.add(
                    Math.max(
                            Math.abs(rs.get(i) - Math.round(color[0])),
                            Math.max(
                                    Math.abs(gs.get(i) - Math.round(color[1])),
                                    Math.abs(bs.get(i) - Math.round(color[2])))));
        }
        // The ring's own spread sets how loosely "background" is matched: a clean
        // beige ring yields a tight band that still blocks a white halo sitting just
        // inside the user's tolerance, a noisy one widens toward it.
        int spread = Math.round(median(dists));
        return new RingReference(color, spread);
    }

    private static float median(java.util.List<Integer> values) {
        int[] sorted = values.stream().mapToInt(Integer::intValue).sorted().toArray();
        return (sorted[sorted.length / 2] + sorted[(sorted.length - 1) / 2]) / 2f;
    }

    /** Mean of opaque pixels adjacent to flooded background; null when they disagree. */
    private static float[] frontierReference(int[] px, boolean[] bg, int w, int h, int tolerance) {
        double[] sum = new double[3];
        int n = 0;
        int[] min = {255, 255, 255};
        int[] max = {0, 0, 0};
        for (int i = 0; i < px.length; i++) {
            if (bg[i]) {
                continue;
            }
            int x = i % w, y = i / w;
            boolean adjacent =
                    (x > 0 && bg[i - 1])
                            || (x < w - 1 && bg[i + 1])
                            || (y > 0 && bg[i - w])
                            || (y < h - 1 && bg[i + w]);
            if (!adjacent) {
                continue;
            }
            int rgb = px[i];
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
            sum[0] += r;
            sum[1] += g;
            sum[2] += b;
            min[0] = Math.min(min[0], r);
            max[0] = Math.max(max[0], r);
            min[1] = Math.min(min[1], g);
            max[1] = Math.max(max[1], g);
            min[2] = Math.min(min[2], b);
            max[2] = Math.max(max[2], b);
            n++;
        }
        if (n < 8) {
            return null;
        }
        float[] ref = {(float) (sum[0] / n), (float) (sum[1] / n), (float) (sum[2] / n)};
        for (int k = 0; k < 3; k++) {
            if (ref[k] - min[k] > tolerance || max[k] - ref[k] > tolerance) {
                return null;
            }
        }
        return ref;
    }

    private static void spread(
            int[] px,
            boolean[] bg,
            Deque<Integer> queue,
            int i,
            float[] ref,
            float tol,
            int alphaThreshold) {
        if (bg[i]) {
            return;
        }
        int rgb = px[i];
        // Unpainted regions render transparent: they belong to the background, not the subject.
        if ((rgb >>> 24) <= alphaThreshold
                || (ref != null
                        && Math.abs(((rgb >> 16) & 0xFF) - ref[0]) <= tol
                        && Math.abs(((rgb >> 8) & 0xFF) - ref[1]) <= tol
                        && Math.abs((rgb & 0xFF) - ref[2]) <= tol)) {
            bg[i] = true;
            queue.add(i);
        }
    }

    /**
     * Morphological close-then-open with a square radius-{@code r} window: fills background cracks
     * narrower than 2r inside the subject, then drops foreground dust narrower than 2r. Both passes
     * run in O(n) through an integral image.
     */
    static void consolidateMask(boolean[] mask, int w, int h, int r) {
        morphClose(mask, w, h, r);
        boolean[] opened = dilate(erode(mask.clone(), w, h, r), w, h, r);
        System.arraycopy(opened, 0, mask, 0, mask.length);
    }

    /** Dilate-then-erode: bridges foreground gaps narrower than 2r, seals same-size pockets. */
    static void morphClose(boolean[] mask, int w, int h, int r) {
        boolean[] closed = erode(dilate(mask, w, h, r), w, h, r);
        System.arraycopy(closed, 0, mask, 0, mask.length);
    }

    private static boolean[] dilate(boolean[] m, int w, int h, int r) {
        int[] integ = integral(m, w, h);
        boolean[] out = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (windowSum(integ, w, h, x, y, r) > 0) {
                    out[y * w + x] = true;
                }
            }
        }
        return out;
    }

    private static boolean[] erode(boolean[] m, int w, int h, int r) {
        int[] integ = integral(m, w, h);
        boolean[] out = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - r), y0 = Math.max(0, y - r);
                int x1 = Math.min(w - 1, x + r), y1 = Math.min(h - 1, y + r);
                if (windowSum(integ, w, h, x, y, r) == (x1 - x0 + 1) * (y1 - y0 + 1)) {
                    out[y * w + x] = true;
                }
            }
        }
        return out;
    }

    private static int[] integral(boolean[] m, int w, int h) {
        int[] integ = new int[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            int row = 0;
            for (int x = 0; x < w; x++) {
                row += m[y * w + x] ? 1 : 0;
                integ[(y + 1) * (w + 1) + x + 1] = integ[y * (w + 1) + x + 1] + row;
            }
        }
        return integ;
    }

    private static int windowSum(int[] integ, int w, int h, int x, int y, int r) {
        int x0 = Math.max(0, x - r), y0 = Math.max(0, y - r);
        int x1 = Math.min(w - 1, x + r), y1 = Math.min(h - 1, y + r);
        return integ[(y1 + 1) * (w + 1) + x1 + 1]
                - integ[y0 * (w + 1) + x1 + 1]
                - integ[(y1 + 1) * (w + 1) + x0]
                + integ[y0 * (w + 1) + x0];
    }

    /** 4-connected components; clears every component smaller than minPx in place. */
    static void removeSmallComponents(boolean[] mask, int w, int h, int minPx) {
        int[] comp = labelComponents(mask, w, h);
        int max = 0;
        for (int c : comp) {
            max = Math.max(max, c);
        }
        int[] sizes = new int[max + 1];
        for (int c : comp) {
            if (c > 0) {
                sizes[c]++;
            }
        }
        for (int i = 0; i < mask.length; i++) {
            if (comp[i] > 0 && sizes[comp[i]] < minPx) {
                mask[i] = false;
            }
        }
    }

    /** Marks every background pixel not reachable from the border as foreground. */
    static void fillInteriorHoles(boolean[] mask, int w, int h) {
        boolean[] exterior = new boolean[w * h];
        Deque<Integer> queue = new ArrayDeque<>();
        for (int x = 0; x < w; x++) {
            pushBg(mask, exterior, queue, x);
            pushBg(mask, exterior, queue, (h - 1) * w + x);
        }
        for (int y = 0; y < h; y++) {
            pushBg(mask, exterior, queue, y * w);
            pushBg(mask, exterior, queue, y * w + w - 1);
        }
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int x = i % w;
            int y = i / w;
            if (x > 0) {
                pushBg(mask, exterior, queue, i - 1);
            }
            if (x < w - 1) {
                pushBg(mask, exterior, queue, i + 1);
            }
            if (y > 0) {
                pushBg(mask, exterior, queue, i - w);
            }
            if (y < h - 1) {
                pushBg(mask, exterior, queue, i + w);
            }
        }
        for (int i = 0; i < mask.length; i++) {
            if (!mask[i] && !exterior[i]) {
                mask[i] = true;
            }
        }
    }

    private static void pushBg(boolean[] mask, boolean[] exterior, Deque<Integer> queue, int i) {
        if (!mask[i] && !exterior[i]) {
            exterior[i] = true;
            queue.add(i);
        }
    }

    /**
     * Distance to the nearest foreground pixel in 1/10 px, capped at {@code
     * capTenths}+CHAMFER_DIAG. {@code src} optionally receives the flat index of that nearest
     * foreground pixel (used to propagate edge colours into bleed).
     */
    static int[] chamferDistance(boolean[] fg, int w, int h, int capTenths, int[] src) {
        int inf = capTenths + CHAMFER_DIAG * 2;
        int[] d = new int[w * h];
        for (int i = 0; i < d.length; i++) {
            if (fg[i]) {
                d[i] = 0;
                if (src != null) {
                    src[i] = i;
                }
            } else {
                d[i] = inf;
                if (src != null) {
                    src[i] = -1;
                }
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (x > 0) {
                    relax(d, src, i, i - 1, CHAMFER_ORTHO);
                    if (y > 0) {
                        relax(d, src, i, i - w - 1, CHAMFER_DIAG);
                    }
                }
                if (y > 0) {
                    relax(d, src, i, i - w, CHAMFER_ORTHO);
                    if (x < w - 1) {
                        relax(d, src, i, i - w + 1, CHAMFER_DIAG);
                    }
                }
            }
        }
        for (int y = h - 1; y >= 0; y--) {
            for (int x = w - 1; x >= 0; x--) {
                int i = y * w + x;
                if (x < w - 1) {
                    relax(d, src, i, i + 1, CHAMFER_ORTHO);
                    if (y < h - 1) {
                        relax(d, src, i, i + w + 1, CHAMFER_DIAG);
                    }
                }
                if (y < h - 1) {
                    relax(d, src, i, i + w, CHAMFER_ORTHO);
                    if (x > 0) {
                        relax(d, src, i, i + w - 1, CHAMFER_DIAG);
                    }
                }
            }
        }
        return d;
    }

    private static void relax(int[] d, int[] src, int i, int from, int w10) {
        int cand = d[from] + w10;
        if (cand < d[i]) {
            d[i] = cand;
            if (src != null) {
                src[i] = src[from];
            }
        }
    }

    /**
     * Dilate ({@code offsetPx} &gt; 0) or erode (&lt; 0) a mask by a disk of that radius. Erosion
     * keeps pixels whose distance to the nearest background exceeds the radius.
     */
    static boolean[] offsetMask(boolean[] mask, int w, int h, int offsetPx) {
        if (offsetPx > 0) {
            int cap = offsetPx * CHAMFER_ORTHO;
            int[] d = chamferDistance(mask, w, h, cap, null);
            boolean[] out = new boolean[mask.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = d[i] <= cap;
            }
            return out;
        }
        int radius = -offsetPx;
        boolean[] inv = new boolean[mask.length];
        for (int i = 0; i < inv.length; i++) {
            inv[i] = !mask[i];
        }
        int cap = radius * CHAMFER_ORTHO;
        int[] d = chamferDistance(inv, w, h, cap, null);
        boolean[] out = new boolean[mask.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = mask[i] && d[i] > cap;
        }
        return out;
    }

    record Contour(List<int[]> px, boolean hole) {}

    /**
     * Outer boundary of every 4-connected foreground component plus the boundary of every
     * background component not touching the image edge (a hole).
     */
    static List<Contour> traceContours(boolean[] mask, int w, int h) {
        int[] fgComp = labelComponents(mask, w, h);
        boolean[] inv = new boolean[mask.length];
        for (int i = 0; i < inv.length; i++) {
            inv[i] = !mask[i];
        }
        int[] bgComp = labelComponents(inv, w, h);

        // Background components touching the border are exterior, not holes.
        int maxBg = 0;
        for (int c : bgComp) {
            maxBg = Math.max(maxBg, c);
        }
        boolean[] bgIsHole = new boolean[maxBg + 1];
        int[] bgFirstPixel = new int[maxBg + 1];
        boolean[] bgTouchesEdge = new boolean[maxBg + 1];
        for (int x = 0; x < w; x++) {
            if (bgComp[x] > 0) {
                bgTouchesEdge[bgComp[x]] = true;
            }
            if (bgComp[(h - 1) * w + x] > 0) {
                bgTouchesEdge[bgComp[(h - 1) * w + x]] = true;
            }
        }
        for (int y = 0; y < h; y++) {
            if (bgComp[y * w] > 0) {
                bgTouchesEdge[bgComp[y * w]] = true;
            }
            if (bgComp[y * w + w - 1] > 0) {
                bgTouchesEdge[bgComp[y * w + w - 1]] = true;
            }
        }
        for (int i = 0; i < bgComp.length; i++) {
            int c = bgComp[i];
            if (c > 0 && bgFirstPixel[c] == 0) {
                bgFirstPixel[c] = i;
            }
        }

        List<Contour> contours = new ArrayList<>();
        int maxFg = 0;
        for (int c : fgComp) {
            maxFg = Math.max(maxFg, c);
        }
        int[] fgFirstPixel = new int[maxFg + 1];
        for (int i = 0; i < fgComp.length; i++) {
            int c = fgComp[i];
            if (c > 0 && fgFirstPixel[c] == 0) {
                fgFirstPixel[c] = i;
            }
        }
        for (int c = 1; c <= maxFg; c++) {
            int start = fgFirstPixel[c];
            contours.add(new Contour(mooreTrace(fgComp, c, start, w, h), false));
        }
        for (int c = 1; c <= maxBg; c++) {
            if (bgTouchesEdge[c] || bgFirstPixel[c] == 0) {
                continue;
            }
            bgIsHole[c] = true;
            contours.add(new Contour(mooreTrace(bgComp, c, bgFirstPixel[c], w, h), true));
        }
        return contours;
    }

    /** Labels 4-connected components; 0 = not member, ids are 1-based in raster order. */
    private static int[] labelComponents(boolean[] mask, int w, int h) {
        int[] comp = new int[mask.length];
        int next = 1;
        Deque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < mask.length; i++) {
            if (!mask[i] || comp[i] != 0) {
                continue;
            }
            comp[i] = next;
            queue.add(i);
            while (!queue.isEmpty()) {
                int j = queue.poll();
                int x = j % w;
                int y = j / w;
                if (x > 0 && mask[j - 1] && comp[j - 1] == 0) {
                    comp[j - 1] = next;
                    queue.add(j - 1);
                }
                if (x < w - 1 && mask[j + 1] && comp[j + 1] == 0) {
                    comp[j + 1] = next;
                    queue.add(j + 1);
                }
                if (y > 0 && mask[j - w] && comp[j - w] == 0) {
                    comp[j - w] = next;
                    queue.add(j - w);
                }
                if (y < h - 1 && mask[j + w] && comp[j + w] == 0) {
                    comp[j + w] = next;
                    queue.add(j + w);
                }
            }
            next++;
        }
        return comp;
    }

    /**
     * Moore-neighbourhood boundary tracing with Jacob's stopping criterion: the loop ends when the
     * start pixel is re-entered with the same backtrack it was first left from. {@code label[i] ==
     * member} marks the region; the boundary is returned as pixel-centre coordinates, without
     * repeating the start at the end.
     */
    private static List<int[]> mooreTrace(int[] label, int member, int start, int w, int h) {
        // clockwise from W, image coords (y down)
        int[][] dirs = {{-1, 0}, {-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}};
        int sx = start % w;
        int sy = start / w;
        int bx = sx - 1; // initial backtrack sits west of the raster-first pixel
        int by = sy;
        int cx = sx;
        int cy = sy;
        List<int[]> boundary = new ArrayList<>();
        long guard = 4L * w * h;
        while (true) {
            boundary.add(new int[] {cx, cy});
            int bi = dirIndex(bx - cx, by - cy, dirs);
            int ncx = -1;
            int ncy = -1;
            int nbx = bx;
            int nby = by;
            for (int k = 0; k < 8; k++) {
                int j = (bi + k) % 8;
                int nx = cx + dirs[j][0];
                int ny = cy + dirs[j][1];
                if (nx >= 0 && ny >= 0 && nx < w && ny < h && label[ny * w + nx] == member) {
                    ncx = nx;
                    ncy = ny;
                    break;
                }
                nbx = nx;
                nby = ny;
            }
            if (ncx < 0) {
                break; // isolated pixel
            }
            if (ncx == sx && ncy == sy && nbx == sx - 1 && nby == sy) {
                break; // start re-entered from its initial backtrack: contour closed
            }
            cx = ncx;
            cy = ncy;
            bx = nbx;
            by = nby;
            if (boundary.size() > guard) {
                break;
            }
        }
        return boundary;
    }

    private static int dirIndex(int dx, int dy, int[][] dirs) {
        for (int i = 0; i < dirs.length; i++) {
            if (dirs[i][0] == dx && dirs[i][1] == dy) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Douglas-Peucker on a closed ring: split at the two most distant extreme points so the
     * simplification cannot collapse the ring onto itself.
     */
    private static List<int[]> simplifyClosed(List<int[]> ring, float epsPx) {
        if (ring.size() < 8) {
            return ring;
        }
        int minX = 0;
        int maxX = 0;
        for (int i = 1; i < ring.size(); i++) {
            if (ring.get(i)[0] < ring.get(minX)[0]) {
                minX = i;
            }
            if (ring.get(i)[0] > ring.get(maxX)[0]) {
                maxX = i;
            }
        }
        if (minX == maxX) {
            return ring;
        }
        List<int[]> chain1 = subRing(ring, minX, maxX);
        List<int[]> chain2 = subRing(ring, maxX, minX);
        List<int[]> out = new ArrayList<>();
        List<int[]> a = rdp(chain1, epsPx);
        List<int[]> b = rdp(chain2, epsPx);
        out.addAll(a.subList(0, a.size() - 1));
        out.addAll(b.subList(0, b.size() - 1));
        return out.size() >= 3 ? out : ring;
    }

    private static List<int[]> subRing(List<int[]> ring, int from, int to) {
        List<int[]> chain = new ArrayList<>();
        int i = from;
        chain.add(ring.get(i));
        while (i != to) {
            i = (i + 1) % ring.size();
            chain.add(ring.get(i));
        }
        return chain;
    }

    private static List<int[]> rdp(List<int[]> pts, float eps) {
        int n = pts.size();
        if (n < 3) {
            return pts;
        }
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[] {0, n - 1});
        while (!stack.isEmpty()) {
            int[] seg = stack.pop();
            int a = seg[0];
            int b = seg[1];
            if (b - a < 2) {
                continue;
            }
            double ax = pts.get(a)[0];
            double ay = pts.get(a)[1];
            double bx = pts.get(b)[0];
            double by = pts.get(b)[1];
            double dx = bx - ax;
            double dy = by - ay;
            double len = Math.hypot(dx, dy);
            int worst = -1;
            double worstD = -1;
            for (int i = a + 1; i < b; i++) {
                double px = pts.get(i)[0];
                double py = pts.get(i)[1];
                double dist =
                        len == 0
                                ? Math.hypot(px - ax, py - ay)
                                : Math.abs(dy * px - dx * py + bx * ay - by * ax) / len;
                if (dist > worstD) {
                    worstD = dist;
                    worst = i;
                }
            }
            if (worstD > eps) {
                keep[worst] = true;
                stack.push(new int[] {a, worst});
                stack.push(new int[] {worst, b});
            }
        }
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                out.add(pts.get(i));
            }
        }
        return out;
    }

    /** Corner-cutting smoothing; each pass replaces every edge by two points at 25% and 75%. */
    private static List<int[]> chaikinClosed(List<int[]> ring, int iterations) {
        List<double[]> work = new ArrayList<>(ring.size());
        for (int[] p : ring) {
            work.add(new double[] {p[0], p[1]});
        }
        for (int it = 0; it < iterations; it++) {
            List<double[]> next = new ArrayList<>(work.size() * 2);
            int n = work.size();
            for (int i = 0; i < n; i++) {
                double[] p = work.get(i);
                double[] q = work.get((i + 1) % n);
                next.add(new double[] {0.75 * p[0] + 0.25 * q[0], 0.75 * p[1] + 0.25 * q[1]});
                next.add(new double[] {0.25 * p[0] + 0.75 * q[0], 0.25 * p[1] + 0.75 * q[1]});
            }
            work = next;
        }
        List<int[]> out = new ArrayList<>(work.size());
        for (double[] p : work) {
            out.add(new int[] {(int) Math.round(p[0]), (int) Math.round(p[1])});
        }
        return out;
    }

    private static Point2D.Float toUserSpace(
            int px, int py, PDRectangle space, int imgW, int imgH) {
        float x = space.getLowerLeftX() + (px + 0.5f) / imgW * space.getWidth();
        float y = space.getUpperRightY() - (py + 0.5f) / imgH * space.getHeight();
        return new Point2D.Float(x, y);
    }

    static int mm2ToPx2(float mm2, float dpi) {
        double pxPerMm = dpi / 25.4;
        return (int) Math.max(1, Math.round(mm2 * pxPerMm * pxPerMm));
    }

    static int mmToPx(float mm, float dpi) {
        return Math.round(mm * dpi / 25.4f);
    }
}
