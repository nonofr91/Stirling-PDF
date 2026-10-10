package stirling.software.SPDF.service.preflight;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceN;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDICCBased;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup.RenderState;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.RenderDestination;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.model.api.security.PrintPreflightReport;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Category;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Facts;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Finding;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.FindingArea;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.FontFact;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.OutputIntentFact;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.PageSize;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Severity;
import stirling.software.SPDF.model.api.security.PrintPreflightRequest;
import stirling.software.SPDF.service.preflight.PreflightGraphicsEngine.FontUse;
import stirling.software.SPDF.service.preflight.PreflightGraphicsEngine.ImageUse;
import stirling.software.SPDF.service.preflight.PreflightGraphicsEngine.PaintedArea;
import stirling.software.SPDF.service.preflight.PreflightGraphicsEngine.StrokeUse;
import stirling.software.SPDF.service.preflight.PreflightGraphicsEngine.TextUse;

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

    /** Optional rendered-TAC collaborator; null keeps the painted-area approximation. */
    @Autowired(required = false)
    private RenderedInkCoverage renderedInkCoverage;

    void setRenderedInkCoverage(RenderedInkCoverage renderedInkCoverage) {
        this.renderedInkCoverage = renderedInkCoverage;
    }

    public PrintPreflightReport analyze(
            PDDocument document, String fileName, long fileSizeBytes, PrintPreflightRequest request)
            throws IOException {

        PrintPreflightReport report = new PrintPreflightReport();
        report.setFileName(fileName);
        report.setFileSizeBytes(fileSizeBytes);
        report.setPdfVersion(Float.toString(document.getVersion()));
        report.setPageCount(document.getNumberOfPages());

        Set<PreflightCheck> disabled = PreflightCheck.disabledSet(request.getDisabledChecks());
        ResourceBundle bundle = PreflightReportText.bundleFor(request);

        Map<String, FontUse> fonts = new LinkedHashMap<>();
        Map<String, Integer> colorSpaceCounts = new LinkedHashMap<>();
        Set<String> spotColors = new LinkedHashSet<>();
        Set<String> technicalSeparations = new LinkedHashSet<>();
        List<ImageUse> images = new ArrayList<>();
        Map<Integer, List<ImageUse>> lowResByPage = new LinkedHashMap<>();
        Map<Integer, List<ImageUse>> lowRes1BitByPage = new LinkedHashMap<>();
        Map<Integer, List<ImageUse>> oversampledByPage = new LinkedHashMap<>();
        double minImageDpi = Double.MAX_VALUE;
        double maxImageDpi = 0;
        Map<Integer, List<StrokeUse>> hairlinesByPage = new LinkedHashMap<>();
        Map<Integer, List<PaintedArea>> rgbAreasByPage = new LinkedHashMap<>();
        Map<Integer, List<PaintedArea>> spotAreasByPage = new LinkedHashMap<>();
        Map<Integer, List<PaintedArea>> alphaAreasByPage = new LinkedHashMap<>();
        Map<Integer, List<PaintedArea>> whiteOverprintByPage = new LinkedHashMap<>();
        Map<Integer, List<PaintedArea>> knockoutBlackByPage = new LinkedHashMap<>();
        Map<Integer, List<PaintedArea>> inkAreasByPage = new LinkedHashMap<>();
        Map<Integer, List<PaintedArea>> registrationByPage = new LinkedHashMap<>();
        Map<Integer, List<PaintedArea>> outsidePageByPage = new LinkedHashMap<>();
        Map<Integer, List<TextUse>> textUsesByPage = new LinkedHashMap<>();
        Map<Integer, List<FindingArea>> safetyMarginAreas = new LinkedHashMap<>();
        Set<Integer> transparencyPages = new TreeSet<>();
        Set<Integer> optionalContentPages = new TreeSet<>();
        Set<Integer> invisibleTextPages = new TreeSet<>();
        Set<Integer> patternPages = new TreeSet<>();
        Set<Integer> shadingPages = new TreeSet<>();
        Set<Integer> emptyPages = new TreeSet<>();
        Set<Integer> nonStandardUserUnitPages = new TreeSet<>();
        Set<Integer> cropBoxDiffersPages = new TreeSet<>();
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
        float minFontSize = Float.NaN;
        float maxInkCoverage = 0;
        boolean anyTrim = false;
        boolean anyBleed = false;
        boolean anyCrop = false;
        boolean anyArt = false;
        boolean transparency = false;
        boolean anyPattern = false;
        boolean anyShading = false;

        boolean coverageCheck =
                request.isCheckBleedCoverage()
                        && !disabled.contains(PreflightCheck.BLEED_UNPAINTED);
        PDFRenderer renderer = coverageCheck ? new PDFRenderer(document) : null;
        float requiredBleedPt = request.getRequiredBleedMm() * PT_PER_MM;
        float safetyMarginPt = request.getSafetyMarginMm() * PT_PER_MM;

        int pageIndex = 0;
        for (PDPage page : document.getPages()) {
            pageIndex++;
            final int pageNum = pageIndex;

            PreflightGraphicsEngine engine =
                    new PreflightGraphicsEngine(page, request.getMaxInkCoveragePercent());
            boolean parseFailed = false;
            try {
                engine.processPage(page);
            } catch (Exception e) {
                parseFailed = true;
                log.debug("Preflight content pass failed on page {}", pageNum, e);
                if (!disabled.contains(PreflightCheck.CONTENT_PARSE_ERROR)) {
                    report.addFinding(
                            new Finding(
                                    Severity.WARNING,
                                    Category.CONTENT,
                                    PreflightCheck.CONTENT_PARSE_ERROR.code(),
                                    PreflightReportText.msg(
                                            bundle, "finding.CONTENT_PARSE_ERROR", e.getMessage()),
                                    List.of(pageNum)));
                }
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
                    maxImageDpi = Math.max(maxImageDpi, img.effectiveDpi);
                    if (img.bitsPerComponent == 1) {
                        if (img.effectiveDpi < request.getMinImage1BitDpi()) {
                            lowRes1BitByPage
                                    .computeIfAbsent(pageNum, k -> new ArrayList<>())
                                    .add(img);
                        }
                    } else if (img.effectiveDpi < request.getMinImageDpi()) {
                        lowResByPage.computeIfAbsent(pageNum, k -> new ArrayList<>()).add(img);
                    }
                    if (img.effectiveDpi > request.getMaxImageDpi()) {
                        oversampledByPage.computeIfAbsent(pageNum, k -> new ArrayList<>()).add(img);
                    }
                }
            }
            mergePaintAreas(whiteOverprintByPage, pageNum, engine.getWhiteOverprintAreas());
            mergePaintAreas(knockoutBlackByPage, pageNum, engine.getKnockoutBlackAreas());
            mergePaintAreas(inkAreasByPage, pageNum, engine.getInkAreas());
            mergePaintAreas(registrationByPage, pageNum, engine.getRegistrationAreas());
            mergePaintAreas(outsidePageByPage, pageNum, engine.getOutsidePageAreas());
            if (!engine.getTextUses().isEmpty()) {
                textUsesByPage.put(pageNum, engine.getTextUses());
            }
            if (!Float.isNaN(engine.getMinFontSize())
                    && (Float.isNaN(minFontSize) || engine.getMinFontSize() < minFontSize)) {
                minFontSize = engine.getMinFontSize();
            }
            maxInkCoverage = Math.max(maxInkCoverage, engine.getMaxInkCoverage());
            if (engine.getPaintedOps() == 0 && !parseFailed) {
                // A failed pass proves nothing about what the page paints — only
                // CONTENT_PARSE_ERROR.
                emptyPages.add(pageNum);
            }
            if (engine.isInvisibleTextUsed()) {
                invisibleTextPages.add(pageNum);
            }
            if (engine.isPatternUsed()) {
                patternPages.add(pageNum);
                anyPattern = true;
            }
            if (engine.isShadingUsed()) {
                shadingPages.add(pageNum);
                anyShading = true;
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
            boolean hasCrop = page.getCOSObject().getItem(COSName.CROP_BOX) != null;
            boolean hasArt = page.getCOSObject().getItem(COSName.ART_BOX) != null;
            anyTrim |= hasTrim;
            anyBleed |= hasBleed;
            anyCrop |= hasCrop;
            anyArt |= hasArt;
            PDRectangle trim = page.getTrimBox();
            PDRectangle bleed = page.getBleedBox();
            PDRectangle cropBox = page.getCropBox();
            trimByPage.put(pageNum, trim);
            if (!rectEquals(cropBox, media)) {
                cropBoxDiffersPages.add(pageNum);
            }
            if (Math.abs(page.getUserUnit() - 1f) > 0.001f) {
                nonStandardUserUnitPages.add(pageNum);
            }
            if (hasTrim && !disabled.contains(PreflightCheck.SAFETY_MARGIN) && safetyMarginPt > 0) {
                collectSafetyMargin(
                        bundle, pageNum, trim, safetyMarginPt, engine, safetyMarginAreas);
            }
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
                                        bundle,
                                        pageNum,
                                        trim,
                                        bleed,
                                        side,
                                        sides[side],
                                        requiredBleedPt));
                    }
                }
                if (!gaps.isEmpty()) {
                    insufficientBleedAreas
                            .computeIfAbsent(pageNum, k -> new ArrayList<>())
                            .addAll(gaps);
                } else if (renderer != null && requiredBleedPt > 0) {
                    BleedCoverage coverage =
                            bleedCoveragePercent(bundle, page, trim, bleed, renderer, pageNum);
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
                    // An annotation not flagged for print can only be seen on screen.
                    if (!annotation.isPrinted()) {
                        continue;
                    }
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
        facts.setLowResImageCount(
                lowResByPage.values().stream().mapToInt(List::size).sum()
                        + lowRes1BitByPage.values().stream().mapToInt(List::size).sum());
        facts.setOversampledImageCount(
                oversampledByPage.values().stream().mapToInt(List::size).sum());
        if (minImageDpi != Double.MAX_VALUE) {
            facts.setMinEffectiveDpi(minImageDpi);
        }
        if (maxImageDpi > 0) {
            facts.setMaxEffectiveDpi(maxImageDpi);
        }
        facts.setMinFontSizeSeen(minFontSize);
        facts.setMaxInkCoverageSeen(maxInkCoverage);
        facts.setTransparencyUsed(transparency);
        facts.setPatternUsed(anyPattern);
        facts.setShadingUsed(anyShading);
        facts.setHasTrimBox(anyTrim);
        facts.setHasBleedBox(anyBleed);
        facts.setHasCropBox(anyCrop);
        facts.setHasArtBox(anyArt);
        facts.setEmptyPages(new ArrayList<>(emptyPages));
        facts.setInvisibleTextPages(new ArrayList<>(invisibleTextPages));
        facts.setRegistrationPaintPages(new ArrayList<>(registrationByPage.keySet()));
        facts.setNonStandardUserUnitPages(new ArrayList<>(nonStandardUserUnitPages));
        collectDocumentFacts(bundle, document, facts);
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
        if (!disabled.contains(PreflightCheck.FONT_NOT_EMBEDDED) && !unembeddedNames.isEmpty()) {
            Finding finding =
                    new Finding(
                            Severity.ERROR,
                            Category.FONTS,
                            PreflightCheck.FONT_NOT_EMBEDDED.code(),
                            PreflightReportText.msg(
                                    bundle,
                                    "finding.FONT_NOT_EMBEDDED",
                                    String.join(", ", unembeddedNames)),
                            unembeddedPages);
            unembeddedAreas.forEach(finding::addArea);
            report.addFinding(finding);
        }
        if (!disabled.contains(PreflightCheck.FONT_TYPE3) && !type3Names.isEmpty()) {
            Finding finding =
                    new Finding(
                            Severity.WARNING,
                            Category.FONTS,
                            PreflightCheck.FONT_TYPE3.code(),
                            PreflightReportText.msg(
                                    bundle, "finding.FONT_TYPE3", String.join(", ", type3Names)),
                            type3Pages);
            type3Areas.forEach(finding::addArea);
            report.addFinding(finding);
        }

        boolean rgbUsed =
                colorSpaceCounts.keySet().stream()
                        .anyMatch(l -> l.contains("RGB") || l.startsWith("Indexed over DeviceRGB"));
        if (rgbUsed && !disabled.contains(PreflightCheck.COLOR_RGB_USED)) {
            Finding finding =
                    new Finding(
                            Severity.WARNING,
                            Category.COLOR,
                            PreflightCheck.COLOR_RGB_USED.code(),
                            PreflightReportText.msg(bundle, "finding.COLOR_RGB_USED"),
                            new ArrayList<>(rgbAreasByPage.keySet()));
            addPaintAreas(finding, rgbAreasByPage);
            report.addFinding(finding);
        }
        if (!printSpots.isEmpty() && !disabled.contains(PreflightCheck.COLOR_SPOT)) {
            Finding finding =
                    new Finding(
                            Severity.INFO,
                            Category.COLOR,
                            PreflightCheck.COLOR_SPOT.code(),
                            PreflightReportText.msg(
                                    bundle, "finding.COLOR_SPOT", String.join(", ", printSpots)),
                            new ArrayList<>(spotAreasByPage.keySet()));
            addPaintAreas(finding, spotAreasByPage);
            report.addFinding(finding);
        }

        if (!lowResByPage.isEmpty() && !disabled.contains(PreflightCheck.IMAGE_LOW_RES)) {
            Finding finding =
                    new Finding(
                            Severity.WARNING,
                            Category.IMAGES,
                            PreflightCheck.IMAGE_LOW_RES.code(),
                            PreflightReportText.msg(
                                    bundle,
                                    "finding.IMAGE_LOW_RES",
                                    lowResByPage.size(),
                                    request.getMinImageDpi(),
                                    Math.round(minImageDpi)),
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

        if (!missingTrimPages.isEmpty() && !disabled.contains(PreflightCheck.TRIMBOX_MISSING)) {
            report.addFinding(
                    new Finding(
                            Severity.WARNING,
                            Category.GEOMETRY,
                            PreflightCheck.TRIMBOX_MISSING.code(),
                            PreflightReportText.msg(bundle, "finding.TRIMBOX_MISSING"),
                            new ArrayList<>(missingTrimPages)));
        }
        if (!missingBleedPages.isEmpty() && !disabled.contains(PreflightCheck.BLEED_MISSING)) {
            Finding finding =
                    new Finding(
                            Severity.ERROR,
                            Category.GEOMETRY,
                            PreflightCheck.BLEED_MISSING.code(),
                            PreflightReportText.msg(bundle, "finding.BLEED_MISSING"),
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
                                    PreflightReportText.msg(bundle, "label.trimEdge")));
                }
            }
            report.addFinding(finding);
        }
        if (!insufficientBleedAreas.isEmpty()
                && !disabled.contains(PreflightCheck.BLEED_INSUFFICIENT)) {
            Finding finding =
                    new Finding(
                            Severity.ERROR,
                            Category.GEOMETRY,
                            PreflightCheck.BLEED_INSUFFICIENT.code(),
                            PreflightReportText.msg(
                                    bundle,
                                    "finding.BLEED_INSUFFICIENT",
                                    request.getRequiredBleedMm()),
                            new ArrayList<>(insufficientBleedAreas.keySet()));
            insufficientBleedAreas.values().forEach(list -> list.forEach(finding::addArea));
            report.addFinding(finding);
        }
        if (!unpaintedBleedPages.isEmpty() && !disabled.contains(PreflightCheck.BLEED_UNPAINTED)) {
            Finding finding =
                    new Finding(
                            Severity.WARNING,
                            Category.GEOMETRY,
                            PreflightCheck.BLEED_UNPAINTED.code(),
                            PreflightReportText.msg(
                                    bundle, "finding.BLEED_UNPAINTED", minInt(unpaintedCoverage)),
                            new ArrayList<>(unpaintedBleedPages));
            unpaintedBleedAreas.values().forEach(list -> list.forEach(finding::addArea));
            report.addFinding(finding);
        }
        if (!annotationAreasByPage.isEmpty()
                && !disabled.contains(PreflightCheck.ANNOTATION_IN_TRIM)) {
            Finding finding =
                    new Finding(
                            Severity.WARNING,
                            Category.CONTENT,
                            PreflightCheck.ANNOTATION_IN_TRIM.code(),
                            PreflightReportText.msg(bundle, "finding.ANNOTATION_IN_TRIM"),
                            new ArrayList<>(annotationAreasByPage.keySet()));
            annotationAreasByPage.values().forEach(list -> list.forEach(finding::addArea));
            report.addFinding(finding);
        }
        if (!hairlinesByPage.isEmpty() && !disabled.contains(PreflightCheck.HAIRLINE)) {
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
                            PreflightCheck.HAIRLINE.code(),
                            PreflightReportText.msg(
                                    bundle,
                                    "finding.HAIRLINE",
                                    request.getHairlineThresholdPt(),
                                    String.format(Locale.ROOT, "%.3f", min)),
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
                                        String.format(Locale.ROOT, "%.2f pt", s.widthPt)));
                    }
                }
            }
            report.addFinding(finding);
        }
        if (transparency && !disabled.contains(PreflightCheck.TRANSPARENCY)) {
            Finding finding =
                    new Finding(
                            Severity.INFO,
                            Category.CONTENT,
                            PreflightCheck.TRANSPARENCY.code(),
                            PreflightReportText.msg(bundle, "finding.TRANSPARENCY"),
                            new ArrayList<>(transparencyPages));
            addPaintAreas(finding, alphaAreasByPage);
            report.addFinding(finding);
        }
        if (!optionalContentPages.isEmpty()
                && !disabled.contains(PreflightCheck.OPTIONAL_CONTENT)) {
            report.addFinding(
                    new Finding(
                            Severity.INFO,
                            Category.CONTENT,
                            PreflightCheck.OPTIONAL_CONTENT.code(),
                            PreflightReportText.msg(bundle, "finding.OPTIONAL_CONTENT"),
                            new ArrayList<>(optionalContentPages)));
        }
        if (pageSizeCounts.size() > 1 && !disabled.contains(PreflightCheck.MIXED_PAGE_SIZES)) {
            List<Integer> allPages = new ArrayList<>();
            for (int i = 1; i <= pageIndex; i++) {
                allPages.add(i);
            }
            report.addFinding(
                    new Finding(
                            Severity.INFO,
                            Category.DOCUMENT,
                            PreflightCheck.MIXED_PAGE_SIZES.code(),
                            PreflightReportText.msg(
                                    bundle, "finding.MIXED_PAGE_SIZES", pageSizeCounts.size()),
                            allPages));
        }

        if (!whiteOverprintByPage.isEmpty() && !disabled.contains(PreflightCheck.OVERPRINT_WHITE)) {
            Finding finding =
                    new Finding(
                            Severity.ERROR,
                            Category.COLOR,
                            PreflightCheck.OVERPRINT_WHITE.code(),
                            PreflightReportText.msg(bundle, "finding.OVERPRINT_WHITE"),
                            new ArrayList<>(whiteOverprintByPage.keySet()));
            addPaintAreas(finding, whiteOverprintByPage);
            report.addFinding(finding);
        }
        if (!knockoutBlackByPage.isEmpty() && !disabled.contains(PreflightCheck.OVERPRINT_BLACK)) {
            Finding finding =
                    new Finding(
                            Severity.WARNING,
                            Category.COLOR,
                            PreflightCheck.OVERPRINT_BLACK.code(),
                            PreflightReportText.msg(bundle, "finding.OVERPRINT_BLACK"),
                            new ArrayList<>(knockoutBlackByPage.keySet()));
            addPaintAreas(finding, knockoutBlackByPage);
            report.addFinding(finding);
        }
        collectTextFindings(report, disabled, request, textUsesByPage, bundle);
        if (!safetyMarginAreas.isEmpty() && !disabled.contains(PreflightCheck.SAFETY_MARGIN)) {
            Finding finding =
                    new Finding(
                            Severity.WARNING,
                            Category.GEOMETRY,
                            PreflightCheck.SAFETY_MARGIN.code(),
                            PreflightReportText.msg(
                                    bundle, "finding.SAFETY_MARGIN", request.getSafetyMarginMm()),
                            new ArrayList<>(safetyMarginAreas.keySet()));
            safetyMarginAreas.values().forEach(list -> list.forEach(finding::addArea));
            report.addFinding(finding);
        }
        if (!emptyPages.isEmpty() && !disabled.contains(PreflightCheck.EMPTY_PAGE)) {
            report.addFinding(
                    new Finding(
                            Severity.INFO,
                            Category.DOCUMENT,
                            PreflightCheck.EMPTY_PAGE.code(),
                            PreflightReportText.msg(
                                    bundle, "finding.EMPTY_PAGE", emptyPages.size()),
                            new ArrayList<>(emptyPages)));
        }
        if (!oversampledByPage.isEmpty() && !disabled.contains(PreflightCheck.IMAGE_OVERSAMPLED)) {
            Finding finding =
                    new Finding(
                            Severity.INFO,
                            Category.IMAGES,
                            PreflightCheck.IMAGE_OVERSAMPLED.code(),
                            PreflightReportText.msg(
                                    bundle,
                                    "finding.IMAGE_OVERSAMPLED",
                                    oversampledByPage.size(),
                                    request.getMaxImageDpi(),
                                    Math.round(maxImageDpi)),
                            new ArrayList<>(oversampledByPage.keySet()));
            addImageAreas(finding, oversampledByPage, " dpi");
            report.addFinding(finding);
        }
        if (!lowRes1BitByPage.isEmpty() && !disabled.contains(PreflightCheck.IMAGE_1BIT_LOW_RES)) {
            double min1Bit = Double.MAX_VALUE;
            for (List<ImageUse> list : lowRes1BitByPage.values()) {
                for (ImageUse img : list) {
                    min1Bit = Math.min(min1Bit, img.effectiveDpi);
                }
            }
            Finding finding =
                    new Finding(
                            Severity.WARNING,
                            Category.IMAGES,
                            PreflightCheck.IMAGE_1BIT_LOW_RES.code(),
                            PreflightReportText.msg(
                                    bundle,
                                    "finding.IMAGE_1BIT_LOW_RES",
                                    request.getMinImage1BitDpi(),
                                    Math.round(min1Bit)),
                            new ArrayList<>(lowRes1BitByPage.keySet()));
            addImageAreas(finding, lowRes1BitByPage, " dpi");
            report.addFinding(finding);
        }
        // When the rendered pass is on and Ghostscript answers, its composite measurement is
        // authoritative: paint hidden under later knockouts or stacked overprints reads true.
        RenderedInkCoverage.Result renderedTac = null;
        if (request.isRenderedInkCoverage()
                && renderedInkCoverage != null
                && !disabled.contains(PreflightCheck.INK_COVERAGE_HIGH)
                && !disabled.contains(PreflightCheck.INK_COVERAGE_HIGH_RENDERED)) {
            renderedTac = renderedInkCoverage.measure(document, request.getMaxInkCoveragePercent());
        }
        if (renderedTac != null) {
            if (!renderedTac.areasByPage().isEmpty()) {
                List<Integer> pages = new ArrayList<>(renderedTac.areasByPage().keySet());
                Finding finding =
                        new Finding(
                                Severity.WARNING,
                                Category.COLOR,
                                PreflightCheck.INK_COVERAGE_HIGH_RENDERED.code(),
                                PreflightReportText.msg(
                                        bundle,
                                        "finding.INK_COVERAGE_HIGH_RENDERED",
                                        request.getMaxInkCoveragePercent(),
                                        Math.round(renderedTac.peakPercent())),
                                pages);
                for (List<FindingArea> areas : renderedTac.areasByPage().values()) {
                    for (FindingArea area : areas) {
                        finding.addArea(area);
                    }
                }
                report.addFinding(finding);
            }
        }
        List<Map.Entry<Integer, List<PaintedArea>>> inkHits = new ArrayList<>();
        float maxTacHit = 0;
        if (renderedTac == null) {
            for (Map.Entry<Integer, List<PaintedArea>> e : inkAreasByPage.entrySet()) {
                List<PaintedArea> over = new ArrayList<>();
                for (PaintedArea a : e.getValue()) {
                    if (a.totalInk > request.getMaxInkCoveragePercent()) {
                        over.add(a);
                        maxTacHit = Math.max(maxTacHit, a.totalInk);
                    }
                }
                if (!over.isEmpty()) {
                    inkHits.add(Map.entry(e.getKey(), over));
                }
            }
        }
        if (!inkHits.isEmpty() && !disabled.contains(PreflightCheck.INK_COVERAGE_HIGH)) {
            List<Integer> pages = new ArrayList<>();
            Finding finding =
                    new Finding(
                            Severity.WARNING,
                            Category.COLOR,
                            PreflightCheck.INK_COVERAGE_HIGH.code(),
                            PreflightReportText.msg(
                                    bundle,
                                    "finding.INK_COVERAGE_HIGH",
                                    request.getMaxInkCoveragePercent(),
                                    Math.round(maxTacHit)),
                            pages);
            for (Map.Entry<Integer, List<PaintedArea>> e : inkHits) {
                pages.add(e.getKey());
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
            report.addFinding(finding);
        }
        List<List<String>> spotAliases = spotAliasGroups(printSpots);
        if (!spotAliases.isEmpty() && !disabled.contains(PreflightCheck.SPOT_ALIAS)) {
            List<String> lines = new ArrayList<>();
            for (List<String> group : spotAliases) {
                lines.add(String.join(" ≈ ", group));
            }
            report.addFinding(
                    new Finding(
                            Severity.WARNING,
                            Category.COLOR,
                            PreflightCheck.SPOT_ALIAS.code(),
                            PreflightReportText.msg(
                                    bundle, "finding.SPOT_ALIAS", String.join("; ", lines)),
                            new ArrayList<>(spotAreasByPage.keySet())));
        }
        if (request.getMaxSpotCount() > 0
                && printSpots.size() > request.getMaxSpotCount()
                && !disabled.contains(PreflightCheck.SPOT_COUNT)) {
            report.addFinding(
                    new Finding(
                            Severity.INFO,
                            Category.COLOR,
                            PreflightCheck.SPOT_COUNT.code(),
                            PreflightReportText.msg(
                                    bundle,
                                    "finding.SPOT_COUNT",
                                    printSpots.size(),
                                    request.getMaxSpotCount()),
                            new ArrayList<>(spotAreasByPage.keySet())));
        }
        if (!registrationByPage.isEmpty()
                && !disabled.contains(PreflightCheck.REGISTRATION_PAINT)) {
            Finding finding =
                    new Finding(
                            Severity.INFO,
                            Category.COLOR,
                            PreflightCheck.REGISTRATION_PAINT.code(),
                            PreflightReportText.msg(bundle, "finding.REGISTRATION_PAINT"),
                            new ArrayList<>(registrationByPage.keySet()));
            addPaintAreas(finding, registrationByPage);
            report.addFinding(finding);
        }
        if (!invisibleTextPages.isEmpty() && !disabled.contains(PreflightCheck.INVISIBLE_TEXT)) {
            report.addFinding(
                    new Finding(
                            Severity.INFO,
                            Category.CONTENT,
                            PreflightCheck.INVISIBLE_TEXT.code(),
                            PreflightReportText.msg(bundle, "finding.INVISIBLE_TEXT"),
                            new ArrayList<>(invisibleTextPages)));
        }
        if (!patternPages.isEmpty() && !disabled.contains(PreflightCheck.PATTERN_USED)) {
            report.addFinding(
                    new Finding(
                            Severity.INFO,
                            Category.COLOR,
                            PreflightCheck.PATTERN_USED.code(),
                            PreflightReportText.msg(bundle, "finding.PATTERN_USED"),
                            new ArrayList<>(patternPages)));
        }
        if (!shadingPages.isEmpty() && !disabled.contains(PreflightCheck.SHADING_USED)) {
            report.addFinding(
                    new Finding(
                            Severity.INFO,
                            Category.COLOR,
                            PreflightCheck.SHADING_USED.code(),
                            PreflightReportText.msg(bundle, "finding.SHADING_USED"),
                            new ArrayList<>(shadingPages)));
        }
        if (!outsidePageByPage.isEmpty()
                && !disabled.contains(PreflightCheck.OBJECT_OUTSIDE_PAGE)) {
            Finding finding =
                    new Finding(
                            Severity.INFO,
                            Category.GEOMETRY,
                            PreflightCheck.OBJECT_OUTSIDE_PAGE.code(),
                            PreflightReportText.msg(bundle, "finding.OBJECT_OUTSIDE_PAGE"),
                            new ArrayList<>(outsidePageByPage.keySet()));
            addPaintAreas(finding, outsidePageByPage);
            report.addFinding(finding);
        }
        if (facts.getOutputIntent() == null
                && !disabled.contains(PreflightCheck.OUTPUT_INTENT_MISSING)) {
            report.addFinding(
                    new Finding(
                            Severity.WARNING,
                            Category.COLOR,
                            PreflightCheck.OUTPUT_INTENT_MISSING.code(),
                            PreflightReportText.msg(bundle, "finding.OUTPUT_INTENT_MISSING"),
                            null));
        }
        if (facts.getEmbeddedFileCount() > 0 && !disabled.contains(PreflightCheck.EMBEDDED_FILES)) {
            report.addFinding(
                    new Finding(
                            Severity.WARNING,
                            Category.DOCUMENT,
                            PreflightCheck.EMBEDDED_FILES.code(),
                            PreflightReportText.msg(
                                    bundle, "finding.EMBEDDED_FILES", facts.getEmbeddedFileCount()),
                            null));
        }
        if (facts.isHasAcroForm() && !disabled.contains(PreflightCheck.FORM_FIELDS)) {
            report.addFinding(
                    new Finding(
                            Severity.WARNING,
                            Category.DOCUMENT,
                            PreflightCheck.FORM_FIELDS.code(),
                            PreflightReportText.msg(
                                    bundle, "finding.FORM_FIELDS", facts.getFormFieldCount()),
                            null));
        }
        if (facts.isHasXfa() && !disabled.contains(PreflightCheck.XFA_FORM)) {
            report.addFinding(
                    new Finding(
                            Severity.WARNING,
                            Category.DOCUMENT,
                            PreflightCheck.XFA_FORM.code(),
                            PreflightReportText.msg(bundle, "finding.XFA_FORM"),
                            null));
        }
        if (facts.getSignatureCount() > 0 && !disabled.contains(PreflightCheck.SIGNATURES)) {
            report.addFinding(
                    new Finding(
                            Severity.INFO,
                            Category.DOCUMENT,
                            PreflightCheck.SIGNATURES.code(),
                            PreflightReportText.msg(
                                    bundle, "finding.SIGNATURES", facts.getSignatureCount()),
                            null));
        }
        if (facts.isHasJavascript() && !disabled.contains(PreflightCheck.JAVASCRIPT)) {
            report.addFinding(
                    new Finding(
                            Severity.INFO,
                            Category.DOCUMENT,
                            PreflightCheck.JAVASCRIPT.code(),
                            PreflightReportText.msg(bundle, "finding.JAVASCRIPT"),
                            null));
        }
        if (!nonStandardUserUnitPages.isEmpty() && !disabled.contains(PreflightCheck.USER_UNIT)) {
            report.addFinding(
                    new Finding(
                            Severity.WARNING,
                            Category.GEOMETRY,
                            PreflightCheck.USER_UNIT.code(),
                            PreflightReportText.msg(bundle, "finding.USER_UNIT"),
                            new ArrayList<>(nonStandardUserUnitPages)));
        }
        if (!facts.getLayersDisabledForPrint().isEmpty()
                && !disabled.contains(PreflightCheck.LAYERS_PRINT_OFF)) {
            report.addFinding(
                    new Finding(
                            Severity.INFO,
                            Category.CONTENT,
                            PreflightCheck.LAYERS_PRINT_OFF.code(),
                            PreflightReportText.msg(
                                    bundle,
                                    "finding.LAYERS_PRINT_OFF",
                                    String.join(", ", facts.getLayersDisabledForPrint())),
                            new ArrayList<>(optionalContentPages)));
        }
        if (!cropBoxDiffersPages.isEmpty() && !disabled.contains(PreflightCheck.CROPBOX_NE_MEDIA)) {
            report.addFinding(
                    new Finding(
                            Severity.INFO,
                            Category.GEOMETRY,
                            PreflightCheck.CROPBOX_NE_MEDIA.code(),
                            PreflightReportText.msg(bundle, "finding.CROPBOX_NE_MEDIA"),
                            new ArrayList<>(cropBoxDiffersPages)));
        }

        return report;
    }

    private static void mergePaintAreas(
            Map<Integer, List<PaintedArea>> byPage, int pageNum, List<PaintedArea> areas) {
        if (!areas.isEmpty()) {
            byPage.computeIfAbsent(pageNum, k -> new ArrayList<>()).addAll(areas);
        }
    }

    private static void addImageAreas(
            Finding finding, Map<Integer, List<ImageUse>> byPage, String unit) {
        for (Map.Entry<Integer, List<ImageUse>> e : byPage.entrySet()) {
            for (ImageUse img : e.getValue()) {
                if (img.bounds != null) {
                    finding.addArea(
                            new FindingArea(
                                    e.getKey(),
                                    img.bounds[0],
                                    img.bounds[1],
                                    Math.max(img.bounds[2] - img.bounds[0], 0.5f),
                                    Math.max(img.bounds[3] - img.bounds[1], 0.5f),
                                    Math.round(img.effectiveDpi) + unit));
                }
            }
        }
    }

    /** Text findings that need the request thresholds — small size and rich-black colour. */
    private static void collectTextFindings(
            PrintPreflightReport report,
            Set<PreflightCheck> disabled,
            PrintPreflightRequest request,
            Map<Integer, List<TextUse>> textUsesByPage,
            ResourceBundle bundle) {
        Map<Integer, List<TextUse>> smallByPage = new LinkedHashMap<>();
        Map<Integer, List<TextUse>> richByPage = new LinkedHashMap<>();
        float smallest = Float.MAX_VALUE;
        for (Map.Entry<Integer, List<TextUse>> e : textUsesByPage.entrySet()) {
            for (TextUse t : e.getValue()) {
                if (t.fontSize < request.getMinFontSizePt()) {
                    smallByPage.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(t);
                    smallest = Math.min(smallest, t.fontSize);
                }
                if (isRichBlackText(t.color)) {
                    richByPage.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(t);
                }
            }
        }
        if (!smallByPage.isEmpty() && !disabled.contains(PreflightCheck.TEXT_SMALL)) {
            Finding finding =
                    new Finding(
                            Severity.WARNING,
                            Category.CONTENT,
                            PreflightCheck.TEXT_SMALL.code(),
                            PreflightReportText.msg(
                                    bundle,
                                    "finding.TEXT_SMALL",
                                    request.getMinFontSizePt(),
                                    String.format(Locale.ROOT, "%.1f", smallest)),
                            new ArrayList<>(smallByPage.keySet()));
            addTextAreas(finding, smallByPage);
            report.addFinding(finding);
        }
        if (!richByPage.isEmpty() && !disabled.contains(PreflightCheck.TEXT_RICH_BLACK)) {
            Finding finding =
                    new Finding(
                            Severity.WARNING,
                            Category.COLOR,
                            PreflightCheck.TEXT_RICH_BLACK.code(),
                            PreflightReportText.msg(bundle, "finding.TEXT_RICH_BLACK"),
                            new ArrayList<>(richByPage.keySet()));
            addTextAreas(finding, richByPage);
            report.addFinding(finding);
        }
    }

    private static void addTextAreas(Finding finding, Map<Integer, List<TextUse>> byPage) {
        for (Map.Entry<Integer, List<TextUse>> e : byPage.entrySet()) {
            for (TextUse t : e.getValue()) {
                if (t.bounds != null) {
                    finding.addArea(
                            new FindingArea(
                                    e.getKey(),
                                    t.bounds[0],
                                    t.bounds[1],
                                    Math.max(t.bounds[2] - t.bounds[0], 0.5f),
                                    Math.max(t.bounds[3] - t.bounds[1], 0.5f),
                                    String.format(Locale.ROOT, "%.1f pt", t.fontSize)));
                }
            }
        }
    }

    /**
     * Text that lands on more than one plate: K-plate black plus a chromatic underlay (rich black),
     * composite CMY black, multi-separation DeviceN, or dark RGB which converts to four plates.
     * Fine for display, fragile at small sizes.
     */
    private static boolean isRichBlackText(PDColor color) {
        if (color == null) {
            return false;
        }
        PDColorSpace cs = color.getColorSpace();
        float[] c = color.getComponents();
        if (cs instanceof PDDeviceCMYK
                || (cs instanceof PDICCBased icc && icc.getNumberOfComponents() == 4)) {
            if (c.length >= 4 && c[3] >= 0.4f) {
                return c[0] >= 0.15f || c[1] >= 0.15f || c[2] >= 0.15f;
            }
            return c.length >= 3 && c[0] >= 0.3f && c[1] >= 0.3f && c[2] >= 0.3f;
        }
        if (cs instanceof PDDeviceN) {
            int nonZero = 0;
            float sum = 0;
            for (float v : c) {
                if (v > 0.15f) {
                    nonZero++;
                }
                sum += v;
            }
            return nonZero >= 2 && sum > 0.8f;
        }
        if (cs instanceof PDDeviceRGB
                || (cs instanceof PDICCBased icc && icc.getNumberOfComponents() == 3)) {
            return c.length >= 3 && c[0] < 0.35f && c[1] < 0.35f && c[2] < 0.35f;
        }
        return false;
    }

    /**
     * Painted bounds fully inside the trim (bleed elements cross the edge) but closer than the
     * safety margin to it — the zone where cutting tolerance can bite.
     */
    private static void collectSafetyMargin(
            ResourceBundle bundle,
            int pageNum,
            PDRectangle trim,
            float marginPt,
            PreflightGraphicsEngine engine,
            Map<Integer, List<FindingArea>> out) {
        List<FindingArea> hits = new ArrayList<>();
        for (PaintedArea a : engine.getPaintAreas()) {
            if (!a.technical && a.bounds != null) {
                addIfNearEdge(bundle, pageNum, a.bounds, a.label, trim, marginPt, hits);
            }
        }
        for (ImageUse img : engine.getImages()) {
            if (!img.technical && img.bounds != null) {
                addIfNearEdge(
                        bundle, pageNum, img.bounds, img.colorSpaceLabel, trim, marginPt, hits);
            }
        }
        for (TextUse t : engine.getTextUses()) {
            if (t.bounds != null) {
                addIfNearEdge(
                        bundle,
                        pageNum,
                        t.bounds,
                        PreflightReportText.msg(
                                bundle,
                                "label.ptText",
                                String.format(Locale.ROOT, "%.1f", t.fontSize)),
                        trim,
                        marginPt,
                        hits);
            }
        }
        if (!hits.isEmpty()) {
            out.put(pageNum, hits);
        }
    }

    private static void addIfNearEdge(
            ResourceBundle bundle,
            int page,
            float[] b,
            String label,
            PDRectangle trim,
            float marginPt,
            List<FindingArea> hits) {
        if (hits.size() >= 64
                || b[0] < trim.getLowerLeftX()
                || b[2] > trim.getUpperRightX()
                || b[1] < trim.getLowerLeftY()
                || b[3] > trim.getUpperRightY()) {
            return;
        }
        float dist =
                Math.min(
                        Math.min(b[0] - trim.getLowerLeftX(), trim.getUpperRightX() - b[2]),
                        Math.min(b[1] - trim.getLowerLeftY(), trim.getUpperRightY() - b[3]));
        if (dist >= marginPt) {
            return;
        }
        String detail =
                PreflightReportText.msg(
                        bundle, "label.nearTrim", Math.round(dist / PT_PER_MM * 10) / 10f);
        if (label != null) {
            detail += " · " + label;
        }
        hits.add(
                new FindingArea(
                        page,
                        b[0],
                        b[1],
                        Math.max(b[2] - b[0], 0.5f),
                        Math.max(b[3] - b[1], 0.5f),
                        detail));
    }

    private static boolean rectEquals(PDRectangle a, PDRectangle b) {
        return a != null
                && b != null
                && Math.abs(a.getLowerLeftX() - b.getLowerLeftX()) < 0.5f
                && Math.abs(a.getLowerLeftY() - b.getLowerLeftY()) < 0.5f
                && Math.abs(a.getUpperRightX() - b.getUpperRightX()) < 0.5f
                && Math.abs(a.getUpperRightY() - b.getUpperRightY()) < 0.5f;
    }

    /**
     * Spot names that differ only in spelling — "PANTONE 485 C" vs "pms-485cv" — each become a
     * separate plate although the ink is the same.
     */
    static List<List<String>> spotAliasGroups(Set<String> spotColors) {
        Map<String, Set<String>> byNormalized = new LinkedHashMap<>();
        for (String name : spotColors) {
            String norm = normalizeSpotName(name);
            if (!norm.isEmpty()) {
                byNormalized.computeIfAbsent(norm, k -> new LinkedHashSet<>()).add(name);
            }
        }
        List<List<String>> groups = new ArrayList<>();
        for (Set<String> raws : byNormalized.values()) {
            if (raws.size() > 1) {
                groups.add(new ArrayList<>(raws));
            }
        }
        return groups;
    }

    private static String normalizeSpotName(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
        n = n.replaceFirst("^(pantone|pms|hks|toyo|dic|focoltone|ral)", "");
        n = n.replaceFirst("(cvu?|up|uc|coated|uncoated|matte?|gloss|c|u|m|k)$", "");
        return n;
    }

    /**
     * Document-level facts that need no page pass: output intent, Trapped flag, attachments,
     * forms/signatures, JavaScript and layers switched off for print.
     */
    private static void collectDocumentFacts(
            ResourceBundle bundle, PDDocument document, Facts facts) {
        try {
            PDDocumentCatalog catalog = document.getDocumentCatalog();
            List<PDOutputIntent> intents = catalog.getOutputIntents();
            if (!intents.isEmpty()) {
                PDOutputIntent oi = intents.get(0);
                facts.setOutputIntent(
                        new OutputIntentFact(
                                oi.getOutputCondition(),
                                oi.getRegistryName(),
                                oi.getInfo(),
                                oi.getOutputConditionIdentifier()));
            }
            PDDocumentInformation info = document.getDocumentInformation();
            if (info != null) {
                facts.setTrapped(info.getTrapped());
            }
            PDDocumentNameDictionary names = catalog.getNames();
            if (names != null) {
                if (names.getEmbeddedFiles() != null) {
                    Map<String, ?> embedded = names.getEmbeddedFiles().getNames();
                    facts.setEmbeddedFileCount(embedded != null ? embedded.size() : 1);
                }
                facts.setHasJavascript(names.getJavaScript() != null);
            }
            if (catalog.getOpenAction() instanceof PDActionJavaScript) {
                facts.setHasJavascript(true);
            }
            PDAcroForm form = catalog.getAcroForm();
            if (form != null) {
                List<PDField> fields = form.getFields();
                facts.setHasAcroForm(!fields.isEmpty());
                facts.setFormFieldCount(fields.size());
                int signatures = 0;
                for (PDField field : fields) {
                    if (field instanceof PDSignatureField) {
                        signatures++;
                    }
                }
                facts.setSignatureCount(signatures);
                facts.setHasXfa(form.hasXFA());
            }
            PDOptionalContentProperties ocProps = catalog.getOCProperties();
            if (ocProps != null) {
                List<String> off = new ArrayList<>();
                for (PDOptionalContentGroup group : ocProps.getOptionalContentGroups()) {
                    try {
                        if (RenderState.OFF.equals(group.getRenderState(RenderDestination.PRINT))) {
                            String name = group.getName();
                            off.add(
                                    name != null
                                            ? name
                                            : PreflightReportText.msg(
                                                    bundle, "label.unnamedLayer"));
                        }
                    } catch (RuntimeException ignored) {
                        // a state name outside the RenderState enum must not sink the fact pass
                    }
                }
                facts.setLayersDisabledForPrint(off);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Document-level preflight facts failed", e);
        }
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
            ResourceBundle bundle,
            int pageNum,
            PDRectangle trim,
            PDRectangle bleed,
            int side,
            float actualPt,
            float requiredPt) {
        float gap = requiredPt - actualPt;
        String label =
                PreflightReportText.msg(
                        bundle,
                        "label.bleedDeclared",
                        sideLabel(bundle, side),
                        Math.round(actualPt / PT_PER_MM * 10) / 10f);
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

    private static String sideLabel(ResourceBundle bundle, int side) {
        String key =
                switch (side) {
                    case 0 -> "label.side.left";
                    case 1 -> "label.side.bottom";
                    case 2 -> "label.side.right";
                    default -> "label.side.top";
                };
        return PreflightReportText.msg(bundle, key);
    }

    /** Bleed width on each side: left, bottom, right, top; null when bleed does not cover trim. */
    static float[] bleedWidthPerSide(PDRectangle bleed, PDRectangle trim) {
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
            ResourceBundle bundle,
            PDPage page,
            PDRectangle trim,
            PDRectangle bleed,
            PDFRenderer renderer,
            int pageIndex) {
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
                                PreflightReportText.msg(
                                        bundle, "label.unpainted", sideLabel(bundle, band))));
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
