package stirling.software.SPDF.service.preflight;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.model.api.security.PrintPreflightReport;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Category;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Facts;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Finding;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.FindingArea;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.FontFact;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.PageSize;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Severity;
import stirling.software.SPDF.model.api.security.PrintPreflightRequest;
import stirling.software.SPDF.service.preflight.PreflightGraphicsEngine.FontUse;
import stirling.software.SPDF.service.preflight.PreflightGraphicsEngine.ImageUse;
import stirling.software.SPDF.service.preflight.PreflightGraphicsEngine.PaintedArea;
import stirling.software.SPDF.service.preflight.PreflightGraphicsEngine.StrokeUse;

/**
 * Read-only print preflight: walks painted content and page geometry and reports what would break
 * or degrade on press — missing fonts, RGB/spot color, low-resolution images, absent or unpainted
 * bleed, hairlines, transparency and finishing annotations.
 */
@Service
@Slf4j
public class PrintPreflightService {

    private static final float PT_PER_MM = 72f / 25.4f;
    private static final int COVERAGE_DPI = 72;
    private static final int COVERAGE_STRIDE = 2;
    private static final float COVERAGE_MIN_PERCENT = 95f;
    private static final int WHITE_RGB_THRESHOLD = 245;
    private static final float BLEED_TOLERANCE_PT = 0.5f;
    private static final float PAGE_SIZE_TOLERANCE_PT = 0.5f;

