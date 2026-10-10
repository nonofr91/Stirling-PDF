package stirling.software.common.model.tool;

/**
 * Which step variant emits a report field. {@code FIX} fields describe the corrector pass — pre-fix
 * state and fixup outcomes — so a route reading them is only meaningful behind a fix endpoint.
 */
public enum ReportProducedBy {
    ANALYSIS,
    FIX;

    public String serialize() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
