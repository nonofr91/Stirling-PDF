import { defineSingleFileTool } from "@app/hooks/tools/shared/useToolOperation";
import type { ToolEndpoint } from "@app/hooks/tools/shared/toolApiMapping";
import {
  buildPreflightStepFormData,
  preflightStepFromApiParams,
  preflightStepToApiParams,
  type PrintPreflightStepParameters,
} from "@app/hooks/tools/printPreflight/preflightStep";
import {
  defaultParameters,
  validatePrintPreflightParameters,
} from "@app/hooks/tools/printPreflight/usePrintPreflightParameters";

const ENDPOINT =
  "/api/v1/security/print-preflight-annotated" satisfies ToolEndpoint;

export const printPreflightCheckDefaultParameters: PrintPreflightStepParameters =
  { ...defaultParameters, reportFormat: "annotatedPdf" };

/**
 * Inspect-only preflight as a pipeline step: the annotated copy carries every
 * located issue while the verdict header feeds routing. Same rationale as the
 * fix variant — a static endpoint keeps the stored step editable and stops the
 * interactive tool's dynamic endpoint set from claiming it.
 */
export const printPreflightCheckOperationConfig = defineSingleFileTool({
  operationType: "printPreflightCheck",
  endpoint: ENDPOINT,
  buildFormData: buildPreflightStepFormData,
  toApiParams: preflightStepToApiParams,
  fromApiParams: (apiParams) =>
    preflightStepFromApiParams(apiParams, "annotatedPdf"),
  defaultParameters: printPreflightCheckDefaultParameters,
  validateParams: validatePrintPreflightParameters,
});
