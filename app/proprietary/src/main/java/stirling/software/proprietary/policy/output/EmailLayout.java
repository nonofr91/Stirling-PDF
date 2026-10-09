package stirling.software.proprietary.policy.output;

import java.util.List;
import java.util.Map;

/**
 * Wraps a rendered message in a branded HTML shell: dark header carrying the Stirling logo (inline
 * CID part), a coloured verdict banner, the caller's message, one row per delivered file, and a
 * footer with run identity. Table-based layout with inline styles — the subset mail clients
 * actually render.
 */
final class EmailLayout {

    /** One line of the per-file table inside the report mail. */
    record Row(String name, String verdict, int errors, int warnings, List<String> fixups) {}

    private EmailLayout() {}

    static String render(
            String messageHtml, List<Row> files, Map<String, String> vars, boolean hasLogo) {
        String verdict = vars.getOrDefault("verdict", "n/a");
        StringBuilder html =
                new StringBuilder(
                        """
                        <!DOCTYPE html>
                        <html><body style="margin:0;padding:0;background:#f1f5f9;font-family:'Segoe UI',Arial,sans-serif">
                        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" bgcolor="#f1f5f9"><tr><td align="center" style="padding:24px 12px">
                        <table role="presentation" width="620" cellpadding="0" cellspacing="0" style="max-width:620px;width:100%;border-radius:12px;overflow:hidden;box-shadow:0 1px 3px rgba(15,23,42,.12)">
                        <tr><td bgcolor="#0f172a" style="padding:18px 28px">
                        """);
        if (hasLogo) {
            html.append(
                    "<img src=\"cid:stirling-logo\" height=\"32\" alt=\"Stirling-PDF\" style=\"display:block\">");
        } else {
            html.append(
                    "<span style=\"color:#fff;font-size:18px;font-weight:700;letter-spacing:.5px\">Stirling-PDF</span>");
        }
        html.append(
                """
                </td></tr>
                <tr><td bgcolor="#ffffff" style="padding:24px 28px 8px">
                """);
        html.append(badge(verdict))
                .append("<span style=\"font-size:15px;color:#334155;margin-left:10px\">")
                .append(escape(vars.getOrDefault("pipeline", "")))
                .append("</span>")
                .append(
                        """
                        <p style="color:#334155;font-size:14px;line-height:1.6;margin:18px 0">
                        """)
                .append(messageHtml)
                .append("</p>");
        if (!files.isEmpty()) {
            html.append(
                    """
                    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border:1px solid #e2e8f0;border-radius:8px;font-size:13px;margin:8px 0 12px">
                    <tr bgcolor="#f8fafc">
                    <th align="left" style="padding:10px 14px;color:#64748b;font-weight:600;border-bottom:1px solid #e2e8f0">File</th>
                    <th align="left" style="padding:10px 14px;color:#64748b;font-weight:600;border-bottom:1px solid #e2e8f0">Verdict</th>
                    <th align="center" style="padding:10px 14px;color:#64748b;font-weight:600;border-bottom:1px solid #e2e8f0">Errors</th>
                    <th align="center" style="padding:10px 14px;color:#64748b;font-weight:600;border-bottom:1px solid #e2e8f0">Warnings</th>
                    </tr>
                    """);
            for (Row row : files) {
                html.append("<tr>")
                        .append(
                                "<td style=\"padding:10px 14px;border-bottom:1px solid #f1f5f9;color:#1e293b\">")
                        .append(escape(row.name()));
                if (!row.fixups().isEmpty()) {
                    html.append(
                                    "<div style=\"color:#64748b;font-size:12px;margin-top:2px\">Fixups: ")
                            .append(escape(String.join(", ", row.fixups())))
                            .append("</div>");
                }
                html.append("</td>")
                        .append("<td style=\"padding:10px 14px;border-bottom:1px solid #f1f5f9\">")
                        .append(badge(row.verdict()))
                        .append(
                                "</td><td align=\"center\" style=\"padding:10px 14px;border-bottom:1px solid #f1f5f9;color:#1e293b\">")
                        .append(row.errors())
                        .append(
                                "</td><td align=\"center\" style=\"padding:10px 14px;border-bottom:1px solid #f1f5f9;color:#1e293b\">")
                        .append(row.warnings())
                        .append("</td></tr>");
            }
            html.append("</table>");
        }
        html.append(
                        """
                        </td></tr>
                        <tr><td bgcolor="#f8fafc" style="padding:14px 28px;border-top:1px solid #e2e8f0">
                        <p style="margin:0;color:#94a3b8;font-size:12px">Run <span style="font-family:monospace">""")
                .append(escape(vars.getOrDefault("runId", "")))
                .append("</span> &middot; ")
                .append(escape(vars.getOrDefault("date", "")))
                .append("</p></td></tr></table>")
                .append(
                        """
                        <p style="color:#94a3b8;font-size:11px;margin-top:14px">Sent by Stirling-PDF pipelines</p>
                        </td></tr></table></body></html>
                        """);
        return html.toString();
    }

    private static String badge(String verdict) {
        String color =
                switch (verdict == null ? "" : verdict) {
                    case "pass" -> "#16a34a";
                    case "warn" -> "#d97706";
                    case "fail" -> "#dc2626";
                    default -> "#64748b";
                };
        return "<span style=\"display:inline-block;padding:3px 12px;border-radius:999px;background:"
                + color
                + ";color:#fff;font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:.4px\">"
                + escape(verdict == null ? "n/a" : verdict)
                + "</span>";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
