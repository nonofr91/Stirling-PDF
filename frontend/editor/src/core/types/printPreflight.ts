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

export interface PreflightFacts {
  fonts: PreflightFontFact[];
  colorSpaces: string[];
  spotColors: string[];
  imageCount: number;
  lowResImageCount: number;
  transparencyUsed: boolean;
  hasTrimBox: boolean;
  hasBleedBox: boolean;
  pageSizes: PreflightPageSize[];
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

export const PREFLIGHT_JSON_FILENAME = "print-preflight-report.json";
