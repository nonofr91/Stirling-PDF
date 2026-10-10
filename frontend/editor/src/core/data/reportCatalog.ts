/**
 * Declarative description of the step reports tools stamp on their output via
 * the `X-Stirling-Tool-Report` header — which endpoints emit which
 * `report.<namespace>.*` facts, and what each field carries. The pipeline
 * condition editor builds its field options and value pickers from this
 * catalog instead of hardcoding paths (contract R5, see
 * devGuide/prepress-tool-contract.md).
 */

export interface ReportFieldDescriptor {
  /** Full fact path, e.g. `report.preflight.verdict`. */
  path: string;
  /** Field kind — decides the value picker. */
  kind: "enum" | "count" | "code-list";
  /** For `code-list` fields: which catalog the values come from. */
  vocabulary?: "checks" | "fixups";
  /** For `enum` fields: the literal values offered. */
  values?: readonly string[];
  /** For `enum` fields: i18n key prefix — a value `v` labels as `<prefix>.<v>`. */
  valueLabelPrefix?: string;
  /**
   * Which step variant emits the field. `analysis` fields exist after any
   * reporting step; `fix` fields only once a corrector step ran (the pre-fix
   * state and the fixup outcome lists).
   */
  producedBy: "analysis" | "fix";
  /** i18n key for the option label in condition editors. */
  labelKey: string;
  /** English fallback for the label. */
  labelDefault: string;
}

/**
 * One report namespace: the endpoints whose responses carry the header, and
 * the fields they emit. `fixEndpoints` is the subset that additionally emits
 * `producedBy: "fix"` fields.
 */
export interface ReportProducerDescriptor {
  namespace: string;
  /** Endpoints emitting `report.<namespace>.*` on the produced file. */
  endpoints: readonly string[];
  /** Subset emitting corrector-step fields (`pre*`, `fixupsApplied`…). */
  fixEndpoints: readonly string[];
  fields: readonly ReportFieldDescriptor[];
}

export const PREFLIGHT_REPORT: ReportProducerDescriptor = {
  namespace: "preflight",
  // The JSON-only variants (plain report, fix preview) emit no file, so the
  // report never rides a routed output — they are deliberately not producers.
  endpoints: [
    "/api/v1/security/print-preflight-annotated",
    "/api/v1/security/print-preflight-report",
    "/api/v1/security/print-preflight-fix",
  ],
  fixEndpoints: ["/api/v1/security/print-preflight-fix"],
  fields: [
    {
      path: "report.preflight.verdict",
      kind: "enum",
      values: ["pass", "warn", "fail"],
      valueLabelPrefix: "printPreflight.verdict",
      producedBy: "analysis",
      labelKey: "portal.pipelines.builder.routing.matchPreflightVerdict",
      labelDefault: "Preflight verdict",
    },
    {
      path: "report.preflight.errors",
      kind: "count",
      producedBy: "analysis",
      labelKey: "portal.pipelines.builder.routing.matchPreflightErrors",
      labelDefault: "Preflight error count",
    },
    {
      path: "report.preflight.warnings",
      kind: "count",
      producedBy: "analysis",
      labelKey: "portal.pipelines.builder.routing.matchPreflightWarnings",
      labelDefault: "Preflight warning count",
    },
    {
      path: "report.preflight.failingChecks",
      kind: "code-list",
      vocabulary: "checks",
      producedBy: "analysis",
      labelKey: "portal.pipelines.builder.routing.matchPreflightFailingChecks",
      labelDefault: "Preflight error type",
    },
    {
      path: "report.preflight.warningChecks",
      kind: "code-list",
      vocabulary: "checks",
      producedBy: "analysis",
      labelKey: "portal.pipelines.builder.routing.matchPreflightWarningChecks",
      labelDefault: "Preflight warning type",
    },
    {
      path: "report.preflight.infoChecks",
      kind: "code-list",
      vocabulary: "checks",
      producedBy: "analysis",
      labelKey: "portal.pipelines.builder.routing.matchPreflightInfoChecks",
      labelDefault: "Preflight info type",
    },
    {
      path: "report.preflight.preErrors",
      kind: "count",
      producedBy: "fix",
      labelKey: "portal.pipelines.builder.routing.matchPreflightPreErrors",
      labelDefault: "Preflight error count before fixups",
    },
    {
      path: "report.preflight.preWarnings",
      kind: "count",
      producedBy: "fix",
      labelKey: "portal.pipelines.builder.routing.matchPreflightPreWarnings",
      labelDefault: "Preflight warning count before fixups",
    },
    {
      path: "report.preflight.preFailingChecks",
      kind: "code-list",
      vocabulary: "checks",
      producedBy: "fix",
      labelKey:
        "portal.pipelines.builder.routing.matchPreflightPreFailingChecks",
      labelDefault: "Preflight error type before fixups",
    },
    {
      path: "report.preflight.preWarningChecks",
      kind: "code-list",
      vocabulary: "checks",
      producedBy: "fix",
      labelKey:
        "portal.pipelines.builder.routing.matchPreflightPreWarningChecks",
      labelDefault: "Preflight warning type before fixups",
    },
    {
      path: "report.preflight.preInfoChecks",
      kind: "code-list",
      vocabulary: "checks",
      producedBy: "fix",
      labelKey: "portal.pipelines.builder.routing.matchPreflightPreInfoChecks",
      labelDefault: "Preflight info type before fixups",
    },
    {
      path: "report.preflight.fixupsApplied",
      kind: "code-list",
      vocabulary: "fixups",
      producedBy: "fix",
      labelKey: "portal.pipelines.builder.routing.matchPreflightFixupsApplied",
      labelDefault: "Applied fixup",
    },
    {
      path: "report.preflight.fixupsSkipped",
      kind: "code-list",
      vocabulary: "fixups",
      producedBy: "fix",
      labelKey: "portal.pipelines.builder.routing.matchPreflightFixupsSkipped",
      labelDefault: "Skipped fixup",
    },
  ],
};

