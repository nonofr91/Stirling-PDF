package stirling.software.SPDF.service.preflight;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import stirling.software.SPDF.model.api.security.PrintPreflightReport.Category;
import stirling.software.SPDF.model.api.security.PrintPreflightReport.Severity;

/**
 * Every finding code the preflight can emit, with its default severity and category. Doubles as the
 * vocabulary of {@code disabledChecks}: a check in the disabled set is skipped (cheap checks are
 * simply not reported; expensive checks that need a render pass are not run at all).
 */
public enum PreflightCheck {
    FONT_NOT_EMBEDDED(Category.FONTS, Severity.ERROR),
    FONT_TYPE3(Category.FONTS, Severity.WARNING),
    COLOR_RGB_USED(Category.COLOR, Severity.WARNING),
    COLOR_SPOT(Category.COLOR, Severity.INFO),
    IMAGE_LOW_RES(Category.IMAGES, Severity.WARNING),
    TRIMBOX_MISSING(Category.GEOMETRY, Severity.WARNING),
    BLEED_MISSING(Category.GEOMETRY, Severity.ERROR),
    BLEED_INSUFFICIENT(Category.GEOMETRY, Severity.ERROR),
    BLEED_UNPAINTED(Category.GEOMETRY, Severity.WARNING, true),
    ANNOTATION_IN_TRIM(Category.CONTENT, Severity.WARNING),
    HAIRLINE(Category.CONTENT, Severity.WARNING),
    TRANSPARENCY(Category.CONTENT, Severity.INFO),
    OPTIONAL_CONTENT(Category.CONTENT, Severity.INFO),
    MIXED_PAGE_SIZES(Category.DOCUMENT, Severity.INFO),
    CONTENT_PARSE_ERROR(Category.CONTENT, Severity.WARNING),
    OVERPRINT_WHITE(Category.COLOR, Severity.ERROR),
    OVERPRINT_BLACK(Category.COLOR, Severity.WARNING),
    TEXT_RICH_BLACK(Category.COLOR, Severity.WARNING),
    TEXT_SMALL(Category.CONTENT, Severity.WARNING),
    SAFETY_MARGIN(Category.GEOMETRY, Severity.WARNING),
    EMPTY_PAGE(Category.DOCUMENT, Severity.INFO),
    IMAGE_OVERSAMPLED(Category.IMAGES, Severity.INFO),
    IMAGE_1BIT_LOW_RES(Category.IMAGES, Severity.WARNING),
    INK_COVERAGE_HIGH(Category.COLOR, Severity.WARNING),
    SPOT_ALIAS(Category.COLOR, Severity.WARNING),
    SPOT_COUNT(Category.COLOR, Severity.INFO),
    REGISTRATION_PAINT(Category.COLOR, Severity.INFO),
    INVISIBLE_TEXT(Category.CONTENT, Severity.INFO),
    PATTERN_USED(Category.COLOR, Severity.INFO),
    SHADING_USED(Category.COLOR, Severity.INFO),
    OBJECT_OUTSIDE_PAGE(Category.GEOMETRY, Severity.INFO),
    OUTPUT_INTENT_MISSING(Category.COLOR, Severity.WARNING),
    EMBEDDED_FILES(Category.DOCUMENT, Severity.WARNING),
    FORM_FIELDS(Category.DOCUMENT, Severity.WARNING),
    XFA_FORM(Category.DOCUMENT, Severity.WARNING),
    SIGNATURES(Category.DOCUMENT, Severity.INFO),
    JAVASCRIPT(Category.DOCUMENT, Severity.INFO),
    USER_UNIT(Category.GEOMETRY, Severity.WARNING),
    LAYERS_PRINT_OFF(Category.CONTENT, Severity.INFO),
    CROPBOX_NE_MEDIA(Category.GEOMETRY, Severity.INFO);

    private final Category category;
    private final Severity severity;

    /** True when the check needs a rendered page pass rather than content inspection. */
    private final boolean renderPass;

    PreflightCheck(Category category, Severity severity) {
        this(category, severity, false);
    }

    PreflightCheck(Category category, Severity severity, boolean renderPass) {
        this.category = category;
        this.severity = severity;
        this.renderPass = renderPass;
    }

    public String code() {
        return name();
    }

    public Category category() {
        return category;
    }

    public Severity severity() {
        return severity;
    }

    public boolean needsRenderPass() {
        return renderPass;
    }

    public static PreflightCheck byCode(String code) {
        if (code == null) {
            return null;
        }
        try {
            return valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Request codes normalized to check instances; unknown codes are dropped (the request just
     * asked to disable a check that does not exist).
     */
    public static Set<PreflightCheck> disabledSet(Collection<String> codes) {
        Set<PreflightCheck> disabled = new LinkedHashSet<>();
        if (codes != null) {
            for (String code : codes) {
                PreflightCheck check = byCode(code);
                if (check != null) {
                    disabled.add(check);
                }
            }
        }
        return disabled;
    }
}
