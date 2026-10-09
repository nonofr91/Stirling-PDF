package stirling.software.SPDF.service.preflight;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import org.springframework.context.i18n.LocaleContextHolder;

import stirling.software.SPDF.model.api.security.PrintPreflightRequest;

/**
 * Text lookup for generated preflight output (report pages, annotation notes). The bundle lives in
 * {@code preflight-report*.properties}; any language without its own file falls back to the base
 * English bundle, and a key absent from a translated file falls back to the English entry — so a
 * partial or missing translation can never break the report.
 */
public final class PreflightReportText {

    static final String BUNDLE_NAME = "preflight-report";

    private PreflightReportText() {}

    /**
     * Report locale: explicit {@code reportLanguage} request field first, then the servlet/session
     * locale (set by {@code ?lang=} or the server default), then English.
     */
    public static Locale localeFor(PrintPreflightRequest request) {
        String tag = request != null ? request.getReportLanguage() : null;
        if (tag != null && !tag.isBlank()) {
            return Locale.forLanguageTag(tag.trim().replace('_', '-'));
        }
        Locale session = LocaleContextHolder.getLocale();
        return session != null ? session : Locale.ENGLISH;
    }

    /**
     * Loads the report bundle for the request. {@code getNoFallbackControl} keeps an unsupported
     * tag (e.g. {@code ja} on a French-default host) on the English base bundle instead of leaking
     * the JVM default locale into the report.
     */
    public static ResourceBundle bundleFor(PrintPreflightRequest request) {
        return ResourceBundle.getBundle(
                BUNDLE_NAME,
                localeFor(request),
                ResourceBundle.Control.getNoFallbackControl(
                        ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    /** Raw string lookup; returns {@code fallback} when the bundle or the key is missing. */
    public static String t(ResourceBundle bundle, String key, String fallback) {
        if (bundle == null) {
            return fallback;
        }
        try {
            return bundle.containsKey(key) ? bundle.getString(key) : fallback;
        } catch (MissingResourceException e) {
            return fallback;
        }
    }

    /**
     * Pattern lookup plus {@code {n}} substitution — deliberately not MessageFormat so French
     * apostrophes need no escaping in the properties files.
     */
    public static String msg(ResourceBundle bundle, String key, Object... args) {
        String out = t(bundle, key, key);
        for (int i = 0; i < args.length; i++) {
            out = out.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return out;
    }
}