    public PrintPreflightReport analyze(
            PDDocument document, String fileName, long fileSizeBytes, PrintPreflightRequest request)
            throws IOException {

        PrintPreflightReport report = new PrintPreflightReport();
        report.setFileName(fileName);
        report.setFileSizeBytes(fileSizeBytes);
        report.setPdfVersion(Float.toString(document.getVersion()));
        report.setPageCount(document.getNumberOfPages());

        Map<String, FontUse> fonts = new LinkedHashMap<>();
        Map<String, Integer> colorSpaceCounts = new LinkedHashMap<>();
        Set<String> spotColors = new LinkedHashSet<>();
        Set<String> technicalSeparations = new LinkedHashSet<>();
        List<ImageUse> images = new ArrayList<>();
        Map<Integer, List<ImageUse>> lowResByPage = new LinkedHashMap<>();
        double minImageDpi = Double.MAX_VALUE;
        Map<Integer, List<StrokeUse>> hairlinesByPage = new LinkedHashMap<>();
        Map<Integer, List<PaintedArea>> rgbAreasByPage = new LinkedHashMap<>();
        Map<Integer, List<PaintedArea>> spotAreasByPage = new LinkedHashMap<>();
        Map<Integer, List<PaintedArea>> alphaAreasByPage = new LinkedHashMap<>();
        Set<Integer> transparencyPages = new TreeSet<>();
        Set<Integer> optionalContentPages = new TreeSet<>();
        Map<Integer, List<FindingArea>> annotationAreasByPage = new LinkedHashMap<>();
        Set<Integer> missingTrimPages = new TreeSet<>();
        Set<Integer> missingBleedPages = new TreeSet<>();
        Map<Integer, PDRectangle> trimByPage = new LinkedHashMap<>();
        Map<String, List<FindingArea>> fontAreasByKey = new LinkedHashMap<>();
        Map<Integer, List<FindingArea>> insufficientBleedAreas = new LinkedHashMap<>();
        Set<Integer> unpaintedBleedPages = new TreeSet<>();
        List<Integer> unpaintedCoverage = new ArrayList<>();
        Map<Integer, List<FindingArea>> unpaintedBleedAreas = new LinkedHashMap<>();
        Map<String, Integer> pageSizeCounts = new LinkedHashMap<>();
        Map<String, Integer> pageSizeFirstPage = new LinkedHashMap<>();
        boolean anyTrim = false;
        boolean anyBleed = false;
        boolean transparency = false;

        PDFRenderer renderer = request.isCheckBleedCoverage() ? new PDFRenderer(document) : null;
        float requiredBleedPt = request.getRequiredBleedMm() * PT_PER_MM;

        int pageIndex = 0;
        for (PDPage page : document.getPages()) {
            pageIndex++;
            final int pageNum = pageIndex;

            PreflightGraphicsEngine engine = new PreflightGraphicsEngine(page);
            try {
                engine.processPage(page);
            } catch (Exception e) {
                log.debug("Preflight content pass failed on page {}", pageNum, e);
                report.addFinding(
                        new Finding(
                                Severity.WARNING,
                                Category.CONTENT,
                                "CONTENT_PARSE_ERROR",
                                "Page content could not be fully analyzed: " + e.getMessage(),
                                List.of(pageNum)));
            }
            for (Map.Entry<String, FontUse> e : engine.getFonts().entrySet()) {
                fonts.computeIfAbsent(e.getKey(), k -> e.getValue()).pages.add(pageNum);
                List<FindingArea> areas =
                        fontAreasByKey.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                for (float[] b : e.getValue().bounds) {
                    if (areas.size() >= 12) {
                        break;
                    }
                    areas.add(
                            new FindingArea(
                                    pageNum,
                                    b[0],
                                    b[1],
                                    b[2] - b[0],
                                    b[3] - b[1],
                                    e.getValue().name));
                }
            }
            engine.getColorSpaceCounts()
                    .forEach((k, v) -> colorSpaceCounts.merge(k, v, Integer::sum));
            spotColors.addAll(engine.getSpotColors());
            technicalSeparations.addAll(engine.getTechnicalSeparations());
            for (ImageUse img : engine.getImages()) {
                if (img.technical) {
                    continue;
                }
                images.add(img);
                if (!Double.isNaN(img.effectiveDpi)) {
                    minImageDpi = Math.min(minImageDpi, img.effectiveDpi);
                    if (img.effectiveDpi < request.getMinImageDpi()) {
                        lowResByPage.computeIfAbsent(pageNum, k -> new ArrayList<>()).add(img);
                    }
                }
            }
            for (PaintedArea area : engine.getPaintAreas()) {
                if (area.technical) {
                    continue;
                }
                String label = area.label != null ? area.label : "";
                if (label.contains("RGB") || label.startsWith("Indexed over DeviceRGB")) {
                    rgbAreasByPage.computeIfAbsent(pageNum, k -> new ArrayList<>()).add(area);
                }
                if (label.startsWith("Spot:")) {
                    spotAreasByPage.computeIfAbsent(pageNum, k -> new ArrayList<>()).add(area);
                }
            }
            for (PaintedArea area : engine.getAlphaAreas()) {
                if (!area.technical) {
                    alphaAreasByPage.computeIfAbsent(pageNum, k -> new ArrayList<>()).add(area);
                }
            }
            List<StrokeUse> thin = new ArrayList<>();
            for (StrokeUse s : engine.getStrokes()) {
                if (!s.technical && s.widthPt < request.getHairlineThresholdPt()) {
                    thin.add(s);
                }
            }
            if (!thin.isEmpty()) {
                hairlinesByPage.put(pageNum, thin);
            }
            if (engine.isTransparencyUsed() || hasTransparencyGroup(page)) {
                transparency = true;
                transparencyPages.add(pageNum);
            }
            if (engine.isOptionalContentUsed()) {
                optionalContentPages.add(pageNum);
            }

            PDRectangle media = page.getMediaBox();
            String sizeKey =
                    Math.round(media.getWidth())
                            + "x"
                            + Math.round(media.getHeight())
                            + "@"
                            + page.getRotation();
            pageSizeCounts.merge(sizeKey, 1, Integer::sum);
            pageSizeFirstPage.putIfAbsent(sizeKey, pageNum);

            boolean hasTrim = page.getCOSObject().getItem(COSName.TRIM_BOX) != null;
            boolean hasBleed = page.getCOSObject().getItem(COSName.BLEED_BOX) != null;
            anyTrim |= hasTrim;
            anyBleed |= hasBleed;
            PDRectangle trim = page.getTrimBox();
            PDRectangle bleed = page.getBleedBox();
            trimByPage.put(pageNum, trim);
            if (!hasTrim) {
                missingTrimPages.add(pageNum);
            }
            if (!hasBleed || bleedWidthPerSide(bleed, trim) == null) {
                missingBleedPages.add(pageNum);
            } else {
                float[] sides = bleedWidthPerSide(bleed, trim);
                List<FindingArea> gaps = new ArrayList<>();
                for (int side = 0; side < 4; side++) {
                    if (sides[side] < requiredBleedPt - BLEED_TOLERANCE_PT) {
                        gaps.add(
                                bleedGapArea(
                                        pageNum, trim, bleed, side, sides[side], requiredBleedPt));
                    }
                }
                if (!gaps.isEmpty()) {
                    insufficientBleedAreas
                            .computeIfAbsent(pageNum, k -> new ArrayList<>())
                            .addAll(gaps);
                } else if (renderer != null && requiredBleedPt > 0) {
                    BleedCoverage coverage =
                            bleedCoveragePercent(page, trim, bleed, renderer, pageNum);
                    if (coverage != null && coverage.percent < COVERAGE_MIN_PERCENT) {
                        unpaintedBleedPages.add(pageNum);
                        unpaintedCoverage.add(Math.round(coverage.percent));
                        if (!coverage.whiteZones.isEmpty()) {
                            unpaintedBleedAreas.put(pageNum, coverage.whiteZones);
                        }
                    }
                }
            }

            if (hasTrim) {
                for (PDAnnotation annotation : page.getAnnotations()) {
                    PDRectangle rect = annotation.getRectangle();
                    if (rect != null && trimBoundsOverlap(trim, rect)) {
                        annotationAreasByPage
                                .computeIfAbsent(pageNum, k -> new ArrayList<>())
                                .add(
                                        new FindingArea(
                                                pageNum,
                                                rect.getLowerLeftX(),
                                                rect.getLowerLeftY(),
                                                rect.getWidth(),
                                                rect.getHeight(),
                                                annotation.getSubtype()));
                    }
                }
            }
        }

        // Registration marks and finishing separations (cut paths, fold, varnish…) live in
        // facts.technicalSeparations — they are machine drivers, not ink on the artwork.
        Set<String> printSpots = new LinkedHashSet<>(spotColors);
        printSpots.removeAll(technicalSeparations);

        Facts facts = report.getFacts();
        for (FontUse f : fonts.values()) {
            FontFact fact = new FontFact(f.name, f.subType, f.embedded, f.type3);
            fact.setPages(new ArrayList<>(f.pages));
            facts.getFonts().add(fact);
        }
        facts.setColorSpaces(new ArrayList<>(colorSpaceCounts.keySet()));
        facts.setSpotColors(new ArrayList<>(printSpots));
        facts.setTechnicalSeparations(new ArrayList<>(technicalSeparations));
        facts.setImageCount(images.size());
        facts.setLowResImageCount(lowResByPage.values().stream().mapToInt(List::size).sum());
        facts.setTransparencyUsed(transparency);
        facts.setHasTrimBox(anyTrim);
        facts.setHasBleedBox(anyBleed);
        for (Map.Entry<String, Integer> e : pageSizeCounts.entrySet()) {
            String[] parts = e.getKey().split("[x@]");
            facts.getPageSizes()
                    .add(
                            new PageSize(
                                    Float.parseFloat(parts[0]),
                                    Float.parseFloat(parts[1]),
                                    Integer.parseInt(parts[2]),
                                    e.getValue()));
        }

        List<Integer> unembeddedPages = new ArrayList<>();
        List<String> unembeddedNames = new ArrayList<>();
        List<String> type3Names = new ArrayList<>();
        List<Integer> type3Pages = new ArrayList<>();
        List<FindingArea> unembeddedAreas = new ArrayList<>();
        List<FindingArea> type3Areas = new ArrayList<>();
        for (Map.Entry<String, FontUse> e : fonts.entrySet()) {
            FontUse f = e.getValue();
            if (!f.embedded) {
                unembeddedNames.add(f.name);
                unembeddedAreas.addAll(fontAreasByKey.getOrDefault(e.getKey(), List.of()));
                for (int p : f.pages) {
                    if (!unembeddedPages.contains(p)) {
                        unembeddedPages.add(p);
                    }
                }
            }
            if (f.type3) {
                type3Names.add(f.name);
                type3Areas.addAll(fontAreasByKey.getOrDefault(e.getKey(), List.of()));
                for (int p : f.pages) {
                    if (!type3Pages.contains(p)) {
                        type3Pages.add(p);
                    }
                }
            }
        }
        if (!unembeddedNames.isEmpty()) {
            Finding finding =
                    new Finding(
                            Severity.ERROR,
                            Category.FONTS,
                            "FONT_NOT_EMBEDDED",
                            "Fonts not embedded: "
                                    + String.join(", ", unembeddedNames)
                                    + ". Print output cannot be guaranteed without them",
                            unembeddedPages);
            unembeddedAreas.forEach(finding::addArea);
            report.addFinding(finding);
        }
        if (!type3Names.isEmpty()) {
            Finding finding =
                    new Finding(
                            Severity.WARNING,
                            Category.FONTS,
                            "FONT_TYPE3",
                            "Type 3 fonts in use: "
                                    + String.join(", ", type3Names)
                                    + " — they may print as bitmaps or be refused",
                            type3Pages);
            type3Areas.forEach(finding::addArea);
            report.addFinding(finding);
        }

        boolean rgbUsed =
                colorSpaceCounts.keySet().stream()
                        .anyMatch(l -> l.contains("RGB") || l.startsWith("Indexed over DeviceRGB"));
        if (rgbUsed) {
            Finding finding =
                    new Finding(
                            Severity.WARNING,
                            Category.COLOR,
                            "COLOR_RGB_USED",
                            "RGB content is painted — offset printing needs CMYK; convert or accept"
                                    + " a color shift",
                            new ArrayList<>(rgbAreasByPage.keySet()));
            addPaintAreas(finding, rgbAreasByPage);
            report.addFinding(finding);
        }
        if (!printSpots.isEmpty()) {
            Finding finding =
                    new Finding(
                            Severity.INFO,
                            Category.COLOR,
                            "COLOR_SPOT",
                            "Spot colors in use: " + String.join(", ", printSpots),
                            new ArrayList<>(spotAreasByPage.keySet()));
            addPaintAreas(finding, spotAreasByPage);
            report.addFinding(finding);
        }

        if (!lowResByPage.isEmpty()) {
            Finding finding =
                    new Finding(
                            Severity.WARNING,
                            Category.IMAGES,
                            "IMAGE_LOW_RES",
                            lowResByPage.size()
                                    + " page(s) contain images below "
                                    + request.getMinImageDpi()
                                    + " dpi effective (lowest: "
                                    + Math.round(minImageDpi)
                                    + " dpi)",
                            new ArrayList<>(lowResByPage.keySet()));
            for (Map.Entry<Integer, List<ImageUse>> e : lowResByPage.entrySet()) {
                for (ImageUse img : e.getValue()) {
                    if (img.bounds != null) {
                        finding.addArea(
                                new FindingArea(
                                        e.getKey(),
                                        img.bounds[0],
                                        img.bounds[1],
                                        img.bounds[2] - img.bounds[0],
                                        img.bounds[3] - img.bounds[1],
                                        Math.round(img.effectiveDpi) + " dpi"));
                    }
                }
            }
            report.addFinding(finding);
        }

        if (!missingTrimPages.isEmpty()) {
            report.addFinding(
                    new Finding(
                            Severity.WARNING,
                            Category.GEOMETRY,
                            "TRIMBOX_MISSING",
                            "No TrimBox — the finished cut size is undefined",
                            new ArrayList<>(missingTrimPages)));
        }
        if (!missingBleedPages.isEmpty()) {
            Finding finding =
                    new Finding(
                            Severity.ERROR,
                            Category.GEOMETRY,
                            "BLEED_MISSING",
                            "No BleedBox beyond the trim — cutting tolerance will expose white"
                                    + " edges",
                            new ArrayList<>(missingBleedPages));
            // The trim edge is where bleed would have to extend past.
            for (int p : missingBleedPages) {
                PDRectangle trim = trimByPage.get(p);
                if (trim != null) {
                    finding.addArea(
                            new FindingArea(
                                    p,
                                    trim.getLowerLeftX(),
                                    trim.getLowerLeftY(),
                                    trim.getWidth(),
                                    trim.getHeight(),
                                    "trim edge"));
                }
            }
            report.addFinding(finding);
        }
        if (!insufficientBleedAreas.isEmpty()) {
            Finding finding =
                    new Finding(
                            Severity.ERROR,
                            Category.GEOMETRY,
                            "BLEED_INSUFFICIENT",
                            "Bleed is under "
                                    + request.getRequiredBleedMm()
                                    + " mm on at least one side",
                            new ArrayList<>(insufficientBleedAreas.keySet()));
            insufficientBleedAreas.values().forEach(list -> list.forEach(finding::addArea));
            report.addFinding(finding);
        }
        if (!unpaintedBleedPages.isEmpty()) {
            Finding finding =
                    new Finding(
                            Severity.WARNING,
                            Category.GEOMETRY,
                            "BLEED_UNPAINTED",
                            "Bleed area declared but not painted — as low as "
                                    + minInt(unpaintedCoverage)
                                    + "% coverage; white slivers may show after trimming",
                            new ArrayList<>(unpaintedBleedPages));
            unpaintedBleedAreas.values().forEach(list -> list.forEach(finding::addArea));
            report.addFinding(finding);
        }
        if (!annotationAreasByPage.isEmpty()) {
            Finding finding =
                    new Finding(
                            Severity.WARNING,
                            Category.CONTENT,
                            "ANNOTATION_IN_TRIM",
                            "Annotations sit inside the trim area and may print",
                            new ArrayList<>(annotationAreasByPage.keySet()));
            annotationAreasByPage.values().forEach(list -> list.forEach(finding::addArea));
            report.addFinding(finding);
        }
        if (!hairlinesByPage.isEmpty()) {
            double min = Double.MAX_VALUE;
            for (List<StrokeUse> ws : hairlinesByPage.values()) {
                for (StrokeUse s : ws) {
                    min = Math.min(min, s.widthPt);
                }
            }
            Finding finding =
                    new Finding(
                            Severity.WARNING,
                            Category.CONTENT,
                            "HAIRLINE",
                            "Strokes thinner than "
                                    + request.getHairlineThresholdPt()
                                    + " pt (thinnest: "
                                    + String.format("%.3f", min)
                                    + " pt) may drop out in print",
                            new ArrayList<>(hairlinesByPage.keySet()));
            for (Map.Entry<Integer, List<StrokeUse>> e : hairlinesByPage.entrySet()) {
                for (StrokeUse s : e.getValue()) {
                    if (s.bounds != null) {
                        finding.addArea(
                                new FindingArea(
                                        e.getKey(),
                                        s.bounds[0],
                                        s.bounds[1],
                                        Math.max(s.bounds[2] - s.bounds[0], 0.5f),
                                        Math.max(s.bounds[3] - s.bounds[1], 0.5f),
                                        String.format("%.2f pt", s.widthPt)));
                    }
                }
            }
            report.addFinding(finding);
        }
        if (transparency) {
            Finding finding =
                    new Finding(
                            Severity.INFO,
                            Category.CONTENT,
                            "TRANSPARENCY",
                            "Live transparency present — flatten for PDF/X-1a workflows",
                            new ArrayList<>(transparencyPages));
            addPaintAreas(finding, alphaAreasByPage);
            report.addFinding(finding);
        }
        if (!optionalContentPages.isEmpty()) {
            report.addFinding(
                    new Finding(
                            Severity.INFO,
                            Category.CONTENT,
                            "OPTIONAL_CONTENT",
                            "Optional content groups (layers) — hidden layers may print or be"
                                    + " dropped depending on the RIP",
                            new ArrayList<>(optionalContentPages)));
        }
        if (pageSizeCounts.size() > 1) {
            List<Integer> allPages = new ArrayList<>();
            for (int i = 1; i <= pageIndex; i++) {
                allPages.add(i);
            }
            report.addFinding(
                    new Finding(
                            Severity.INFO,
                            Category.DOCUMENT,
                            "MIXED_PAGE_SIZES",
                            pageSizeCounts.size()
                                    + " distinct page sizes/rotations — check they are intended",
                            allPages));
        }

        return report;
    }

