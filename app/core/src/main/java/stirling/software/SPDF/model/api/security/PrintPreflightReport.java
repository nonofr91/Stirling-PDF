package stirling.software.SPDF.model.api.security;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

import stirling.software.SPDF.service.preflight.PreflightCheck;
import stirling.software.SPDF.service.preflight.PreflightFixer;
import stirling.software.common.model.tool.ReportField;
import stirling.software.common.model.tool.ReportFieldKind;
import stirling.software.common.model.tool.ReportNamespace;
import stirling.software.common.model.tool.ReportProducedBy;

@Data
@NoArgsConstructor
public class PrintPreflightReport {

    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    public enum Category {
        FONTS,
        COLOR,
        IMAGES,
        GEOMETRY,
        CONTENT,
        DOCUMENT
    }

    private String fileName;
    private long fileSizeBytes;
    private String pdfVersion;
    private int pageCount;
    private Severity worstSeverity;
    private Counts counts = new Counts();
    private List<Finding> findings = new ArrayList<>();
    private Facts facts = new Facts();

    public void addFinding(Finding finding) {
        findings.add(finding);
        switch (finding.getSeverity()) {
            case ERROR -> counts.errors++;
            case WARNING -> counts.warnings++;
            case INFO -> counts.infos++;
        }
        if (worstSeverity == null || finding.getSeverity().ordinal() < worstSeverity.ordinal()) {
            worstSeverity = finding.getSeverity();
        }
    }

    /**
     * Compact summary of this report, also emitted as the pipeline step's report so routing rules
     * can match on {@code report.preflight.*}. {@code verdict} is categorical because matches-any
     * conditions compare by equality — {@code errors > 0} is not expressible there. {@code
     * fixupsApplied}, {@code preErrors} and {@code preWarnings} describe the pre-fix state and stay
     * empty/null on a plain analysis.
     */
    public Preflight getPreflight() {
        return Preflight.of(counts, findings);
    }

    @Data
    @NoArgsConstructor
    public static class Counts {
        private int errors;
        private int warnings;
        private int infos;
    }

