export type PreflightSeverity = "ERROR" | "WARNING" | "INFO";

export type PreflightCategory =
  | "FONTS"
  | "COLOR"
  | "IMAGES"
  | "GEOMETRY"
  | "CONTENT"
  | "DOCUMENT";

/**
 * Where a finding lives on the page, in PDF user space (unrotated, bottom-left
 * origin, points). The viewer overlay converts with pdfRectToPageFractions.
 */
export interface PreflightArea {
  page: number;
  x: number;
  y: number;
  width: number;
  height: number;
  label?: string | null;
}

export interface PreflightFinding {
  severity: PreflightSeverity;
  category: PreflightCategory;
  code: string;
  message: string;
  pages: number[];
  areas?: PreflightArea[];
  areasTruncated?: boolean;
}

export interface PreflightCounts {
  errors: number;
  warnings: number;
  infos: number;
}

export interface PreflightFontFact {
  name: string;
  subType: string;
  embedded: boolean;
  type3: boolean;
  pages: number[];
}

export interface PreflightPageSize {
  widthPt: number;
  heightPt: number;
  rotation: number;
  count: number;
}

export interface PreflightOutputIntent {
  name: string | null;
  registry: string | null;
  info: string | null;
  conditionIdentifier: string | null;
}

export interface PreflightFacts {
  fonts: PreflightFontFact[];
  colorSpaces: string[];
  spotColors: string[];
  technicalSeparations: string[];
  imageCount: number;
  lowResImageCount: number;
  oversampledImageCount: number;
  minEffectiveDpi: number;
  maxEffectiveDpi: number;
  minFontSizeSeen: number;
  maxInkCoverageSeen: number;
  transparencyUsed: boolean;
  patternUsed: boolean;
  shadingUsed: boolean;
  hasTrimBox: boolean;
  hasBleedBox: boolean;
  hasCropBox: boolean;
  hasArtBox: boolean;
  pageSizes: PreflightPageSize[];
  outputIntent: PreflightOutputIntent | null;
  trapped: string | null;
  nonStandardUserUnitPages: number[];
  hasAcroForm: boolean;
  formFieldCount: number;
  hasXfa: boolean;
  signatureCount: number;
  embeddedFileCount: number;
  hasJavascript: boolean;
  layersDisabledForPrint: string[];
  emptyPages: number[];
  invisibleTextPages: number[];
  registrationPaintPages: number[];
}

export interface PrintPreflightReport {
  fileName: string | null;
  fileSizeBytes: number;
  pdfVersion: string;
  pageCount: number;
  worstSeverity: PreflightSeverity | null;
  counts: PreflightCounts;
  findings: PreflightFinding[];
  facts: PreflightFacts;
}

/**
 * Dry-run outcome of the automatic fixups — returned by
 * /api/v1/security/print-preflight-fix-preview, which applies the requested
 * corrections in memory and re-analyses the result instead of returning the
 * fixed PDF. Findings are compared by code: a code absent afterwards means the
 * finding resolved; a new code means a correction surfaced it.
 */
export interface PreflightFixAudit {
  fileName: string | null;
  appliedFixups: string[];
  countsBefore: PreflightCounts;
  countsAfter: PreflightCounts;
  resolvedFindings: PreflightFinding[];
  remainingFindings: PreflightFinding[];
  introducedFindings: PreflightFinding[];
}

export const PREFLIGHT_JSON_FILENAME = "print-preflight-report.json";

/**
 * A named preflight profile — snapshot of every threshold plus fixups and
 * disabled checks. Served by /api/v1/security/print-preflight-profiles;
 * built-ins ship with the backend, customs persist in configs/.
 */
export interface PrintPreflightProfile {
  name: string;
  description?: string | null;
  builtin: boolean;
  requiredBleedMm?: number | null;
  minImageDpi?: number | null;
  hairlineThresholdPt?: number | null;
  checkBleedCoverage?: boolean | null;
  minFontSizePt?: number | null;
  safetyMarginMm?: number | null;
  maxInkCoveragePercent?: number | null;
  minImage1BitDpi?: number | null;
  maxImageDpi?: number | null;
  maxSpotCount?: number | null;
  includeSummaryPage?: boolean | null;
  disabledChecks?: string[] | null;
  fixups?: string[] | null;
}
