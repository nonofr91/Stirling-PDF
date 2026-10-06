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
   * Fixup codes the print-preflight-fix endpoint applies; empty or undefined
   * runs every supported fixup that has something to correct.
   */
  fixups?: string[];
  /**
   * Automation output: "annotatedPdf" returns a PDF copy with located issues
   * framed, "reportPdf" returns the standalone report document, "fixedPdf"
   * returns the corrected PDF, "json" returns the machine-readable report and
   * "fixAuditJson" returns the fixup dry-run audit (applied corrections plus
   * before/after findings) without producing a PDF.
   * Interactive mode ignores it — the tool page exposes all outputs itself.
   */
  reportFormat:
    | "annotatedPdf"
    | "reportPdf"
    | "fixedPdf"
    | "json"
    | "fixAuditJson";
}

/** Every fixup the print-preflight-fix endpoint understands. */
export const FIXUP_CODES = [
  "REMOVE_JAVASCRIPT",
  "REMOVE_ATTACHMENTS",
  "FLATTEN_FORM",
  "NORMALIZE_USER_UNIT",
  "SET_OUTPUT_INTENT",
  "REMOVE_ANNOTATIONS_IN_TRIM",
  "MERGE_SPOT_ALIASES",
  "DOWNSAMPLE_IMAGES",
  "EXTEND_BLEED",
  "SET_MISSING_BOXES",
  "REMOVE_EMPTY_PAGES",
  "DISCARD_CROPBOX",
  "ENABLE_LAYER_PRINTING",
  "REMOVE_INVISIBLE_TEXT",
  "REGISTRATION_TO_BLACK",
  "OVERPRINT_BLACK_TEXT",
  "KNOCKOUT_WHITE",
  "PURE_BLACK_TEXT",
  "SPOT_TO_CMYK",
  "REDUCE_INK_COVERAGE",
  "RGB_TO_CMYK",
  "FLATTEN_TRANSPARENCY",
  "TEXT_TO_OUTLINES",
] as const;

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
  fixups: undefined,
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
    params.reportFormat !== "fixedPdf" &&
    params.reportFormat !== "json" &&
    params.reportFormat !== "fixAuditJson"
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
