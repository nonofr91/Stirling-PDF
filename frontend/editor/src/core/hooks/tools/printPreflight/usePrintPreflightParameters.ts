import { BaseParameters } from "@app/types/parameters";
import {
  useBaseParameters,
  BaseParametersHook,
} from "@app/hooks/tools/shared/useBaseParameters";
import {
  PREFLIGHT_FIXUP_IDS,
  validateFixupParams,
  type FixupParams,
} from "@app/data/preflightCatalog";

/** Thresholds for the print preflight checks; undefined falls back to backend defaults. */
export interface PrintPreflightParameters extends BaseParameters {
  requiredBleedMm?: number;
  minImageDpi?: number;
  hairlineThresholdPt?: number;
  checkBleedCoverage: boolean;
  minFontSizePt?: number;
  safetyMarginMm?: number;
  maxInkCoveragePercent?: number;
  /**
   * Measure total ink coverage from a Ghostscript-rendered CMYK raster rather
   * than painted fills — sees true stacking/knockouts, at the cost of one
   * render pass per document.
   */
  renderedInkCoverage?: boolean;
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
   * Per-fixup parameters keyed by fixup code
   * (`{"EXTEND_BLEED":{"method":"MIRROR_IMAGE"}}`) — only keys the fixup
   * declares in the catalog are accepted; shared thresholds stay top-level
   * fields (contract R3). Serialized to a JSON string on the wire.
   */
  fixupParams?: FixupParams;
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

/** Every fixup the print-preflight-fix endpoint understands — the catalog's vocabulary. */
export const FIXUP_CODES = PREFLIGHT_FIXUP_IDS;

export const defaultParameters: PrintPreflightParameters = {
  requiredBleedMm: undefined,
  minImageDpi: undefined,
  hairlineThresholdPt: undefined,
  checkBleedCoverage: true,
  minFontSizePt: undefined,
  safetyMarginMm: undefined,
  maxInkCoveragePercent: undefined,
  renderedInkCoverage: false,
  minImage1BitDpi: undefined,
  maxImageDpi: undefined,
  maxSpotCount: undefined,
  includeSummaryPage: true,
  disabledChecks: undefined,
  fixups: undefined,
  fixupParams: undefined,
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
  if (
    params.fixupParams !== undefined &&
    !validateFixupParams(params.fixupParams)
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
