package stirling.software.SPDF.model.api.security;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

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

    @Data
    @NoArgsConstructor
    public static class Counts {
        private int errors;
        private int warnings;
        private int infos;
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
        private boolean transparencyUsed;
        private boolean hasTrimBox;
        private boolean hasBleedBox;
        private List<PageSize> pageSizes = new ArrayList<>();
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