    /** Areas from painted-content records: map key is the 1-based page. */
    private static void addPaintAreas(
            Finding finding, Map<Integer, List<PaintedArea>> areasByPage) {
        for (Map.Entry<Integer, List<PaintedArea>> e : areasByPage.entrySet()) {
            for (PaintedArea a : e.getValue()) {
                float[] b = a.bounds;
                finding.addArea(
                        new FindingArea(
                                e.getKey(),
                                b[0],
                                b[1],
                                Math.max(b[2] - b[0], 0.5f),
                                Math.max(b[3] - b[1], 0.5f),
                                a.label));
            }
        }
    }

    /**
     * The strip between the required bleed line and the declared bleed edge on one deficient side —
     * the zone that should be painted but falls outside the declared bleed.
     */
    private static FindingArea bleedGapArea(
            int pageNum,
            PDRectangle trim,
            PDRectangle bleed,
            int side,
            float actualPt,
            float requiredPt) {
        float gap = requiredPt - actualPt;
        String label =
                sideLabel(side)
                        + ": "
                        + Math.round(actualPt / PT_PER_MM * 10) / 10f
                        + " mm declared";
        return switch (side) {
            case 0 -> // left
                    new FindingArea(
                            pageNum,
                            trim.getLowerLeftX() - requiredPt,
                            bleed.getLowerLeftY(),
                            gap,
                            bleed.getHeight(),
                            label);
            case 1 -> // bottom
                    new FindingArea(
                            pageNum,
                            bleed.getLowerLeftX(),
                            trim.getLowerLeftY() - requiredPt,
                            bleed.getWidth(),
                            gap,
                            label);
            case 2 -> // right
                    new FindingArea(
                            pageNum,
                            trim.getUpperRightX() + actualPt,
                            bleed.getLowerLeftY(),
                            gap,
                            bleed.getHeight(),
                            label);
            default -> // top
                    new FindingArea(
                            pageNum,
                            bleed.getLowerLeftX(),
                            trim.getUpperRightY() + actualPt,
                            bleed.getWidth(),
                            gap,
                            label);
        };
    }

