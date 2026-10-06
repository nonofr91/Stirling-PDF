package stirling.software.SPDF.model.api.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.Data;
import lombok.NoArgsConstructor;

import stirling.software.SPDF.model.api.security.PrintPreflightReport.Counts;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Finding;

/**
 * Dry-run outcome of the print-preflight fixups: which corrections applied and how the finding set
 * changed between the original document and the corrected copy. Produced by {@code
 * /api/v1/security/print-preflight-fix-preview}, which runs the same corrections as the fix
 * endpoint but returns this audit instead of the mutated PDF.
 */
@Data
@NoArgsConstructor
public class PrintPreflightFixAudit {

    private String fileName;
    private List<String> appliedFixups = new ArrayList<>();
    private Counts countsBefore = new Counts();
    private Counts countsAfter = new Counts();

    /** Findings whose code fired before the fixups and no longer does. */
    private List<Finding> resolvedFindings = new ArrayList<>();

    /** Findings still firing after the fixups ran. */
    private List<Finding> remainingFindings = new ArrayList<>();

    /** Findings that only appeared after — corrections can surface new issues. */
    private List<Finding> introducedFindings = new ArrayList<>();

    /**
     * Compares findings by code: a fixup resolving a finding removes its code, one surfacing a new
     * issue introduces a code. A code firing on fewer pages afterwards counts as remaining, not
     * resolved — the counts carry the quantitative change.
     */
    public static PrintPreflightFixAudit of(
            PrintPreflightReport before, PrintPreflightReport after, List<String> appliedFixups) {
        PrintPreflightFixAudit audit = new PrintPreflightFixAudit();
        audit.setFileName(before.getFileName());
        audit.setAppliedFixups(appliedFixups);
        audit.setCountsBefore(before.getCounts());
        audit.setCountsAfter(after.getCounts());

        Map<String, Finding> afterByCode = firstByCode(after.getFindings());
        Set<String> beforeCodes = new LinkedHashSet<>();
        for (Finding finding : before.getFindings()) {
            beforeCodes.add(finding.getCode());
            if (!afterByCode.containsKey(finding.getCode())) {
                audit.resolvedFindings.add(finding);
            }
        }
        for (Finding finding : after.getFindings()) {
            if (beforeCodes.contains(finding.getCode())) {
                audit.remainingFindings.add(finding);
            } else {
                audit.introducedFindings.add(finding);
            }
        }
        return audit;
    }

    private static Map<String, Finding> firstByCode(List<Finding> findings) {
        Map<String, Finding> byCode = new LinkedHashMap<>();
        for (Finding finding : findings) {
            byCode.putIfAbsent(finding.getCode(), finding);
        }
        return byCode;
    }
}
