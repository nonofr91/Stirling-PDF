package stirling.software.common.config.swagger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import io.swagger.v3.oas.models.Operation;

import stirling.software.common.model.tool.ReportField;
import stirling.software.common.model.tool.ReportNamespace;
import stirling.software.common.model.tool.ReportProducedBy;
import stirling.software.common.model.tool.ToolReport;

/**
 * Publishes each {@link ToolReport} into the spec as {@code x-stirling-report}: the report
 * namespace, whether the endpoint emits corrector-level fields, and the field descriptors the
 * report type declares — so the frontend's routing and gate editors always offer the fields the
 * pipeline actually produces.
 */
@Component
public class ToolReportOperationCustomizer implements GlobalOperationCustomizer {

    public static final String EXTENSION_NAME = "x-stirling-report";

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        ToolReport declaration = handlerMethod.getMethodAnnotation(ToolReport.class);
        if (declaration == null) {
            return operation;
        }
        List<Map<String, Object>> reports = new ArrayList<>();
        for (Class<?> type : declaration.value()) {
            reports.add(toExtension(type, declaration.fix()));
        }
        operation.addExtension(EXTENSION_NAME, reports);
        return operation;
    }

    private static Map<String, Object> toExtension(Class<?> type, boolean fix) {
        ReportNamespace namespace = type.getAnnotation(ReportNamespace.class);
        if (namespace == null) {
            throw new IllegalStateException(
                    type.getName() + " is declared in @ToolReport but lacks @ReportNamespace");
        }
        if (!type.isRecord()) {
            throw new IllegalStateException(
                    type.getName() + " is declared in @ToolReport but is not a record");
        }
        Map<String, Object> extension = new LinkedHashMap<>();
        extension.put("namespace", namespace.value());
        extension.put("fix", fix);
        List<Map<String, Object>> fields = new ArrayList<>();
        for (var component : type.getRecordComponents()) {
            ReportField field = component.getAnnotation(ReportField.class);
            // A non-fix endpoint emits only the analysis fields — fix fields stay null/empty on
            // its report, so offering them would produce conditions that can never match.
            if (field == null || (!fix && field.producedBy() == ReportProducedBy.FIX)) {
                continue;
            }
            fields.add(toField(component.getName(), field));
        }
        extension.put("fields", fields);
        return extension;
    }

    private static Map<String, Object> toField(String name, ReportField field) {
        Map<String, Object> extension = new LinkedHashMap<>();
        extension.put("name", name);
        extension.put("kind", field.kind().serialize());
        extension.put("producedBy", field.producedBy().serialize());
        if (field.vocabulary() != ReportField.NoVocabulary.class) {
            extension.put("values", enumNames(field.vocabulary()));
        } else if (field.values().length > 0) {
            extension.put("values", List.of(field.values()));
        }
        if (!field.valueLabelPrefix().isEmpty()) {
            extension.put("valueLabelPrefix", field.valueLabelPrefix());
        }
        extension.put("labelKey", field.labelKey());
        extension.put("labelDefault", field.labelDefault());
        return extension;
    }

    private static List<String> enumNames(Class<? extends Enum<?>> vocabulary) {
        return Arrays.stream(vocabulary.getEnumConstants()).map(Enum::name).toList();
    }
}
