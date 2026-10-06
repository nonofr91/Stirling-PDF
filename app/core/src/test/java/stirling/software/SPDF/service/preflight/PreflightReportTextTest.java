package stirling.software.SPDF.service.preflight;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import stirling.software.SPDF.model.api.security.PrintPreflightReport;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Finding;
import stirling.software.SPDF.model.api.security.PrintPreflightRequest;

/** Locale resolution, bundle parity and end-to-end French report rendering. */
class PreflightReportTextTest {

    /** Every code a check can emit — a missing translation in either bundle fails the build. */
    private static final Set<String> FINDING_CODES =
            Set.of(
                    "CONTENT_PARSE_ERROR",
                    "FONT_NOT_EMBEDDED",
                    "FONT_TYPE3",
                    "COLOR_RGB_USED",
                    "COLOR_SPOT",
                    "IMAGE_LOW_RES",
                    "TRIMBOX_MISSING",
                    "BLEED_MISSING",
                    "BLEED_INSUFFICIENT",
                    "BLEED_UNPAINTED",
                    "ANNOTATION_IN_TRIM",
                    "HAIRLINE",
                    "TRANSPARENCY",
                    "OPTIONAL_CONTENT",
                    "MIXED_PAGE_SIZES",
                    "OVERPRINT_WHITE",
                    "OVERPRINT_BLACK",
                    "TEXT_SMALL",
                    "TEXT_RICH_BLACK",
                    "SAFETY_MARGIN",
                    "EMPTY_PAGE",
                    "IMAGE_OVERSAMPLED",
                    "IMAGE_1BIT_LOW_RES",
                    "INK_COVERAGE_HIGH",
                    "SPOT_ALIAS",
                    "SPOT_COUNT",
                    "REGISTRATION_PAINT",
                    "INVISIBLE_TEXT",
                    "PATTERN_USED",
                    "SHADING_USED",
                    "OBJECT_OUTSIDE_PAGE",
                    "OUTPUT_INTENT_MISSING",
                    "EMBEDDED_FILES",
                    "FORM_FIELDS",
                    "XFA_FORM",
                    "SIGNATURES",
                    "JAVASCRIPT",
                    "USER_UNIT",
                    "LAYERS_PRINT_OFF",
                    "CROPBOX_NE_MEDIA");

    private static ResourceBundle bundle(String tag) {
        PrintPreflightRequest request = new PrintPreflightRequest();
        request.setReportLanguage(tag);
        return PreflightReportText.bundleFor(request);
    }

    @Test
    void localeTagsNormalize() {
        PrintPreflightRequest request = new PrintPreflightRequest();
        request.setReportLanguage("fr-FR");
        assertEquals(Locale.forLanguageTag("fr-FR"), PreflightReportText.localeFor(request));
        request.setReportLanguage("en_US");
        assertEquals(Locale.forLanguageTag("en-US"), PreflightReportText.localeFor(request));
    }

    @Test
    void unsupportedTagsFallBackToEnglish() {
        // No ja file exists — and the JVM default locale must not leak into the report.
        ResourceBundle ja = bundle("ja-JP");
        assertEquals("Fonts", PreflightReportText.t(ja, "section.fonts", "?"));
    }

    @Test
    void everyFindingCodeHasEnAndFrText() {
        ResourceBundle en = bundle("en");
        ResourceBundle fr = bundle("fr");
        for (String code : FINDING_CODES) {
            assertTrue(en.containsKey("finding." + code), "en bundle lacks finding." + code);
            assertTrue(fr.containsKey("finding." + code), "fr bundle lacks finding." + code);
            assertNotEquals(
                    "finding." + code,
                    PreflightReportText.msg(fr, "finding." + code),
                    code + " resolved to nothing");
        }
    }

    @Test
    void placeholdersSubstituteWithoutEscapingApostrophes() {
        ResourceBundle fr = bundle("fr");
        String msg =
                PreflightReportText.msg(fr, "finding.FONT_NOT_EMBEDDED", "Helvetica, Courier-Bold");
        assertTrue(msg.contains("Helvetica, Courier-Bold"), msg);
        assertTrue(
                PreflightReportText.msg(fr, "finding.LAYERS_PRINT_OFF", "Guides")
                        .contains("l'impression"),
                "French apostrophes must survive pattern formatting");
    }

    @Test
    void frenchRequestProducesFrenchFindingsAndReport() throws Exception {
        // No TrimBox/BleedBox → deterministic geometry findings.
        PDDocument doc = new PDDocument();
        doc.addPage(new PDPage(PDRectangle.A4));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.save(baos);
        doc.close();

        PrintPreflightRequest request = new PrintPreflightRequest();
        request.setReportLanguage("fr-FR");
        PrintPreflightReport report;
        try (PDDocument loaded = Loader.loadPDF(baos.toByteArray())) {
            report = new PrintPreflightService().analyze(loaded, "test.pdf", 100, request);
        }

        Finding trim = null;
        for (Finding f : report.getFindings()) {
            if ("TRIMBOX_MISSING".equals(f.getCode())) {
                trim = f;
            }
        }
        assertNotNull(trim);
        assertEquals("Pas de TrimBox — le format de coupe fini est indéfini", trim.getMessage());

        Set<String> englishLeaks = new TreeSet<>();
        for (Finding f : report.getFindings()) {
            if (f.getMessage().contains(" the ") || f.getMessage().startsWith("No ")) {
                englishLeaks.add(f.getCode() + ": " + f.getMessage());
            }
        }
        assertTrue(englishLeaks.isEmpty(), "untranslated messages: " + englishLeaks);

        try (PDDocument reportDoc = new PDDocument()) {
            PreflightReportRenderer.render(reportDoc, report, request);
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(reportDoc);
            assertTrue(text.contains("RAPPORT DE PREFLIGHT"), text);
            assertTrue(text.contains("Constats"), text);
            assertTrue(text.contains("Polices"), text);
            assertFalse(text.contains("PRINT PREFLIGHT REPORT"), text);
        }
    }

    @Test
    void englishRequestKeepsEnglishReport() throws Exception {
        PDDocument doc = new PDDocument();
        doc.addPage(new PDPage(PDRectangle.A4));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.save(baos);
        doc.close();

        PrintPreflightRequest request = new PrintPreflightRequest();
        request.setReportLanguage("en-US");
        PrintPreflightReport report;
        try (PDDocument loaded = Loader.loadPDF(baos.toByteArray())) {
            report = new PrintPreflightService().analyze(loaded, "test.pdf", 100, request);
        }
        assertEquals(
                "No TrimBox — the finished cut size is undefined",
                report.getFindings().stream()
                        .filter(f -> "TRIMBOX_MISSING".equals(f.getCode()))
                        .findFirst()
                        .orElseThrow()
                        .getMessage());

        try (PDDocument reportDoc = new PDDocument()) {
            PreflightReportRenderer.render(reportDoc, report, request);
            String text = new PDFTextStripper().getText(reportDoc);
            assertTrue(text.contains("PRINT PREFLIGHT REPORT"), text);
        }
    }

    @Test
    void bundleCoversSideLabels() {
        ResourceBundle fr = bundle("fr-FR");
        for (String side : List.of("left", "bottom", "right", "top")) {
            String value = PreflightReportText.msg(fr, "label.side." + side);
            assertNotEquals("label.side." + side, value);
        }
        assertEquals(
                "gauche : 1.0 mm déclarés",
                PreflightReportText.msg(fr, "label.bleedDeclared", "gauche", 1.0f));
    }
}
