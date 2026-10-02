import { BaseParameters } from "@app/types/parameters";
import {
  useBaseParameters,
  BaseParametersHook,
} from "@app/hooks/tools/shared/useBaseParameters";

/** Thresholds for the print preflight checks; undefined falls back to backend defaults. */
export interface PrintPreflightParameters extends BaseParameters {
  requiredBleedMm?: number;
  minImageDpi?: number;
  hairlineThresholdPt?: number;
  checkBleedCoverage: boolean;
  /**
   * Automation output: "annotatedPdf" returns a PDF copy with located issues
   * framed (keeps the pipeline chain alive), "json" returns the machine-readable
   * report and ends the chain. Interactive mode ignores it — the tool page
   * exposes both outputs itself.
   */
  reportFormat: "annotatedPdf" | "json";
}

export const defaultParameters: PrintPreflightParameters = {
  requiredBleedMm: undefined,
  minImageDpi: undefined,
  hairlineThresholdPt: undefined,
  checkBleedCoverage: true,
  reportFormat: "annotatedPdf",
};

export type PrintPreflightParametersHook =
  BaseParametersHook<PrintPreflightParameters>;

/** Shared by the tool's settings hook and its operationConfig. */
export function validatePrintPreflightParameters(
  params: PrintPreflightParameters,
): boolean {
  if (
    params.requiredBleedMm !== undefined &&
    (!Number.isFinite(params.requiredBleedMm) || params.requiredBleedMm < 0)
  ) {
    return false;
  }
  if (
    params.minImageDpi !== undefined &&
    (!Number.isFinite(params.minImageDpi) || params.minImageDpi < 1)
  ) {
    return false;
  }
  if (
    params.hairlineThresholdPt !== undefined &&
    (!Number.isFinite(params.hairlineThresholdPt) ||
      params.hairlineThresholdPt < 0)
  ) {
    return false;
  }
  if (
    params.reportFormat !== "annotatedPdf" &&
    params.reportFormat !== "json"
  ) {
    return false;
  }
  return true;
}

export const usePrintPreflightParameters = (): PrintPreflightParametersHook => {
  return useBaseParameters({
    defaultParameters,
    endpointName: "print-preflight",
    validateFn: validatePrintPreflightParameters,
  });
};
