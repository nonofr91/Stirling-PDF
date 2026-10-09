/**
 * The tool operations the Policies feature can run, each a typed {@link ToolOperationDescriptor}.
 * Source of truth for the catalogue, wizard, and wire conversion. Add a tool here to use it in a
 * policy - the catalogue can't reference an untyped operation.
 */

import {
  describeToolOperation,
  type BidirectionalToolConfig,
} from "@app/hooks/tools/shared/toolOperationDescriptor";
import { redactOperationConfig } from "@app/hooks/tools/redact/useRedactOperation";
import { sanitizeOperationConfig } from "@app/hooks/tools/sanitize/useSanitizeOperation";
import { timestampPdfOperationConfig } from "@app/hooks/tools/timestampPdf/useTimestampPdfOperation";
import { addWatermarkOperationConfig } from "@app/hooks/tools/addWatermark/useAddWatermarkOperation";
import { ocrOperationConfig } from "@app/hooks/tools/ocr/useOCROperation";
import { flattenOperationConfig } from "@app/hooks/tools/flatten/useFlattenOperation";
import { compressOperationConfig } from "@app/hooks/tools/compress/useCompressOperation";
import {
  INGEST_ENDPOINT,
  ingestOperationConfig,
} from "@app/policies/ingestOperation";
import { pdfaOperationConfig } from "@app/policies/pdfaOperation";
import { setPageBoxesOperationConfig } from "@app/hooks/tools/setPageBoxes/useSetPageBoxesOperation";
import { adjustPageScaleOperationConfig } from "@app/hooks/tools/adjustPageScale/useAdjustPageScaleOperation";
import { cutContourOperationConfig } from "@app/hooks/tools/cutContour/useCutContourOperation";
import { textToOutlinesOperationConfig } from "@app/hooks/tools/textToOutlines/useTextToOutlinesOperation";
import { cropOperationConfig } from "@app/hooks/tools/crop/useCropOperation";
import type { ToolEndpoint } from "@app/types/toolApiTypes";
import type { WirePipelineStep } from "@app/policies/types";

/**
 * Endpoints for AI-dispatched policy tools. Intentionally NOT part of the generated
 * {@link ToolEndpoint} union: the backend controllers are `@Hidden` and `/api/v1/ai/tools/` is
 * deliberately excluded from tool-model generation, so they can't be typed like standard tools.
 */
export type AiPolicyEndpoint = "/api/v1/ai/tools/classify-and-label";

/**
 * Endpoints for third-party integration steps. Excluded from the generated {@link ToolEndpoint}
 * union for the same reason as the AI tools: `/api/v1/integration/` is not one of the tool
 * namespaces the generator reads.
 */
export type IntegrationPolicyEndpoint =
  | "/api/v1/integration/external-api-call"
  | "/api/v1/integration/purview-apply-label"
  | "/api/v1/integration/purview-read-label";

/** An endpoint typed here rather than by the generator. */
export type UntypedPolicyEndpoint =
  | AiPolicyEndpoint
  | IntegrationPolicyEndpoint;

/** A tool usable in a policy whose endpoint isn't in the generated union. */
export interface AiToolDescriptor<TParams> {
  readonly endpoint: UntypedPolicyEndpoint;
  readonly defaultParameters: TParams;
  toApi(params: TParams): Record<string, unknown>;
  fromApi(api: Record<string, unknown>): TParams;
}

/**
 * Describe an AI-dispatched policy tool. AI tools carry no tunable parameters today, so params are
 * empty and the (de)serializers are identity over an empty object.
 */
export function describeAiToolOperation(
  endpoint: AiPolicyEndpoint,
): AiToolDescriptor<Record<string, never>> {
  return {
    endpoint,
    defaultParameters: {},
    toApi: () => ({}),
    fromApi: () => ({}),
  };
}

/**
 * Describe an integration step. Unlike AI tools these carry real parameters, so each supplies its
 * own defaults; every one of them needs a `connectionId` naming the stored connection that holds
 * the endpoint and credentials. Values cross the wire as form fields, hence the string round-trip.
 */
