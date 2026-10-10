package stirling.software.common.model.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the {@code report.<ns>.*} fact namespace a step-report type publishes under, so the
 * descriptor can be derived from the record itself rather than duplicated somewhere it can drift.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ReportNamespace {

    String value();
}
