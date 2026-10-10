import type { Condition, MatchesAnyCondition } from "@app/conditions/types";
import {
  reportFieldByPath,
  reportFieldServed,
  type ReportAvailability,
} from "@app/data/reportCatalog";

/** Creates the classification comparison offered by the policy wizard and pipeline builder. */
export function classificationCondition(
  values: string[] = [],
): MatchesAnyCondition {
  return {
    input: { source: "document", field: "classification.labels" },
    operator: "matches-any",
    values,
  };
}

/** Creates a deterministic comparison against facts read directly from the document. */
export function documentFieldCondition(
  field: string,
  values: string[] = [],
): MatchesAnyCondition {
  return {
    input: { source: "document", field },
    operator: "matches-any",
    values,
  };
}

/** Classification facts require a classifier-produced verdict; unrelated facts do not. */
export function requiresClassification(condition: Condition): boolean {
  return (
    condition.input.source === "document" &&
    condition.input.field.startsWith("classification.")
  );
}

/** Step-report facts are only present when a reporting tool (e.g. preflight) ran in the pipeline. */
export function requiresPreflight(condition: Condition): boolean {
  return (
    condition.input.source === "document" &&
    condition.input.field.startsWith("report.preflight.")
  );
}

const REPORT_FACT = /^report\./;

/** The condition reads a `report.<ns>.*` fact emitted by a step's tool report. */
export function readsReportField(condition: Condition): boolean {
  return (
    condition.input.source === "document" &&
    REPORT_FACT.test(condition.input.field)
  );
}

/** The condition reads a corrector-step field (`pre*`, `fixupsApplied`…) — fix variant only. */
export function readsFixReportField(condition: Condition): boolean {
  return (
    condition.input.source === "document" &&
    reportFieldByPath(condition.input.field)?.producedBy === "fix"
  );
}

/**
 * Whether the producers that already ran emit every report fact the condition
 * reads — fix-produced fields need the corrector variant of their namespace.
 * An undeclared `report.*` path can never be served, so it fails here too.
 */
export function reportFactsSatisfied(
  condition: Condition,
  availability: ReportAvailability,
): boolean {
  if (!readsReportField(condition)) return true;
  const field = reportFieldByPath(condition.input.field);
  return field !== undefined && reportFieldServed(field, availability);
}
