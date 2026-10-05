package stirling.software.SPDF.service.preflight;

import java.io.Closeable;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import stirling.software.SPDF.model.api.security.PrintPreflightReport;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Facts;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Finding;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.FontFact;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.OutputIntentFact;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.PageSize;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Severity;
import stirling.software.SPDF.model.api.security.PrintPreflightRequest;

/**
 * Renders the preflight report itself — verdict banner, document facts, fonts, colours, images and
 * the full findings list — as ordinary A4 pages. The same render serves both outputs: appended to a
 * fresh document it is the standalone report; moved to the front of the annotated copy it is the
 * summary page(s) a print operator reads first.
 */
public final class PreflightReportRenderer {

    private static final PDRectangle PAGE = PDRectangle.A4;
    private static final float MARGIN = 46f;
    private static final float CONTENT_W = PAGE.getWidth() - 2 * MARGIN;
    private static final float FOOTER_Y = 30f;

    private static final PDFont REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDFont ITALIC =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);

    private static final float[] INK = {0.13f, 0.14f, 0.18f};
    private static final float[] DIM = {0.42f, 0.44f, 0.50f};
    private static final float[] RULE = {0.82f, 0.84f, 0.88f};
    private static final float[] WHITE = {1f, 1f, 1f};
    private static final float[] RED = {0.82f, 0.16f, 0.16f};
    private static final float[] ORANGE = {0.93f, 0.52f, 0.05f};
    private static final float[] BLUE = {0.12f, 0.45f, 0.85f};
    private static final float[] GREEN = {0.16f, 0.60f, 0.27f};

    private static final int MAX_FONT_ROWS = 24;
    private static final int MAX_LIST_ITEMS = 14;

    private static final float[] FONT_TABLE_FRACTIONS = {0.46f, 0.22f, 0.16f, 0.16f};

    /** WinAnsi printable code points beyond Latin-1 — the rest of Unicode degrades to '-'. */
    private static final String WINANSI_EXTRA =
            "\u20ac\u0192\u201a\u201e\u2026\u2020\u2021\u02c6\u2030\u0160\u2039\u0152\u017d"
                    + "\u2018\u2019\u201c\u201d\u2022\u2013\u2014\u02dc\u2122\u0161\u203a\u0153"
                    + "\u017e\u0178";

    private PreflightReportRenderer() {}

    /**
     * Draws the report pages at the end of {@code target} and returns them, in order. The caller
     * decides whether they stay put (standalone report) or move to the front (summary of an
     * annotated copy).
     */
    public static List<PDPage> render(
            PDDocument target, PrintPreflightReport report, PrintPreflightRequest request)
            throws IOException {
        Writer w = new Writer(target);
        w.startPage();
        header(w, report);
        verdictBanner(w, report);
        documentSection(w, report, request);
        colourSection(w, report);
        fontSection(w, report);
        imageSection(w, report);
        findingsSection(w, report);
        w.close();
        return w.pages;
    }

    /** Moves rendered report pages to the front of the document, keeping their order. */
    public static void insertAtFront(PDDocument document, List<PDPage> reportPages) {
        if (reportPages.isEmpty()) {
            return;
        }
        PDPage first = document.getPage(0);
        for (PDPage page : reportPages) {
            // insertBefore does not detach: without the remove the page would sit in Kids twice.
            document.removePage(page);
            document.getPages().insertBefore(page, first);
        }
    }

    private static void header(Writer w, PrintPreflightReport report) throws IOException {
        w.text("PRINT PREFLIGHT REPORT", BOLD, 18, INK);
        w.gap(6);
        String name = report.getFileName() != null ? report.getFileName() : "document";
        w.text(name + "  ·  " + fileSize(report.getFileSizeBytes()), REGULAR, 10, DIM);
        w.text(
                "Generated "
                        + LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                        + " by Stirling-PDF",
                REGULAR,
                8,
                DIM);
        w.gap(10);
    }

    private static void verdictBanner(Writer w, PrintPreflightReport report) throws IOException {
        int errors = report.getCounts().getErrors();
        int warnings = report.getCounts().getWarnings();
        int infos = report.getCounts().getInfos();
        float[] color = errors > 0 ? RED : warnings > 0 ? ORANGE : GREEN;
        String verdict =
                errors > 0
                        ? "NOT READY FOR PRINT"
                        : warnings > 0 ? "PRINTABLE WITH WARNINGS" : "READY FOR PRINT";
        w.banner(color, verdict);
        w.text(
                errors + " error(s) · " + warnings + " warning(s) · " + infos + " info",
                REGULAR,
                10,
                INK);
        w.gap(14);
    }

    private static void documentSection(
            Writer w, PrintPreflightReport report, PrintPreflightRequest request)
            throws IOException {
        w.section("Document");
        Facts facts = report.getFacts();
        w.kv("Pages", String.valueOf(report.getPageCount()));
        w.kv("PDF version", report.getPdfVersion());
        for (PageSize size : facts.getPageSizes()) {
            String label =
                    "Page size" + (size.getCount() > 1 ? " ×" + size.getCount() + " pages" : "");
            String value =
                    String.format(
                            Locale.ROOT,
                            "%.1f × %.1f mm (%.0f × %.0f pt)",
                            size.getWidthPt() / 72f * 25.4f,
                            size.getHeightPt() / 72f * 25.4f,
                            size.getWidthPt(),
                            size.getHeightPt());
            if (size.getRotation() != 0) {
                value += " · rotated " + size.getRotation() + "°";
            }
            w.kv(label, value);
        }
        w.kv(
                "Page boxes",
                "TrimBox "
                        + yesNo(facts.isHasTrimBox())
                        + " · BleedBox "
                        + yesNo(facts.isHasBleedBox())
                        + " · ArtBox "
                        + yesNo(facts.isHasArtBox()));
        w.kv("Required bleed", request.getRequiredBleedMm() + " mm per side");
        if (facts.getOutputIntent() != null) {
            OutputIntentFact oi = facts.getOutputIntent();
            StringBuilder oiText = new StringBuilder();
            oiText.append(oi.getName() != null ? oi.getName() : "present");
            if (oi.getConditionIdentifier() != null && !oi.getConditionIdentifier().isEmpty()) {
                oiText.append(" (").append(oi.getConditionIdentifier()).append(")");
            }
            if (oi.getRegistry() != null && !oi.getRegistry().isEmpty()) {
                oiText.append(" · ").append(oi.getRegistry());
            }
            w.kv("Output intent", oiText.toString());
        } else {
            w.kv("Output intent", "none declared");
        }
        w.kv("Trapped", facts.getTrapped() != null ? facts.getTrapped() : "not set");
        if (!facts.getNonStandardUserUnitPages().isEmpty()) {
            w.kv("UserUnit", "non-default on page(s) " + facts.getNonStandardUserUnitPages());
        }
        w.gap(8);
    }

    private static void colourSection(Writer w, PrintPreflightReport report) throws IOException {
        Facts facts = report.getFacts();
        w.section("Colours & separations");
        w.kv("Colour spaces", joinOrDash(facts.getColorSpaces()));
        w.kv("Spot inks", joinOrDash(facts.getSpotColors()));
        if (!facts.getTechnicalSeparations().isEmpty()) {
            w.kv("Technical separations", joinOrDash(facts.getTechnicalSeparations()));
        }
        w.kv("Transparency", yesNo(facts.isTransparencyUsed()));
        if (facts.isPatternUsed() || facts.isShadingUsed()) {
            w.kv(
                    "Special paint",
                    (facts.isPatternUsed() ? "patterns" : "")
                            + (facts.isPatternUsed() && facts.isShadingUsed() ? " · " : "")
                            + (facts.isShadingUsed() ? "smooth shadings" : ""));
        }
        if (facts.getMaxInkCoverageSeen() > 0) {
            w.kv(
                    "Max ink coverage",
                    Math.round(facts.getMaxInkCoverageSeen()) + "% on a painted object");
        }
        if (!facts.getLayersDisabledForPrint().isEmpty()) {
            w.kv("Layers off for print", joinOrDash(facts.getLayersDisabledForPrint()));
        }
        w.gap(8);
    }

    private static void fontSection(Writer w, PrintPreflightReport report) throws IOException {
        Facts facts = report.getFacts();
        w.section("Fonts");
        if (facts.getFonts().isEmpty()) {
            w.text("No fonts used.", REGULAR, 9, DIM);
        } else {
            w.tableHeader(FONT_TABLE_FRACTIONS, "Name", "Type", "Embedded", "Type 3");
            int shown = 0;
            for (FontFact font : facts.getFonts()) {
                if (shown >= MAX_FONT_ROWS) {
                    w.text(
                            "… + " + (facts.getFonts().size() - shown) + " more font(s)",
                            ITALIC,
                            8,
                            DIM);
                    break;
                }
                w.tableRow(
                        font.isEmbedded() ? INK : RED,
                        FONT_TABLE_FRACTIONS,
                        font.getName(),
                        font.getSubType(),
                        font.isEmbedded() ? "yes" : "NO",
                        font.isType3() ? "yes" : "—");
                shown++;
            }
        }
        if (!Float.isNaN(facts.getMinFontSizeSeen())) {
            w.kv(
                    "Smallest text",
                    String.format(Locale.ROOT, "%.1f pt", facts.getMinFontSizeSeen()));
        }
        w.gap(8);
    }

    private static void imageSection(Writer w, PrintPreflightReport report) throws IOException {
        Facts facts = report.getFacts();
        w.section("Images");
        w.kv("Images", String.valueOf(facts.getImageCount()));
        if (facts.getLowResImageCount() > 0) {
            w.kv("Below resolution threshold", String.valueOf(facts.getLowResImageCount()));
        }
        if (facts.getOversampledImageCount() > 0) {
            w.kv("Oversampled", String.valueOf(facts.getOversampledImageCount()));
        }
        if (!Double.isNaN(facts.getMinEffectiveDpi())
                || !Double.isNaN(facts.getMaxEffectiveDpi())) {
            String range =
                    (Double.isNaN(facts.getMinEffectiveDpi())
                                    ? "—"
                                    : String.valueOf(Math.round(facts.getMinEffectiveDpi())))
                            + " – "
                            + (Double.isNaN(facts.getMaxEffectiveDpi())
                                    ? "—"
                                    : String.valueOf(Math.round(facts.getMaxEffectiveDpi())))
                            + " dpi";
            w.kv("Effective resolution range", range);
        }
        w.gap(8);
    }

    private static void findingsSection(Writer w, PrintPreflightReport report) throws IOException {
        List<Finding> findings = new ArrayList<>(report.getFindings());
        findings.sort(Comparator.comparingInt(f -> f.getSeverity().ordinal()));
        w.section("Findings (" + findings.size() + ")");
        if (findings.isEmpty()) {
            w.text("No issues detected by the enabled checks.", REGULAR, 9, DIM);
            return;
        }
        for (Finding finding : findings) {
            w.findingLine(finding);
        }
    }

    private static String fileSize(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576f);
        }
        return Math.round(bytes / 1024f) + " KB";
    }

    private static String yesNo(boolean b) {
        return b ? "yes" : "no";
    }

    private static String joinOrDash(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "—";
        }
        if (items.size() > MAX_LIST_ITEMS) {
            List<String> shown = new ArrayList<>(items.subList(0, MAX_LIST_ITEMS));
            return String.join(", ", shown) + " … +" + (items.size() - MAX_LIST_ITEMS);
        }
        return String.join(", ", items);
    }

    /** Standard-14 fonts encode WinAnsi only — anything beyond degrades to '-'. */
    private static String safe(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            out.append(c <= 0xFF || WINANSI_EXTRA.indexOf(c) >= 0 ? c : '-');
        }
        return out.toString();
    }

    /** Cursor-based page writer: flows content down, paginating as needed. */
    private static final class Writer implements Closeable {
        final PDDocument doc;
        final List<PDPage> pages = new ArrayList<>();
        PDPage page;
        PDPageContentStream cs;
        float y;

        Writer(PDDocument doc) {
            this.doc = doc;
        }

        void startPage() throws IOException {
            if (cs != null) {
                cs.close();
            }
            page = new PDPage(new PDRectangle(0, 0, PAGE.getWidth(), PAGE.getHeight()));
            doc.addPage(page);
            pages.add(page);
            cs = new PDPageContentStream(doc, page);
            y = PAGE.getHeight() - MARGIN;
        }

        @Override
        public void close() throws IOException {
            if (cs != null) {
                cs.close();
                cs = null;
            }
            for (int i = 0; i < pages.size(); i++) {
                try (PDPageContentStream footer =
                        new PDPageContentStream(
                                doc,
                                pages.get(i),
                                PDPageContentStream.AppendMode.APPEND,
                                true,
                                true)) {
                    footer.beginText();
                    footer.setFont(REGULAR, 7);
                    footer.setNonStrokingColor(DIM[0], DIM[1], DIM[2]);
                    footer.newLineAtOffset(MARGIN, FOOTER_Y);
                    footer.showText(safe("Stirling-PDF — print preflight"));
                    footer.endText();
                    String pageNum = (i + 1) + " / " + pages.size();
                    footer.beginText();
                    footer.setFont(REGULAR, 7);
                    footer.newLineAtOffset(
                            PAGE.getWidth() - MARGIN - widthOf(pageNum, REGULAR, 7), FOOTER_Y);
                    footer.showText(safe(pageNum));
                    footer.endText();
                }
            }
        }

        /** Room left above the footer; opens a fresh page when the block would not fit. */
        private void ensure(float height) throws IOException {
            if (y - height < FOOTER_Y + 14) {
                startPage();
            }
        }

        void gap(float amount) {
            y -= amount;
        }

        void text(String text, PDFont font, float size, float[] color) throws IOException {
            ensure(size + 4);
            putText(MARGIN, y, text, font, size, color);
            y -= size + 4;
        }

        private void putText(float x, float yPos, String text, PDFont font, float size, float[] c)
                throws IOException {
            cs.beginText();
            cs.setFont(font, size);
            cs.setNonStrokingColor(c[0], c[1], c[2]);
            cs.newLineAtOffset(x, yPos);
            cs.showText(safe(text));
            cs.endText();
        }

        void banner(float[] color, String text) throws IOException {
            float h = 26;
            ensure(h + 8);
            cs.setNonStrokingColor(color[0], color[1], color[2]);
            cs.addRect(MARGIN, y - h + 6, CONTENT_W, h);
            cs.fill();
            putText(MARGIN + 10, y - h + 14, text, BOLD, 12, WHITE);
            y -= h + 4;
        }

        void section(String title) throws IOException {
            ensure(34);
            y -= 4;
            putText(MARGIN, y, title, BOLD, 12, INK);
            y -= 6;
            cs.setStrokingColor(RULE[0], RULE[1], RULE[2]);
            cs.setLineWidth(0.8f);
            cs.moveTo(MARGIN, y);
            cs.lineTo(MARGIN + CONTENT_W, y);
            cs.stroke();
            y -= 10;
        }

        void kv(String label, String value) throws IOException {
            List<String> lines = wrap(value, REGULAR, 9, CONTENT_W - 130);
            ensure(lines.size() * 12 + 4);
            putText(MARGIN, y, label, BOLD, 9, DIM);
            for (String line : lines) {
                putText(MARGIN + 130, y, line, REGULAR, 9, INK);
                y -= 12;
            }
            y -= 2;
        }

        void tableHeader(float[] fractions, String... cols) throws IOException {
            ensure(16);
            float x = MARGIN;
            for (int i = 0; i < cols.length && i < fractions.length; i++) {
                putText(x, y, cols[i], BOLD, 8, DIM);
                x += fractions[i] * CONTENT_W;
            }
            y -= 12;
        }

        void tableRow(float[] rowColor, float[] fractions, String... cols) throws IOException {
            ensure(14);
            float x = MARGIN;
            for (int i = 0; i < cols.length && i < fractions.length; i++) {
                String clipped = clip(cols[i], REGULAR, 9, fractions[i] * CONTENT_W - 8);
                putText(x, y, clipped, REGULAR, 9, rowColor);
                x += fractions[i] * CONTENT_W;
            }
            y -= 12;
        }

        void findingLine(Finding finding) throws IOException {
            float[] color =
                    switch (finding.getSeverity()) {
                        case ERROR -> RED;
                        case WARNING -> ORANGE;
                        case INFO -> BLUE;
                    };
            String head = finding.getCode() + "  ·  " + sevName(finding.getSeverity());
            List<String> msgLines = wrap(finding.getMessage(), REGULAR, 9, CONTENT_W - 16);
            String pages =
                    finding.getPages() != null && !finding.getPages().isEmpty()
                            ? "Pages: " + joinPages(finding.getPages())
                            : "Document-wide";
            ensure(14 + msgLines.size() * 12 + 14);
            cs.setNonStrokingColor(color[0], color[1], color[2]);
            cs.addRect(MARGIN, y - 7, 8, 8);
            cs.fill();
            putText(MARGIN + 14, y - 6, head, BOLD, 9, INK);
            y -= 12;
            for (String line : msgLines) {
                putText(MARGIN + 14, y, line, REGULAR, 9, INK);
                y -= 12;
            }
            putText(MARGIN + 14, y, pages, REGULAR, 8, DIM);
            y -= 16;
        }

        private String sevName(Severity severity) {
            return switch (severity) {
                case ERROR -> "Error";
                case WARNING -> "Warning";
                case INFO -> "Info";
            };
        }

        private String joinPages(List<Integer> pages) {
            if (pages.size() > 20) {
                return joinInts(pages.subList(0, 20)) + " … +" + (pages.size() - 20);
            }
            return joinInts(pages);
        }

        private String joinInts(List<Integer> pages) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pages.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(pages.get(i));
            }
            return sb.toString();
        }

        private List<String> wrap(String text, PDFont font, float size, float maxWidth)
                throws IOException {
            List<String> lines = new ArrayList<>();
            if (text == null || text.isEmpty()) {
                lines.add("");
                return lines;
            }
            String[] words = safe(text).split(" ");
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (widthOf(candidate, font, size) <= maxWidth) {
                    line = new StringBuilder(candidate);
                } else {
                    if (!line.isEmpty()) {
                        lines.add(line.toString());
                    }
                    // a single word wider than the column is clipped rather than overflowed
                    line = new StringBuilder(clip(word, font, size, maxWidth));
                }
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
            }
            return lines;
        }

        private String clip(String text, PDFont font, float size, float maxWidth)
                throws IOException {
            String s = safe(text);
            while (!s.isEmpty() && widthOf(s + "…", font, size) > maxWidth) {
                s = s.substring(0, s.length() - 1);
            }
            return s.isEmpty() ? "" : s + "…";
        }

        private float widthOf(String text, PDFont font, float size) throws IOException {
            return font.getStringWidth(safe(text)) / 1000f * size;
        }
    }
}