/** Every report namespace a pipeline can gate or route on. */
export const REPORT_PRODUCERS: readonly ReportProducerDescriptor[] = [
  PREFLIGHT_REPORT,
];

const PRODUCER_BY_ENDPOINT = new Map<string, ReportProducerDescriptor>();
const PRODUCER_BY_FIELD = new Map<string, ReportProducerDescriptor>();
for (const producer of REPORT_PRODUCERS) {
  for (const endpoint of producer.endpoints) {
    PRODUCER_BY_ENDPOINT.set(endpoint, producer);
  }
  for (const field of producer.fields) {
    PRODUCER_BY_FIELD.set(field.path, producer);
  }
}

/** The producer whose endpoints emit the given report namespace. */
export function reportProducerForEndpoint(
  endpoint: string,
): ReportProducerDescriptor | undefined {
  return PRODUCER_BY_ENDPOINT.get(endpoint);
}

/** Field metadata for a `report.<ns>.<field>` path, or undefined if undeclared. */
export function reportFieldByPath(
  path: string,
): ReportFieldDescriptor | undefined {
  return PRODUCER_BY_FIELD.get(path)?.fields.find((f) => f.path === path);
}

/** The producer that emits a field path, or undefined if no catalog declares it. */
export function reportProducerForField(
  path: string,
): ReportProducerDescriptor | undefined {
  return PRODUCER_BY_FIELD.get(path);
}

/** Field paths only a corrector step emits — they need a fix variant upstream. */
export function fixOnlyReportPaths(): ReadonlySet<string> {
  const paths = new Set<string>();
  for (const producer of REPORT_PRODUCERS) {
    for (const field of producer.fields) {
      if (field.producedBy === "fix") paths.add(field.path);
    }
  }
  return paths;
}

/**
 * How much of each report namespace upstream steps emit: "analysis" once any
 * producer step ran, "fix" once a corrector variant did — "fix" also serves
 * the analysis fields.
 */
export type ReportAvailability = Record<string, "analysis" | "fix">;

/** Whether the availability serves this field — fix fields need the corrector level. */
export function reportFieldServed(
  field: ReportFieldDescriptor,
  availability: ReportAvailability,
): boolean {
  const level =
    availability[PRODUCER_BY_FIELD.get(field.path)?.namespace ?? ""];
  return field.producedBy === "fix" ? level === "fix" : level !== undefined;
}

/** Availability derived from the endpoints that already ran — e.g. the steps before a gate. */
export function reportAvailabilityFromEndpoints(
  endpoints: readonly string[],
): ReportAvailability {
  const seen = new Set(endpoints);
  const availability: ReportAvailability = {};
  for (const producer of REPORT_PRODUCERS) {
    if (producer.fixEndpoints.some((e) => seen.has(e))) {
      availability[producer.namespace] = "fix";
    } else if (producer.endpoints.some((e) => seen.has(e))) {
      availability[producer.namespace] = "analysis";
    }
  }
  return availability;
}

/** Every declared field offerable — for hosts that inject the producing step on save. */
export function reportAvailabilityAll(): ReportAvailability {
  const availability: ReportAvailability = {};
  for (const producer of REPORT_PRODUCERS) {
    availability[producer.namespace] = "fix";
  }
  return availability;
}

/** Full availability — shared constant for wizards that inject the producing step on save. */
export const REPORT_AVAILABILITY_ALL: ReportAvailability =
  reportAvailabilityAll();

/** Endpoint sets derived from the catalog — the report producers per family. */
export const PREFLIGHT_STEP_ENDPOINTS: ReadonlySet<string> = new Set(
  PREFLIGHT_REPORT.endpoints,
);
export const PREFLIGHT_FIX_STEP_ENDPOINTS: ReadonlySet<string> = new Set(
  PREFLIGHT_REPORT.fixEndpoints,
);

/** A condition reads a report fact when its document field matches a declared path. */
export function isReportField(path: string): boolean {
  return PRODUCER_BY_FIELD.has(path);
}
