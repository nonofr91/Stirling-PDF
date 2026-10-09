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

const ENDPOINT = "/api/v1/security/print-preflight-fix" satisfies ToolEndpoint;

export const printPreflightFixDefaultParameters: PrintPreflightStepParameters =
  { ...defaultParameters, reportFormat: "fixedPdf" };

/**
 * Preflight-and-fix as a pipeline step. The interactive Print Preflight tool's
 * config is a custom processor without parameter mappers, so a stored fix step
 * used to reopen as an unsupported step and silently resave as an annotated
 * check with empty parameters. This static-endpoint entry claims the endpoint
 * back: the step is editable and round-trips unchanged.
 */
export const printPreflightFixOperationConfig = defineSingleFileTool({
  operationType: "printPreflightFix",
  endpoint: ENDPOINT,
  buildFormData: buildPreflightStepFormData,
  toApiParams: preflightStepToApiParams,
  fromApiParams: (apiParams) =>
    preflightStepFromApiParams(apiParams, "fixedPdf"),
  defaultParameters: printPreflightFixDefaultParameters,
  validateParams: validatePrintPreflightParameters,
});
