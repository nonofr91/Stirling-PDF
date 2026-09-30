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
import stirling.software.SPDF.model.api.security.PrintPreflightReport.FontFact;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.PageSize;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Severity;
import stirling.software.SPDF.model.api.security.PrintPreflightRequest;
import stirling.software.SPDF.service.preflight.PreflightGraphicsEngine.FontUse;
import stirling.software.SPDF.service.preflight.PreflightGraphicsEngine.ImageUse;

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
        List<ImageUse> images = new ArrayList<>();
        List<Integer> lowResPages = new ArrayList<>();
        double minImageDpi = Double.MAX_VALUE;
        Map<Integer, List<Float>> hairlinesByPage = new LinkedHashMap<>();
        Set<Integer> transparencyPages = new TreeSet<>();
        Set<Integer> optionalContentPages = new TreeSet<>();
        Set<Integer> annotationInTrimPages = new TreeSet<>();
        Set<Integer> missingTrimPages = new TreeSet<>();
        Set<Integer> missingBleedPages = new TreeSet<>();
        Set<Integer> insufficientBleedPages = new TreeSet<>();
        Set<Integer> unpaintedBleedPages = new TreeSet<>();
        List<Integer> unpaintedCoverage = new ArrayList<>();
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
            }
            engine.getColorSpaceCounts()
                    .forEach((k, v) -> colorSpaceCounts.merge(k, v, Integer::sum));
            spotColors.addAll(engine.getSpotColors());
            for (ImageUse img : engine.getImages()) {
                images.add(img);
                if (!Double.isNaN(img.effectiveDpi)) {
                    minImageDpi = Math.min(minImageDpi, img.effectiveDpi);
                    if (img.effectiveDpi < request.getMinImageDpi()
                            && !lowResPages.contains(pageNum)) {
                        lowResPages.add(pageNum);
                    }
                }
            }
            if (!engine.getEffectiveStrokeWidths().isEmpty()) {
                List<Float> thin = new ArrayList<>();
                for (float w : engine.getEffectiveStrokeWidths()) {
                    if (w < request.getHairlineThresholdPt()) {
                        thin.add(w);
                    }
                }
                if (!thin.isEmpty()) {
                    hairlinesByPage.put(pageNum, thin);
                }
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
            if (!hasTrim) {
                missingTrimPages.add(pageNum);
            }
            if (!hasBleed || bleedWidthPerSide(bleed, trim) == null) {
                missingBleedPages.add(pageNum);
            } else {
                float[] sides = bleedWidthPerSide(bleed, trim);
                boolean insufficient = false;
                for (float s : sides) {
                    if (s < requiredBleedPt - BLEED_TOLERANCE_PT) {
                        insufficient = true;
                        break;
                    }
                }
                if (insufficient) {
                    insufficientBleedPages.add(pageNum);
                } else if (renderer != null && requiredBleedPt > 0) {
                    float coverage = bleedCoveragePercent(page, trim, bleed, renderer, pageNum);
                    if (coverage >= 0 && coverage < COVERAGE_MIN_PERCENT) {
                        unpaintedBleedPages.add(pageNum);
                        unpaintedCoverage.add(Math.round(coverage));
                    }
                }
            }

            if (hasTrim) {
                for (PDAnnotation annotation : page.getAnnotations()) {
                    PDRectangle rect = annotation.getRectangle();
                    if (rect != null && trimBoundsOverlap(trim, rect)) {
                        annotationInTrimPages.add(pageNum);
                        break;
                    }
                }
            }
        }

        Facts facts = report.getFacts();
        for (FontUse f : fonts.values()) {
            FontFact fact = new FontFact(f.name, f.subType, f.embedded, f.type3);
            fact.setPages(new ArrayList<>(f.pages));
            facts.getFonts().add(fact);
        }
        facts.setColorSpaces(new ArrayList<>(colorSpaceCounts.keySet()));
        facts.setSpotColors(new ArrayList<>(spotColors));
        facts.setImageCount(images.size());
        facts.setLowResImageCount(lowResPages.size());
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
        for (FontUse f : fonts.values()) {
            if (!f.embedded) {
                unembeddedNames.add(f.name);
                for (int p : f.pages) {
                    if (!unembeddedPages.contains(p)) {
                        unembeddedPages.add(p);
                    }
                }
            }
            if (f.type3) {
                type3Names.add(f.name);
                for (int p : f.pages) {
                    if (!type3Pages.contains(p)) {
                        type3Pages.add(p);
                    }
                }
            }
        }
        if (!unembeddedNames.isEmpty()) {
            report.addFinding(
                    new Finding(
                            Severity.ERROR,
                            Category.FONTS,
                            "FONT_NOT_EMBEDDED",
                            "Fonts not embedded: "
                                    + String.join(", ", unembeddedNames)
                                    + ". Print output cannot be guaranteed without them",
                            unembeddedPages));
        }
        if (!type3Names.isEmpty()) {
            report.addFinding(
                    new Finding(
                            Severity.WARNING,
                            Category.FONTS,
                            "FONT_TYPE3",
                            "Type 3 fonts in use: "
                                    + String.join(", ", type3Names)
                                    + " — they may print as bitmaps or be refused",
                            type3Pages));
        }

        boolean rgbUsed =
                colorSpaceCounts.keySet().stream()
                        .anyMatch(l -> l.contains("RGB") || l.startsWith("Indexed over DeviceRGB"));
        if (rgbUsed) {
            report.addFinding(
                    new Finding(
                            Severity.WARNING,
                            Category.COLOR,
                            "COLOR_RGB_USED",
                            "RGB content is painted — offset printing needs CMYK; convert or accept"
                                    + " a color shift",
                            null));
        }
        if (!spotColors.isEmpty()) {
            report.addFinding(
                    new Finding(
                            Severity.INFO,
                            Category.COLOR,
                            "COLOR_SPOT",
                            "Spot colors in use: " + String.join(", ", spotColors),
                            null));
        }

        if (!lowResPages.isEmpty()) {
            report.addFinding(
                    new Finding(
                            Severity.WARNING,
                            Category.IMAGES,
                            "IMAGE_LOW_RES",
                            lowResPages.size()
                                    + " page(s) contain images below "
                                    + request.getMinImageDpi()
                                    + " dpi effective (lowest: "
                                    + Math.round(minImageDpi)
                                    + " dpi)",
                            lowResPages));
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
            report.addFinding(
                    new Finding(
                            Severity.ERROR,
                            Category.GEOMETRY,
                            "BLEED_MISSING",
                            "No BleedBox beyond the trim — cutting tolerance will expose white"
                                    + " edges",
                            new ArrayList<>(missingBleedPages)));
        }
        if (!insufficientBleedPages.isEmpty()) {
            report.addFinding(
                    new Finding(
                            Severity.ERROR,
                            Category.GEOMETRY,
                            "BLEED_INSUFFICIENT",
                            "Bleed is under "
                                    + request.getRequiredBleedMm()
                                    + " mm on at least one side",
                            new ArrayList<>(insufficientBleedPages)));
        }
        if (!unpaintedBleedPages.isEmpty()) {
            report.addFinding(
                    new Finding(
                            Severity.WARNING,
                            Category.GEOMETRY,
                            "BLEED_UNPAINTED",
                            "Bleed area declared but not painted — as low as "
                                    + minInt(unpaintedCoverage)
                                    + "% coverage; white slivers may show after trimming",
                            new ArrayList<>(unpaintedBleedPages)));
        }
        if (!annotationInTrimPages.isEmpty()) {
            report.addFinding(
                    new Finding(
                            Severity.WARNING,
                            Category.CONTENT,
                            "ANNOTATION_IN_TRIM",
                            "Annotations sit inside the trim area and may print",
                            new ArrayList<>(annotationInTrimPages)));
        }
        if (!hairlinesByPage.isEmpty()) {
            double min = Double.MAX_VALUE;
            for (List<Float> ws : hairlinesByPage.values()) {
                for (float w : ws) {
                    min = Math.min(min, w);
                }
            }
            report.addFinding(
                    new Finding(
                            Severity.WARNING,
                            Category.CONTENT,
                            "HAIRLINE",
                            "Strokes thinner than "
                                    + request.getHairlineThresholdPt()
                                    + " pt (thinnest: "
                                    + String.format("%.3f", min)
                                    + " pt) may drop out in print",
                            new ArrayList<>(hairlinesByPage.keySet())));
        }
        if (transparency) {
            report.addFinding(
                    new Finding(
                            Severity.INFO,
                            Category.CONTENT,
                            "TRANSPARENCY",
                            "Live transparency present — flatten for PDF/X-1a workflows",
                            new ArrayList<>(transparencyPages)));
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

    /**
     * Renders the page at low resolution and measures how much of the ring between TrimBox and
     * BleedBox is actually painted. Returns a negative value when the area cannot be measured.
     */
    private static float bleedCoveragePercent(
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
                    }
                }
            }
            return total == 0 ? -1 : painted * 100f / total;
        } catch (Exception e) {
            log.debug("Bleed coverage render failed on page {}", pageIndex, e);
            return -1;
        } finally {
            page.setRotation(rotation);
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
