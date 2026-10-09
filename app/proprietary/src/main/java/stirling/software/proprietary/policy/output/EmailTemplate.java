package stirling.software.proprietary.policy.output;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Renders {@code {name}} placeholders in email subjects and bodies against a run's context. Unknown
 * placeholders stay literal so a typo produces a readable message, not a silent blank. {@link
 * #renderText} substitutes raw values; {@link #renderHtml} HTML-escapes template and values alike —
 * filenames and finding text are attacker-adjacent data — then turns newlines into {@code <br>}.
 */
final class EmailTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9_]*)}");

    private EmailTemplate() {}

    static String renderText(String template, Map<String, String> vars) {
        if (template == null) return "";
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = vars.get(matcher.group(1));
            matcher.appendReplacement(
                    out,
                    value == null
                            ? Matcher.quoteReplacement(matcher.group())
                            : Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static String renderHtml(String template, Map<String, String> vars) {
        if (template == null) return "";
        Map<String, String> escaped =
                vars.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> escape(e.getValue())));
        return renderText(escape(template), escaped).replace("\n", "<br>\n");
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