    /**
     * The step-report shape every preflight endpoint shares: {@code verdict} is {@code fail} when
     * errors remain, {@code warn} when only warnings remain, {@code pass} otherwise. The {@code
     * *Checks} lists carry the distinct {@link Finding#getCode() finding codes} per severity so a
     * pipeline gate can match on the kind of issue, not only its count; the {@code pre*} variants
     * are populated by {@link #afterFix} and stay {@code null} on a plain analysis.
     */
    @ReportNamespace("preflight")
    public record Preflight(
            @ReportField(
                            kind = ReportFieldKind.ENUM,
                            values = {"pass", "warn", "fail"},
                            valueLabelPrefix = "printPreflight.verdict",
                            labelKey = "portal.pipelines.builder.routing.matchPreflightVerdict",
                            labelDefault = "Preflight verdict")
                    String verdict,
            @ReportField(
                            kind = ReportFieldKind.COUNT,
                            labelKey = "portal.pipelines.builder.routing.matchPreflightErrors",
                            labelDefault = "Preflight error count")
                    int errors,
            @ReportField(
                            kind = ReportFieldKind.COUNT,
                            labelKey = "portal.pipelines.builder.routing.matchPreflightWarnings",
                            labelDefault = "Preflight warning count")
                    int warnings,
            @ReportField(
                            kind = ReportFieldKind.CODE_LIST,
                            vocabulary = PreflightCheck.class,
                            labelKey =
                                    "portal.pipelines.builder.routing.matchPreflightFailingChecks",
                            labelDefault = "Preflight error type")
                    List<String> failingChecks,
            @ReportField(
                            kind = ReportFieldKind.CODE_LIST,
                            vocabulary = PreflightCheck.class,
                            labelKey =
                                    "portal.pipelines.builder.routing.matchPreflightWarningChecks",
                            labelDefault = "Preflight warning type")
                    List<String> warningChecks,
            @ReportField(
                            kind = ReportFieldKind.CODE_LIST,
                            vocabulary = PreflightCheck.class,
                            labelKey = "portal.pipelines.builder.routing.matchPreflightInfoChecks",
                            labelDefault = "Preflight info type")
                    List<String> infoChecks,
            @ReportField(
                            kind = ReportFieldKind.CODE_LIST,
                            vocabulary = PreflightFixer.Code.class,
                            producedBy = ReportProducedBy.FIX,
                            labelKey =
                                    "portal.pipelines.builder.routing.matchPreflightFixupsApplied",
                            labelDefault = "Applied fixup")
                    List<String> fixupsApplied,
            @ReportField(
                            kind = ReportFieldKind.CODE_LIST,
                            vocabulary = PreflightFixer.Code.class,
                            producedBy = ReportProducedBy.FIX,
                            labelKey =
                                    "portal.pipelines.builder.routing.matchPreflightFixupsSkipped",
                            labelDefault = "Skipped fixup")
                    List<String> fixupsSkipped,
            @ReportField(
                            kind = ReportFieldKind.COUNT,
                            producedBy = ReportProducedBy.FIX,
                            labelKey = "portal.pipelines.builder.routing.matchPreflightPreErrors",
                            labelDefault = "Preflight error count before fixups")
                    Integer preErrors,
            @ReportField(
                            kind = ReportFieldKind.COUNT,
                            producedBy = ReportProducedBy.FIX,
                            labelKey = "portal.pipelines.builder.routing.matchPreflightPreWarnings",
                            labelDefault = "Preflight warning count before fixups")
                    Integer preWarnings,
            @ReportField(
                            kind = ReportFieldKind.CODE_LIST,
                            vocabulary = PreflightCheck.class,
                            producedBy = ReportProducedBy.FIX,
                            labelKey =
                                    "portal.pipelines.builder.routing.matchPreflightPreFailingChecks",
                            labelDefault = "Preflight error type before fixups")
                    List<String> preFailingChecks,
            @ReportField(
                            kind = ReportFieldKind.CODE_LIST,
                            vocabulary = PreflightCheck.class,
                            producedBy = ReportProducedBy.FIX,
                            labelKey =
                                    "portal.pipelines.builder.routing.matchPreflightPreWarningChecks",
                            labelDefault = "Preflight warning type before fixups")
                    List<String> preWarningChecks,
            @ReportField(
                            kind = ReportFieldKind.CODE_LIST,
                            vocabulary = PreflightCheck.class,
                            producedBy = ReportProducedBy.FIX,
                            labelKey =
                                    "portal.pipelines.builder.routing.matchPreflightPreInfoChecks",
                            labelDefault = "Preflight info type before fixups")
                    List<String> preInfoChecks) {

        public static Preflight of(Counts counts, List<Finding> findings) {
            return new Preflight(
                    verdictOf(counts),
                    counts.getErrors(),
                    counts.getWarnings(),
                    codesOf(findings, Severity.ERROR),
                    codesOf(findings, Severity.WARNING),
                    codesOf(findings, Severity.INFO),
                    List.of(),
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        public static Preflight afterFix(
                Counts post,
                List<Finding> postFindings,
                List<String> fixupsApplied,
                List<String> fixupsSkipped,
                Counts pre,
                List<Finding> preFindings) {
            return new Preflight(
                    verdictOf(post),
                    post.getErrors(),
                    post.getWarnings(),
                    codesOf(postFindings, Severity.ERROR),
                    codesOf(postFindings, Severity.WARNING),
                    codesOf(postFindings, Severity.INFO),
                    List.copyOf(fixupsApplied),
                    List.copyOf(fixupsSkipped),
                    pre.getErrors(),
                    pre.getWarnings(),
                    codesOf(preFindings, Severity.ERROR),
                    codesOf(preFindings, Severity.WARNING),
                    codesOf(preFindings, Severity.INFO));
        }

        private static List<String> codesOf(List<Finding> findings, Severity severity) {
            if (findings == null) {
                return List.of();
            }
            return findings.stream()
                    .filter(finding -> finding.getSeverity() == severity)
                    .map(Finding::getCode)
                    .filter(code -> code != null && !code.isBlank())
                    .distinct()
                    .sorted()
                    .toList();
        }

        private static String verdictOf(Counts counts) {
            if (counts.getErrors() > 0) {
                return "fail";
            }
            if (counts.getWarnings() > 0) {
                return "warn";
            }
            return "pass";
        }
    }

    @Data
    @NoArgsConstructor
    public static class Finding {
        private Severity severity;
        private Category category;
        private String code;
        private String message;
        private List<Integer> pages = new ArrayList<>();

        /**
         * Page-space rectangles locating the issue (unrotated user space, bottom-left origin) —
         * what a viewer overlay or an annotated copy can highlight. Empty for document-wide
         * findings; capped at {@link #MAX_AREAS} to keep the report lean.
         */
        private List<FindingArea> areas = new ArrayList<>();

        private boolean areasTruncated;

        static final int MAX_AREAS = 64;

        public Finding(
                Severity severity,
                Category category,
                String code,
                String message,
                List<Integer> pages) {
            this.severity = severity;
            this.category = category;
            this.code = code;
            this.message = message;
            this.pages = pages != null ? pages : new ArrayList<>();
        }

        public void addArea(FindingArea area) {
            for (FindingArea existing : areas) {
                if (existing.page == area.page
                        && Math.abs(existing.x - area.x) < 0.5f
                        && Math.abs(existing.y - area.y) < 0.5f
                        && Math.abs(existing.width - area.width) < 0.5f
                        && Math.abs(existing.height - area.height) < 0.5f) {
                    return;
                }
            }
            if (areas.size() < MAX_AREAS) {
                areas.add(area);
            } else {
                areasTruncated = true;
            }
        }
    }

    @Data
    @NoArgsConstructor
    public static class FindingArea {
        /** 1-based page number. */
        private int page;

        private float x;
        private float y;
        private float width;
        private float height;
        private String label;

        public FindingArea(int page, float x, float y, float width, float height, String label) {
            this.page = page;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.label = label;
        }
    }

    @Data
    @NoArgsConstructor
    public static class Facts {
        private List<FontFact> fonts = new ArrayList<>();
        private List<String> colorSpaces = new ArrayList<>();
        private List<String> spotColors = new ArrayList<>();
        private List<String> technicalSeparations = new ArrayList<>();
        private int imageCount;
        private int lowResImageCount;
        private int oversampledImageCount;
        private double minEffectiveDpi = Double.NaN;
        private double maxEffectiveDpi = Double.NaN;
        private float minFontSizeSeen = Float.NaN;
        private float maxInkCoverageSeen;
        private boolean transparencyUsed;
        private boolean patternUsed;
        private boolean shadingUsed;
        private boolean hasTrimBox;
        private boolean hasBleedBox;
        private boolean hasCropBox;
        private boolean hasArtBox;
        private List<PageSize> pageSizes = new ArrayList<>();
        private OutputIntentFact outputIntent;
        private String trapped;
        private List<Integer> nonStandardUserUnitPages = new ArrayList<>();
        private boolean hasAcroForm;
        private int formFieldCount;
        private boolean hasXfa;
        private int signatureCount;
        private int embeddedFileCount;
        private boolean hasJavascript;
        private List<String> layersDisabledForPrint = new ArrayList<>();
        private List<Integer> emptyPages = new ArrayList<>();
        private List<Integer> invisibleTextPages = new ArrayList<>();
        private List<Integer> registrationPaintPages = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    public static class FontFact {
        private String name;
        private String subType;
        private boolean embedded;
        private boolean type3;
        private List<Integer> pages = new ArrayList<>();

        public FontFact(String name, String subType, boolean embedded, boolean type3) {
            this.name = name;
            this.subType = subType;
            this.embedded = embedded;
            this.type3 = type3;
        }
    }

    @Data
    @NoArgsConstructor
    public static class OutputIntentFact {
        private String name;
        private String registry;
        private String info;
        private String conditionIdentifier;

        public OutputIntentFact(
                String name, String registry, String info, String conditionIdentifier) {
            this.name = name;
            this.registry = registry;
            this.info = info;
            this.conditionIdentifier = conditionIdentifier;
        }
    }

    @Data
    @NoArgsConstructor
    public static class PageSize {
        private float widthPt;
        private float heightPt;
        private int rotation;
        private int count;

        public PageSize(float widthPt, float heightPt, int rotation, int count) {
            this.widthPt = widthPt;
            this.heightPt = heightPt;
            this.rotation = rotation;
            this.count = count;
        }
    }
}