    private static String sideLabel(int side) {
        return switch (side) {
            case 0 -> "left";
            case 1 -> "bottom";
            case 2 -> "right";
            default -> "top";
        };
    }

    /** Bleed width on each side: left, bottom, right, top; null when bleed does not cover trim. */
    private static float[] bleedWidthPerSide(PDRectangle bleed, PDRectangle trim) {
        if (bleed == null || trim == null) {
            return null;
        }
        return new float[] {
            trim.getLowerLeftX() - bleed.getLowerLeftX(),
            trim.getLowerLeftY() - bleed.getLowerLeftY(),
            bleed.getUpperRightX() - trim.getUpperRightX(),
            bleed.getUpperRightY() - trim.getUpperRightY()
        };
    }

    private static boolean trimBoundsOverlap(PDRectangle trim, PDRectangle rect) {
        return rect.getLowerLeftX() < trim.getUpperRightX()
                && rect.getUpperRightX() > trim.getLowerLeftX()
                && rect.getLowerLeftY() < trim.getUpperRightY()
                && rect.getUpperRightY() > trim.getLowerLeftY();
    }

    private static boolean hasTransparencyGroup(PDPage page) {
        COSDictionary group = page.getCOSObject().getCOSDictionary(COSName.GROUP);
        return group != null && "Transparency".equals(group.getNameAsString(COSName.S));
    }

