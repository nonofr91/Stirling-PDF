package stirling.software.common.model.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A record component that is a pipeline fact — offerable in routing rules and step gates. The
 * metadata here is what the condition editors need to render a meaningful picker: the comparison
 * kind, the values to offer, and which producer level emits it.
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ReportField {

    ReportFieldKind kind();

    /** ANALYSIS fields ride every report variant; FIX fields only a corrector step emits. */
    ReportProducedBy producedBy() default ReportProducedBy.ANALYSIS;

    /** The enum whose constant names are the matchable values, for {@code CODE_LIST} kinds. */
    Class<? extends Enum<?>> vocabulary() default NoVocabulary.class;

    /** Closed value set for {@code ENUM} kinds. */
    String[] values() default {};

    /** i18n key prefix each member of {@link #values()} resolves under. */
    String valueLabelPrefix() default "";

    String labelKey();

    /** English fallback when the locale lacks {@link #labelKey()}. */
    String labelDefault();

    /** Marker for "no enum vocabulary" — annotations cannot default to null. */
    enum NoVocabulary {}
}
