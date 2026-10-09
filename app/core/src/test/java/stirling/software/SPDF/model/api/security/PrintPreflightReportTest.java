package stirling.software.SPDF.model.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.SPDF.model.api.security.PrintPreflightReport.Category;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Finding;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Preflight;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Severity;

class PrintPreflightReportTest {

    private static Finding finding(Severity severity, String code) {
        return new Finding(severity, Category.COLOR, code, code + " found", List.of(1));
    }

    @Test
    void summaryListsDistinctSortedCheckCodesPerSeverity() {
        PrintPreflightReport report = new PrintPreflightReport();
        report.addFinding(finding(Severity.ERROR, "OVERPRINT_WHITE"));
        report.addFinding(finding(Severity.ERROR, "BLEED_MISSING"));
        report.addFinding(finding(Severity.ERROR, "OVERPRINT_WHITE")); // twice on other pages
        report.addFinding(finding(Severity.WARNING, "HAIRLINE"));
        report.addFinding(finding(Severity.INFO, "TRANSPARENCY"));

        Preflight summary = report.getPreflight();

        assertEquals(List.of("BLEED_MISSING", "OVERPRINT_WHITE"), summary.failingChecks());
        assertEquals(List.of("HAIRLINE"), summary.warningChecks());
        assertEquals(List.of("TRANSPARENCY"), summary.infoChecks());
        // An analysis never describes a pre-fix state.
        assertNull(summary.preFailingChecks());
    }

    @Test
    void afterFixReportsPostAndPreCheckCodes() {
        PrintPreflightReport pre = new PrintPreflightReport();
        pre.addFinding(finding(Severity.ERROR, "OVERPRINT_WHITE"));
        pre.addFinding(finding(Severity.ERROR, "BLEED_MISSING"));
        pre.addFinding(finding(Severity.WARNING, "HAIRLINE"));

        PrintPreflightReport post = new PrintPreflightReport();
        post.addFinding(finding(Severity.ERROR, "BLEED_MISSING"));
        post.addFinding(finding(Severity.INFO, "PATTERN_USED"));

        Preflight summary =
                Preflight.afterFix(
                        post.getCounts(),
                        post.getFindings(),
                        List.of("KNOCKOUT_WHITE"),
                        pre.getCounts(),
                        pre.getFindings());

        assertEquals(List.of("BLEED_MISSING"), summary.failingChecks());
        assertEquals(List.of("PATTERN_USED"), summary.infoChecks());
        assertEquals(List.of("KNOCKOUT_WHITE"), summary.fixupsApplied());
        assertEquals(List.of("BLEED_MISSING", "OVERPRINT_WHITE"), summary.preFailingChecks());
        assertEquals(List.of("HAIRLINE"), summary.preWarningChecks());
        assertEquals(List.of(), summary.preInfoChecks());
    }
}