function describeIntegrationOperation<TParams extends Record<string, string>>(
  endpoint: IntegrationPolicyEndpoint,
  defaultParameters: TParams,
): AiToolDescriptor<TParams> {
  return {
    endpoint,
    defaultParameters,
    // Drop blanks rather than send empty fields: the backend distinguishes "absent" from
    // "empty" for optional params like `path` and `labelName`.
    toApi: (params) =>
      Object.fromEntries(
        Object.entries(params).filter(([, value]) => value !== ""),
      ),
    fromApi: (api) => {
      const out = { ...defaultParameters } as Record<string, string>;
      for (const key of Object.keys(defaultParameters)) {
        const value = api[key];
        if (value !== undefined && value !== null) out[key] = String(value);
      }
      return out as TParams;
    },
  };
}

const COMPLIANCE_CHECK_ENDPOINT =
  "/api/v1/security/validate-compliance" satisfies ToolEndpoint;

// Annotated rather than inferred, as pdfaOperationConfig is: an unannotated object literal widens
// `endpoint` to string, which no longer satisfies describeToolOperation's `CE extends ToolEndpoint`.
const complianceCheckOperationConfig: BidirectionalToolConfig<
  Record<string, never>,
  typeof COMPLIANCE_CHECK_ENDPOINT
> = {
  endpoint: COMPLIANCE_CHECK_ENDPOINT,
  defaultParameters: {},
  toApiParams: () => ({}),
  fromApiParams: () => ({}),
};

const PREFLIGHT_ANNOTATED_ENDPOINT =
  "/api/v1/security/print-preflight-annotated" satisfies ToolEndpoint;

const PREFLIGHT_FIX_ENDPOINT =
  "/api/v1/security/print-preflight-fix" satisfies ToolEndpoint;

/**
 * Inspect-only preflight as a policy step, pinned to the annotated endpoint: the JSON report
 * endpoint returns no file (the document would drop out of the pipeline), while the annotated
 * endpoint hands back the copy with every issue framed — exactly what a quarantine reviewer
 * needs — and emits the {@code report.preflight.verdict} header routing reads. Module-private:
 * the namesake registry config in {@code usePrintPreflightOperation} is the dynamic-endpoint
 * interactive variant, not this stored-step shape.
 */
const printPreflightOperationConfig: BidirectionalToolConfig<
  { profileName: string },
  typeof PREFLIGHT_ANNOTATED_ENDPOINT
> = {
  endpoint: PREFLIGHT_ANNOTATED_ENDPOINT,
  defaultParameters: { profileName: "" },
  toApiParams: (params) =>
    params.profileName.trim() !== ""
      ? { profileName: params.profileName.trim() }
      : {},
  fromApiParams: (api) => ({ profileName: api.profileName ?? "" }),
};

/**
 * The fix variant of print preflight, pinned to its own endpoint: a hot folder fixes what it can,
 * then routing reads the emitted {@code report.preflight.verdict} (fail -> quarantine). The
 * interactive tool's dynamic-endpoint config can't describe a stored step, so this declares the
 * wire shape directly — `fixups` travels as a comma-separated list over the wire.
 */
const printPreflightFixOperationConfig: BidirectionalToolConfig<
  { profileName: string; fixups: string },
  typeof PREFLIGHT_FIX_ENDPOINT
> = {
  endpoint: PREFLIGHT_FIX_ENDPOINT,
  defaultParameters: { profileName: "", fixups: "" },
  toApiParams: (params) => ({
    ...(params.profileName.trim() !== ""
      ? { profileName: params.profileName.trim() }
      : {}),
    ...(params.fixups.trim() !== ""
      ? {
          fixups: params.fixups
            .split(",")
            .map((code) => code.trim())
            .filter((code) => code !== ""),
        }
      : {}),
  }),
  fromApiParams: (api) => ({
    profileName: api.profileName ?? "",
    fixups: (api.fixups ?? []).join(","),
  }),
};

