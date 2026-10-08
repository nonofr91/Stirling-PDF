package stirling.software.proprietary.policy.output;

import java.util.List;
import java.util.Map;

import org.springframework.core.io.Resource;

import stirling.software.proprietary.policy.model.PolicyInputs;
import stirling.software.proprietary.policy.model.PolicyRun;

import tools.jackson.databind.JsonNode;

/**
 * Context for one run's output delivery. {@code policyId} is null for ad-hoc pipelines; when
 * present, sinks record outputs in the processed-file ledger so the producing policy does not
 * re-ingest them. {@code inputs} carries the run's inputs so a sink that writes back to where the
 * input lives (e.g. a new version of a stored file) can correlate output to origin. {@code
 * fileOwner} is resolved from the input source or uploader by the engine; storage outputs require
 * it and must not substitute the destination's owner. {@code documentIdentity} is a stable
 * reference to the source document so a re-run upserts a vector document instead of duplicating it.
 * {@code stepReports} maps each output file to the most recent step report attached to it — the
 * same node routing rules match on ({@code report.preflight.*}) — so a notification sink can render
 * verdicts and counts into its message. {@code policyName} is the pipeline's display name. {@code
 * runReport} is the terminal step's report, kept separately because a report-producing step may
 * emit no file at all (e.g. a JSON-only verdict): sinks that would otherwise see an empty delivery
 * still have the run's outcome to report.
 */
public record OutputDelivery(
        String runId,
        String policyId,
        PolicyInputs inputs,
        String fileOwner,
        String documentIdentity,
        String policyName,
        Map<Resource, JsonNode> stepReports,
        JsonNode runReport) {

    public OutputDelivery {
        inputs = inputs == null ? PolicyInputs.of(List.of()) : inputs;
        stepReports = stepReports == null ? Map.of() : stepReports;
    }

    public OutputDelivery(String runId, String policyId, PolicyInputs inputs, String fileOwner) {
        this(runId, policyId, inputs, fileOwner, null, null, null, null);
    }

    public OutputDelivery(
            String runId,
            String policyId,
            PolicyInputs inputs,
            String fileOwner,
            String documentIdentity) {
        this(runId, policyId, inputs, fileOwner, documentIdentity, null, null, null);
    }

    public OutputDelivery(String runId, String policyId, PolicyInputs inputs) {
        this(runId, policyId, inputs, null, null, null, null, null);
    }

    public OutputDelivery(String runId, String policyId) {
        this(runId, policyId, null, null, null, null, null, null);
    }

    /**
     * The step report attached to an output of this delivery, or null when the file reported none.
     */
    public JsonNode reportFor(Resource file) {
        return stepReports.get(file);
    }

    /** Reuses the run's document reference, scoped to its source or submitting user. */
    public static OutputDelivery forRun(PolicyRun run, PolicyInputs inputs) {
        String identity = null;
        if (inputs.primary().size() == 1) {
            if (run.getSourceId() != null) {
                String reference =
                        run.getFileIdentity() != null
                                ? run.getFileIdentity()
                                : inputs.primary().getFirst().getDescription();
                identity = "source:" + run.getSourceId() + "\n" + reference;
            } else if (run.getFileIdentity() != null) {
                identity = "user:" + run.getTriggeringUser() + "\n" + run.getFileIdentity();
            }
        }
        return new OutputDelivery(run.getRunId(), run.getPolicyId(), inputs, null, identity);
    }
}
