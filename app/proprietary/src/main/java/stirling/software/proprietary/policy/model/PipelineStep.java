package stirling.software.proprietary.policy.model;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import stirling.software.proprietary.document.conditions.Condition;

/**
 * A single tool invocation. {@code operation} is a Stirling endpoint path (e.g. {@code
 * /api/v1/misc/compress-pdf}) per the {@code InternalApiClient} convention; {@code parameters} are
 * scalar form fields.
 *
 * <p>{@code fileParameters} maps a tool's named file field (e.g. {@code stampImage}, beyond the
 * primary {@code fileInput} stream) to an asset key in the run's supporting-file store, keeping
 * supporting inputs out of the document stream that flows step to step. The key is either {@code
 * asset:<id>} for a stored supporting file or a plain name supplied with the run itself; see {@code
 * PolicyAssetRefs}.
 *
 * <p>{@code when} gates the step per document: files whose facts (document.* plus the carried step
 * {@code report}) match run through the tool; the others bypass it untouched. Null means
 * unconditional - the default for every step written before the field existed.
 */
public record PipelineStep(
        String operation,
        Map<String, Object> parameters,
        Map<String, String> fileParameters,
        @JsonInclude(JsonInclude.Include.NON_NULL) Condition when) {

    public PipelineStep {
        parameters = parameters == null ? Map.of() : parameters;
        fileParameters = fileParameters == null ? Map.of() : fileParameters;
    }

    /** A step with no supporting-file bindings and no gate. */
    public PipelineStep(String operation, Map<String, Object> parameters) {
        this(operation, parameters, Map.of(), null);
    }

    /** A step with supporting-file bindings and no gate. */
    public PipelineStep(
            String operation, Map<String, Object> parameters, Map<String, String> fileParameters) {
        this(operation, parameters, fileParameters, null);
    }
}
