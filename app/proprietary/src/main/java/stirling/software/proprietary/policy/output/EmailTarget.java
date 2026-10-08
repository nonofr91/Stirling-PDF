package stirling.software.proprietary.policy.output;

import java.util.List;
import java.util.Map;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

/**
 * Parsed configuration of an {@code smtp} destination. {@code subject} and {@code body} are
 * templates rendered per delivery against the run's context ({@code {filename}}, {@code {verdict}},
 * {@code {errorCount}}… — see {@link EmailTemplate}). {@code mode} chooses one mail for the whole
 * delivery ("perRun", default) or one per output file ("perFile"). The processed documents stay out
 * of the mail unless {@code attachOutputs} is set; the producing step's report JSON is attached by
 * default ({@code attachReport}).
 */
public record EmailTarget(
        List<String> to,
        String subject,
        String body,
        String mode,
        boolean attachReport,
        boolean attachOutputs) {

    public static final String MODE_PER_RUN = "perRun";
    public static final String MODE_PER_FILE = "perFile";

    static final String DEFAULT_SUBJECT = "[{verdict}] {filename} — {pipeline}";
    static final String DEFAULT_BODY =
            """
            Pipeline {pipeline} processed {filename}.

            Verdict: {verdict}
            Errors: {errorCount} · Warnings: {warningCount}
            Fixups applied: {fixups}

            Run {runId} — {date}
            """;

    public static EmailTarget from(Map<String, Object> options) {
        String rawTo = string(options.get("to"));
        List<String> to =
                rawTo == null
                        ? List.of()
                        : List.of(rawTo.split("[,;]")).stream()
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList();
        String subject = string(options.get("subject"));
        String body = string(options.get("body"));
        String mode = string(options.get("mode"));
        return new EmailTarget(
                to,
                subject == null || subject.isBlank() ? DEFAULT_SUBJECT : subject,
                body == null || body.isBlank() ? DEFAULT_BODY : body,
                MODE_PER_FILE.equals(mode) ? MODE_PER_FILE : MODE_PER_RUN,
                bool(options.get("attachReport"), true),
                bool(options.get("attachOutputs"), false));
    }

    /**
     * Fail-fast validation for save-time checks: recipients must parse as RFC 822 addresses and
     * carry a domain — a bare local-part parses strictly but can never route.
     */
    public List<InternetAddress> recipients() {
        try {
            InternetAddress[] parsed = InternetAddress.parse(String.join(",", to), true);
            for (InternetAddress address : parsed) {
                if (address.getAddress() == null || !address.getAddress().contains("@")) {
                    throw new IllegalArgumentException(
                            "Recipient has no domain: " + address.getAddress());
                }
            }
            return List.of(parsed);
        } catch (AddressException e) {
            throw new IllegalArgumentException("Invalid recipient list: " + e.getMessage(), e);
        }
    }

    private static String string(Object value) {
        return value instanceof String s ? s : null;
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }
}