export const POLICY_OPERATIONS = {
  redact: describeToolOperation(
    "/api/v1/security/auto-redact",
    redactOperationConfig,
  ),
  sanitize: describeToolOperation(
    "/api/v1/security/sanitize-pdf",
    sanitizeOperationConfig,
  ),
  // RFC 3161 timestamp. Already a SISO tool; surfacing it here is what makes a signature durable
  // in a pipeline (PAdES-LTV), and only a SHA-256 hash reaches the TSA - never the document.
  timestampPdf: describeToolOperation(
    "/api/v1/security/timestamp-pdf",
    timestampPdfOperationConfig,
  ),
  watermark: describeToolOperation(
    "/api/v1/security/add-watermark",
    addWatermarkOperationConfig,
  ),
  ocr: describeToolOperation("/api/v1/misc/ocr-pdf", ocrOperationConfig),
  flatten: describeToolOperation(
    "/api/v1/misc/flatten",
    flattenOperationConfig,
  ),
  compress: describeToolOperation(
    "/api/v1/misc/compress-pdf",
    compressOperationConfig,
  ),
  // Long-term archival format: embeds fonts and colour profiles so the document still renders the
  // same decades from now, which is what a retention or archival requirement actually asks for.
  pdfa: describeToolOperation("/api/v1/convert/pdf/pdfa", pdfaOperationConfig),
  // The gate: no options, so a stored step carries nothing that could drift.
  complianceCheck: describeToolOperation(
    "/api/v1/security/validate-compliance",
    complianceCheckOperationConfig,
  ),
  classify: describeAiToolOperation("/api/v1/ai/tools/classify-and-label"),
  // The prepress toolchain — the two preflight steps emit the verdict routing reads (inspect
  // without changing the file, or fix first); the rest are the geometry/content steps a press
  // folder usually runs first.
  printPreflight: describeToolOperation(
    PREFLIGHT_ANNOTATED_ENDPOINT,
    printPreflightOperationConfig,
  ),
  printPreflightFix: describeToolOperation(
    PREFLIGHT_FIX_ENDPOINT,
    printPreflightFixOperationConfig,
  ),
  setPageBoxes: describeToolOperation(
    "/api/v1/general/set-page-boxes",
    setPageBoxesOperationConfig,
  ),
  scalePages: describeToolOperation(
    "/api/v1/general/scale-pages",
    adjustPageScaleOperationConfig,
  ),
  cutContour: describeToolOperation(
    "/api/v1/general/cut-contour",
    cutContourOperationConfig,
  ),
  textToOutlines: describeToolOperation(
    "/api/v1/misc/text-to-outlines",
    textToOutlinesOperationConfig,
  ),
  crop: describeToolOperation("/api/v1/general/crop", cropOperationConfig),
  ingest: describeToolOperation(INGEST_ENDPOINT, ingestOperationConfig),
  purviewApplyLabel: describeIntegrationOperation(
    "/api/v1/integration/purview-apply-label",
    {
      connectionId: "",
      labelId: "",
      labelName: "",
      method: "STANDARD",
    },
  ),
  purviewReadLabel: describeIntegrationOperation(
    "/api/v1/integration/purview-read-label",
    { connectionId: "" },
  ),
  externalApiCall: describeIntegrationOperation(
    "/api/v1/integration/external-api-call",
    {
      connectionId: "",
      path: "",
      method: "POST",
      // multipart | json | binary — how the document reaches the API.
      bodyMode: "multipart",
      fileFieldName: "file",
      // Default to leaving the document alone; `replace` opts in to the response becoming it.
      responseMode: "report",
      // For `replace`, where the document actually is: inline (leave both blank), behind a URL in
      // the response body / a header, and/or inside a returned archive.
      resultUrlPath: "",
      resultUrlHeader: "",
      responseSelect: "",
      // A dotted path into the JSON response that must be true, or the step fails: the scan gate.
      requireTrue: "",
      // JSON objects whose values may reference {{document.*}}, {{classification.*}},
      // {{sensitivityLabel.*}} and {{run.*}}.
      fields: "",
      headers: "",
      // A vendor-shaped JSON body; wins over bodyMode/fields when set. {{document.base64}}
      // carries the file, for APIs that nest it inside a JSON document.
      bodyTemplate: "",
      includeContext: "false",
      includeFile: "true",
      // Which catalogue entry the operator chose, and their answers to its fields. Persisted so a
      // saved step reopens on the same operation rather than back at the picker; JSON-encoded to
      // keep every step parameter a flat string, as `fields` and `headers` already are.
      operationId: "",
      operationValues: "",
    },
  ),
} as const;

/**
 * Endpoints whose response is a PDF carrying the X-Stirling-Tool-Report header — the only steps
 * whose {@code report.preflight.*} a route can actually read, since the report attaches to the
 * produced file. The JSON variants (plain report, fix preview) emit no file, so their verdict
 * never reaches a routed output — they don't open the gate and are never injected.
 */