    /** Coverage of the bleed ring plus the page-space bounds of its unpainted runs. */
    private record BleedCoverage(float percent, List<FindingArea> whiteZones) {}

    /**
     * Renders the page at low resolution and measures how much of the ring between TrimBox and
     * BleedBox is actually painted, keeping a bounding box of the white pixels on each side band so
     * the report can point at the gap. Returns null when the area cannot be measured.
     */
    private static BleedCoverage bleedCoveragePercent(
            PDPage page, PDRectangle trim, PDRectangle bleed, PDFRenderer renderer, int pageIndex) {
        int rotation = page.getRotation();
        try {
            page.setRotation(0);
            BufferedImage img = renderer.renderImageWithDPI(pageIndex - 1, COVERAGE_DPI);
            PDRectangle crop = page.getCropBox();
            float scale = COVERAGE_DPI / 72f;

            int x0 =
                    Math.max(0, Math.round((bleed.getLowerLeftX() - crop.getLowerLeftX()) * scale));
            int x1 =
                    Math.min(
                            img.getWidth(),
                            Math.round((bleed.getUpperRightX() - crop.getLowerLeftX()) * scale));
            int y0 =
                    Math.max(
                            0,
                            Math.round((crop.getUpperRightY() - bleed.getUpperRightY()) * scale));
            int y1 =
                    Math.min(
                            img.getHeight(),
                            Math.round((crop.getUpperRightY() - bleed.getLowerLeftY()) * scale));
            int tx0 = Math.round((trim.getLowerLeftX() - crop.getLowerLeftX()) * scale);
            int tx1 = Math.round((trim.getUpperRightX() - crop.getLowerLeftX()) * scale);
            int ty0 = Math.round((crop.getUpperRightY() - trim.getUpperRightY()) * scale);
            int ty1 = Math.round((crop.getUpperRightY() - trim.getLowerLeftY()) * scale);

            // Band index: 0 left, 1 bottom, 2 right, 3 top — image y grows downward.
            // Corner pixels fold into the side bands since x is tested first.
            float[][] white = new float[4][];
            int total = 0;
            int painted = 0;
            for (int y = y0; y < y1; y += COVERAGE_STRIDE) {
                for (int x = x0; x < x1; x += COVERAGE_STRIDE) {
                    if (x >= tx0 && x < tx1 && y >= ty0 && y < ty1) {
                        continue;
                    }
                    total++;
                    int rgb = img.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    if (r < WHITE_RGB_THRESHOLD
                            || g < WHITE_RGB_THRESHOLD
                            || b < WHITE_RGB_THRESHOLD) {
                        painted++;
                    } else {
                        int band = x < tx0 ? 0 : x >= tx1 ? 2 : y >= ty1 ? 1 : 3;
                        grow(white, band, x, y);
                    }
                }
            }
            if (total == 0) {
                return null;
            }
            List<FindingArea> zones = new ArrayList<>();
            String[] names = {"left", "bottom", "right", "top"};
            for (int band = 0; band < white.length; band++) {
                float[] w = white[band];
                if (w == null) {
                    continue;
                }
                // Pixel bbox → page space (image y grows down, page y grows up).
                float px = crop.getLowerLeftX() + w[0] / scale;
                float py = crop.getUpperRightY() - (w[3] + COVERAGE_STRIDE) / scale;
                zones.add(
                        new FindingArea(
                                pageIndex,
                                px,
                                py,
                                (w[2] - w[0] + COVERAGE_STRIDE) / scale,
                                (w[3] - w[1] + COVERAGE_STRIDE) / scale,
                                names[band] + " unpainted"));
            }
            return new BleedCoverage(painted * 100f / total, zones);
        } catch (Exception e) {
            log.debug("Bleed coverage render failed on page {}", pageIndex, e);
            return null;
        } finally {
            page.setRotation(rotation);
        }
    }

    private static void grow(float[][] boxes, int band, float x, float y) {
        float[] b = boxes[band];
        if (b == null) {
            boxes[band] = new float[] {x, y, x, y};
        } else {
            b[0] = Math.min(b[0], x);
            b[1] = Math.min(b[1], y);
            b[2] = Math.max(b[2], x);
            b[3] = Math.max(b[3], y);
        }
    }

    private static int minInt(List<Integer> values) {
        int min = Integer.MAX_VALUE;
        for (int v : values) {
            min = Math.min(min, v);
        }
        return min;
    }
}
