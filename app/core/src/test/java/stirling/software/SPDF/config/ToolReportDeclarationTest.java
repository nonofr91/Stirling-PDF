package stirling.software.SPDF.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import io.swagger.v3.oas.models.Operation;

import stirling.software.SPDF.controller.api.security.PrintPreflightController;
import stirling.software.SPDF.model.api.security.PrintPreflightRequest;
import stirling.software.common.config.swagger.ToolReportOperationCustomizer;

/**
 * The {@code x-stirling-report} extension is the contract the frontend builds its routing fields
 * from: these tests pin which endpoints declare it and at which level, so a new variant emitting
 * the header without the annotation — or a fix field leaking onto an analysis endpoint — fails here
 * rather than producing unroutable gates.
 */
class ToolReportDeclarationTest {

    private final ToolReportOperationCustomizer customizer = new ToolReportOperationCustomizer();

    @Test
    void fixEndpointDeclaresEveryField() throws NoSuchMethodException {
        List<Map<String, Object>> reports =
                reports("printPreflightFix", PrintPreflightRequest.class);
        assertEquals(1, reports.size());
        Map<String, Object> report = reports.get(0);
        assertEquals("preflight", report.get("namespace"));
        assertEquals(Boolean.TRUE, report.get("fix"));
        Set<String> fixFields = fieldNames(report, "fix");
        assertTrue(
                fixFields.containsAll(
                        Set.of(
                                "preErrors",
                                "preWarnings",
                                "fixupsApplied",
                                "fixupsSkipped",
                                "preFailingChecks")),
                "the corrector level must carry the fix-only fields");
        assertFalse(fieldNames(report, "analysis").isEmpty());
    }

    @Test
    void analysisEndpointsEmitOnlyAnalysisFields() throws NoSuchMethodException {
        for (String method : List.of("printPreflightAnnotated", "printPreflightReport")) {
            List<Map<String, Object>> reports = reports(method, PrintPreflightRequest.class);
            assertEquals(1, reports.size(), method);
            Map<String, Object> report = reports.get(0);
            assertEquals(Boolean.FALSE, report.get("fix"), method);
            assertTrue(
                    fieldNames(report, "fix").isEmpty(),
                    method + " must not offer fix-only fields it never emits");
            assertFalse(fieldNames(report, "analysis").isEmpty(), method);
        }
    }

    @Test
    void jsonOnlyVariantsDeclareNoReport() throws NoSuchMethodException {
        // The plain JSON report and the fix preview never emit X-Stirling-Tool-Report on a file,
        // so the report cannot ride a routed output — they stay out of the catalog on purpose.
        assertNull(extension("printPreflight", PrintPreflightRequest.class));
        assertNull(extension("printPreflightFixPreview", PrintPreflightRequest.class));
    }

    @Test
    void codeListFieldsCarryTheVocabularyValues() throws NoSuchMethodException {
        List<Map<String, Object>> reports =
                reports("printPreflightFix", PrintPreflightRequest.class);
        Map<String, Object> fixupsApplied = fieldByName(reports.get(0), "fixupsApplied");
        assertNotNull(fixupsApplied);
        assertEquals("code-list", fixupsApplied.get("kind"));
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) fixupsApplied.get("values");
        assertNotNull(values, "a code-list field must carry its vocabulary");
        assertTrue(values.contains("EXTEND_BLEED"));
        assertTrue(values.contains("PURE_BLACK_TEXT"));
    }

    private List<Map<String, Object>> reports(String method, Class<?>... params)
            throws NoSuchMethodException {
        Object extension = extension(method, params);
        assertNotNull(extension, "no " + ToolReportOperationCustomizer.EXTENSION_NAME);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reports = (List<Map<String, Object>>) extension;
        return reports;
    }

    private Object extension(String method, Class<?>... params) throws NoSuchMethodException {
        // The customizer only reads method annotations, so a throwaway bean stands in for the
        // controller instance a real handler method would carry.
        Operation operation =
                customizer.customize(
                        new Operation(),
                        new HandlerMethod(
                                new Object(),
                                PrintPreflightController.class.getDeclaredMethod(method, params)));
        return operation.getExtensions() == null
                ? null
                : operation.getExtensions().get(ToolReportOperationCustomizer.EXTENSION_NAME);
    }

    private static Set<String> fieldNames(Map<String, Object> report, String producedBy) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) report.get("fields");
        return fields.stream()
                .filter(field -> producedBy.equals(field.get("producedBy")))
                .map(field -> (String) field.get("name"))
                .collect(Collectors.toSet());
    }

    private static Map<String, Object> fieldByName(Map<String, Object> report, String name) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) report.get("fields");
        return fields.stream()
                .filter(field -> name.equals(field.get("name")))
                .findFirst()
                .orElse(null);
    }
}