export const PREFLIGHT_STEP_ENDPOINTS: ReadonlySet<string> = new Set([
  "/api/v1/security/print-preflight-annotated",
  "/api/v1/security/print-preflight-report",
  "/api/v1/security/print-preflight-fix",
]);

/**
 * The endpoint whose report carries the pre-fixup state ({@code report.preflight.pre*} and
 * {@code fixupsApplied}); a plain analysis never emits those fields, so gates and routes on them
 * only make sense once a fix step ran.
 */
export const PREFLIGHT_FIX_STEP_ENDPOINTS: ReadonlySet<string> = new Set([
  "/api/v1/security/print-preflight-fix",
]);

export type PolicyToolId = keyof typeof POLICY_OPERATIONS;

export type PolicyParams<Id extends PolicyToolId> =
  (typeof POLICY_OPERATIONS)[Id]["defaultParameters"];

/** Discriminated on `toolId` so `params` matches the tool. */
export type PolicyToolStep = {
  [Id in PolicyToolId]: { toolId: Id; params: PolicyParams<Id> };
}[PolicyToolId];

export type PolicyToolStepOf<Id extends PolicyToolId> = Extract<
  PolicyToolStep,
  { toolId: Id }
>;

const POLICY_TOOL_IDS = Object.keys(POLICY_OPERATIONS) as PolicyToolId[];

const TOOL_ID_BY_ENDPOINT = new Map<string, PolicyToolId>(
  POLICY_TOOL_IDS.map((id) => [POLICY_OPERATIONS[id].endpoint, id]),
);

/** A policy step's endpoint: a generated {@link ToolEndpoint} or one typed here. */
export type PolicyEndpoint = ToolEndpoint | UntypedPolicyEndpoint;

export function policyEndpoint(toolId: PolicyToolId): PolicyEndpoint {
  return POLICY_OPERATIONS[toolId].endpoint;
}

/** Tool id for an endpoint path, or null if it isn't a policy tool. */
export function policyToolIdForEndpoint(endpoint: string): PolicyToolId | null {
  return TOOL_ID_BY_ENDPOINT.get(endpoint) ?? null;
}

/** A step for `toolId`, partial params merged over the tool's defaults. */
export function policyStep<Id extends PolicyToolId>(
  toolId: Id,
  params: Partial<PolicyParams<Id>> = {},
): PolicyToolStepOf<Id> {
  const defaults = POLICY_OPERATIONS[toolId].defaultParameters as object;
  return {
    toolId,
    params: { ...defaults, ...(params as object) },
  } as PolicyToolStepOf<Id>;
}

export function policyStepToWire(step: PolicyToolStep): WirePipelineStep {
  return serializeStep(step);
}

/**
 * Minimal runtime shape shared by standard tool descriptors and {@link AiToolDescriptor}s — enough
 * to (de)serialize a step at the wire boundary regardless of how its endpoint is typed.
 */
interface PolicyOperation<TParams> {
  readonly endpoint: string;
  readonly defaultParameters: TParams;
  toApi(params: TParams): Record<string, unknown>;
  fromApi(api: Record<string, unknown>): TParams;
}

// Generic over the id so `params` stays correlated with the descriptor; TS can't do that through
// the union, so `op` is widened here (a contained cast at the wire boundary).
function serializeStep<Id extends PolicyToolId>(step: {
  toolId: Id;
  params: PolicyParams<Id>;
}): WirePipelineStep {
  const op = POLICY_OPERATIONS[step.toolId] as unknown as PolicyOperation<
    PolicyParams<Id>
  >;
  return {
    operation: op.endpoint,
    parameters: op.toApi(step.params),
  };
}

/** Wire step -> typed policy step, or null if the endpoint isn't a policy tool. */
export function policyStepFromWire(
  wire: WirePipelineStep,
): PolicyToolStep | null {
  const toolId = policyToolIdForEndpoint(wire.operation);
  if (!toolId) return null;
  return deserializeStep(toolId, wire.parameters);
}

function deserializeStep<Id extends PolicyToolId>(
  toolId: Id,
  parameters: Record<string, unknown>,
): PolicyToolStepOf<Id> {
  const op = POLICY_OPERATIONS[toolId] as unknown as PolicyOperation<
    PolicyParams<Id>
  >;
  // Wire params are untyped JSON; this is the one point they enter the typed model.
  const params = op.fromApi(parameters);
  return { toolId, params } as unknown as PolicyToolStepOf<Id>;
}
