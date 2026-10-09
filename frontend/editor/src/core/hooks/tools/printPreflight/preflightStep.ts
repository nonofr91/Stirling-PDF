import {
  objectToFormData,
  type ToolApiParams,
  type ToolEndpoint,
} from "@app/hooks/tools/shared/toolApiMapping";
import {
  defaultParameters,
  type PrintPreflightParameters,
} from "@app/hooks/tools/printPreflight/usePrintPreflightParameters";

const FIX_ENDPOINT =
  "/api/v1/security/print-preflight-fix" satisfies ToolEndpoint;
type PreflightStepApiParams = ToolApiParams[typeof FIX_ENDPOINT];

/**
 * A stored preflight pipeline step's parameters: the interactive tool's full
 * threshold shape (kept so a step authored elsewhere round-trips losslessly)
 * plus `profileName`, which only exists server-side — when set, the profile
 * supplies thresholds, fixups and disabledChecks and the inline values are
 * ignored.
 */
export interface PrintPreflightStepParameters extends PrintPreflightParameters {
  profileName?: string;
  /** BCP-47 tag for generated report text; absent falls back to the session locale. */
  reportLanguage?: string;
  /**
   * Optional ICC profile used as output intent by the colour fixups. Kept out of
   * `toApiParams` — a File cannot live in stored step JSON; it travels as a
   * `fileParameters` binding and is appended by buildFormData.
   */
  iccProfile?: File;
}

export const preflightStepToApiParams = (
  parameters: PrintPreflightStepParameters,
): PreflightStepApiParams => {
  const profileName = parameters.profileName?.trim();
  // A named profile is authoritative on the fields it declares, but the backend merges
  // field-by-field: anything sent alongside it still applies where the profile is silent.
  // Send only the name so a stored step follows the profile exactly.
  if (profileName) {
    return {
      profileName,
      reportLanguage: parameters.reportLanguage,
    };
  }
  // Absent fields stay absent on the wire — the backend defaults fill them in.
  return Object.fromEntries(
    Object.entries({
      reportLanguage: parameters.reportLanguage,
      requiredBleedMm: parameters.requiredBleedMm,
      minImageDpi: parameters.minImageDpi,
      hairlineThresholdPt: parameters.hairlineThresholdPt,
      checkBleedCoverage: parameters.checkBleedCoverage,
      minFontSizePt: parameters.minFontSizePt,
      safetyMarginMm: parameters.safetyMarginMm,
      maxInkCoveragePercent: parameters.maxInkCoveragePercent,
      renderedInkCoverage: parameters.renderedInkCoverage,
      minImage1BitDpi: parameters.minImage1BitDpi,
      maxImageDpi: parameters.maxImageDpi,
      maxSpotCount: parameters.maxSpotCount,
      includeSummaryPage: parameters.includeSummaryPage,
      disabledChecks: parameters.disabledChecks,
      fixups: parameters.fixups,
    }).filter(([, value]) => value !== undefined),
  );
};

export const preflightStepFromApiParams = (
  apiParams: PreflightStepApiParams,
  reportFormat: PrintPreflightParameters["reportFormat"],
): Partial<PrintPreflightStepParameters> => ({
  profileName: apiParams.profileName ?? "",
  reportLanguage: apiParams.reportLanguage,
  requiredBleedMm: apiParams.requiredBleedMm,
  minImageDpi: apiParams.minImageDpi,
  hairlineThresholdPt: apiParams.hairlineThresholdPt,
  checkBleedCoverage:
    apiParams.checkBleedCoverage ?? defaultParameters.checkBleedCoverage,
  minFontSizePt: apiParams.minFontSizePt,
  safetyMarginMm: apiParams.safetyMarginMm,
  maxInkCoveragePercent: apiParams.maxInkCoveragePercent,
  renderedInkCoverage:
    apiParams.renderedInkCoverage ?? defaultParameters.renderedInkCoverage,
  minImage1BitDpi: apiParams.minImage1BitDpi,
  maxImageDpi: apiParams.maxImageDpi,
  maxSpotCount: apiParams.maxSpotCount,
  includeSummaryPage:
    apiParams.includeSummaryPage ?? defaultParameters.includeSummaryPage,
  disabledChecks: apiParams.disabledChecks,
  fixups: apiParams.fixups,
  reportFormat,
});

export const buildPreflightStepFormData = (
  parameters: PrintPreflightStepParameters,
  file: File,
): FormData =>
  objectToFormData(preflightStepToApiParams(parameters), {
    fileInput: file,
    // Sentinel injections arrive as File[]; objectToFormData iterates them.
    iccProfile: parameters.iccProfile,
  });
