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
  minFontSizePt?: number;
  safetyMarginMm?: number;
  maxInkCoveragePercent?: number;
  minImage1BitDpi?: number;
  maxImageDpi?: number;
  /** Maximum real spot separations before a warning; 0 disables the limit. */
  maxSpotCount?: number;
  /** Prepend the summary report pages to the annotated PDF output. */
  includeSummaryPage: boolean;
  /** Finding codes to skip entirely (advanced troubleshooting). */
  disabledChecks?: string[];
  /**
   * Automation output: "annotatedPdf" returns a PDF copy with located issues
   * framed (keeps the pipeline chain alive), "reportPdf" returns the
   * standalone report document, "json" returns the machine-readable report.
   * Interactive mode ignores it — the tool page exposes all outputs itself.
   */
  reportFormat: "annotatedPdf" | "reportPdf" | "json";
}

export const defaultParameters: PrintPreflightParameters = {
  requiredBleedMm: undefined,
  minImageDpi: undefined,
  hairlineThresholdPt: undefined,
  checkBleedCoverage: true,
  minFontSizePt: undefined,
  safetyMarginMm: undefined,
  maxInkCoveragePercent: undefined,
  minImage1BitDpi: undefined,
  maxImageDpi: undefined,
  maxSpotCount: undefined,
  includeSummaryPage: true,
  disabledChecks: undefined,
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
  const nonNegative: Array<number | undefined> = [
    params.minFontSizePt,
    params.safetyMarginMm,
    params.maxInkCoveragePercent,
    params.maxSpotCount,
  ];
  if (
    nonNegative.some((v) => v !== undefined && (!Number.isFinite(v) || v < 0))
  ) {
    return false;
  }
  const atLeastOne: Array<number | undefined> = [
    params.minImage1BitDpi,
    params.maxImageDpi,
  ];
  if (
    atLeastOne.some((v) => v !== undefined && (!Number.isFinite(v) || v < 1))
  ) {
    return false;
  }
  if (
    params.reportFormat !== "annotatedPdf" &&
    params.reportFormat !== "reportPdf" &&
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
