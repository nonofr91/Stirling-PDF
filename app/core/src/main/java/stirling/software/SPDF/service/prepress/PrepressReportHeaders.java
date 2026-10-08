package stirling.software.SPDF.service.prepress;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import stirling.software.SPDF.model.api.security.PrintPreflightReport;

/**
 * The {@code X-Stirling-Tool-Report} header emitted by prepress endpoints so a pipeline step can
 * route on the outcome. The header carries the compact {@link PrintPreflightReport.Preflight}
 * summary only — never the full report, which would blow header size limits.
 */
public final class PrepressReportHeaders {

    // Must stay identical to AiToolResponseHeaders.TOOL_REPORT in app/proprietary — core cannot
    // import it (module direction is proprietary -> core), and the policy executor parses this
    // exact header name off step responses.
    public static final String TOOL_REPORT = "X-Stirling-Tool-Report";

    private PrepressReportHeaders() {}

    /** The report payload as a JSON string: {@code {"preflight":{...}}}. */
    public static String toolReportJson(
            ObjectMapper objectMapper, PrintPreflightReport.Preflight summary) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("preflight", objectMapper.valueToTree(summary));
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize the preflight tool report", e);
        }
    }
}
