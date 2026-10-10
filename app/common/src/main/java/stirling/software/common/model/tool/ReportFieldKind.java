package stirling.software.common.model.tool;

/** How a report field is compared in a routing rule or step gate. */
public enum ReportFieldKind {
    /** A closed value set — the editor offers the declared values. */
    ENUM,
    /** A numeric count — the editor offers free numeric comparison. */
    COUNT,
    /** A list of machine codes — the editor offers the declared vocabulary. */
    CODE_LIST;

    /** Kebab-case, matching the frontend descriptor vocabulary. */
    public String serialize() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
