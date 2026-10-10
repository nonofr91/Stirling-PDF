package stirling.software.common.model.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint as emitting an {@code X-Stirling-Tool-Report} header — the step report a
 * pipeline routes and gates on. The report type carries the namespace and field metadata; this
 * annotation only says "this endpoint emits it" and at which level.
 *
 * <p>Published into the spec as {@code x-stirling-report} by {@code ToolReportOperationCustomizer}
 * and generated into the frontend's report catalog, so the offered routing fields are always the
 * fields the endpoint actually emits.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolReport {

    /** The {@link ReportNamespace}-annotated report type(s) this endpoint's response carries. */
    Class<?>[] value();

    /** True when this endpoint emits the {@link ReportProducedBy#FIX} fields too. */
    boolean fix() default false;
}
