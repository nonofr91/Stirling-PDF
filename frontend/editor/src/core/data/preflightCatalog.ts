/**
 * Declarative catalog of the print-preflight tool family — the frontend side
 * of the detector/corrector contract in devGuide/prepress-tool-contract.md.
 * It mirrors `PreflightCheck` (detections), `PreflightFixer.Code` and
 * `FIXUP_PARAMS` (corrections and their accepted parameters): backend enums
 * decide what is valid, this catalog decides what is shown. New codes and
 * parameters land here and in the backend in the same PR.
 */

export type PreflightCategory =
  | "FONTS"
  | "COLOR"
  | "IMAGES"
  | "GEOMETRY"
  | "CONTENT"
  | "DOCUMENT";

export type PreflightSeverity = "ERROR" | "WARNING" | "INFO";

/**
 * One detection the preflight can emit — `code` is the stable vocabulary for
 * reports, `disabledChecks`, routing/gate conditions and i18n keys.
 */
export interface DetectionDescriptor {
  code: string;
  category: PreflightCategory;
  severity: PreflightSeverity;
  /** The check needs a rendered page pass rather than content inspection. */
  renderPass?: boolean;
}

/**
 * A parameter a fixup accepts inside `fixupParams.<CODE>` — `kind` picks the
 * editor widget; enum params list their `options`, number params their bounds.
 */
export interface FixupParamSpec {
  key: string;
  kind: "enum" | "number";
  /** Allowed values for `kind: "enum"`. */
  options?: readonly string[];
  /** Inclusive bounds for `kind: "number"` (`minExclusive` for DOWNSAMPLE's 0). */
  min?: number;
  max?: number;
  minExclusive?: boolean;
  /** Backend default when the parameter is absent — display only, never sent. */
  default?: string | number;
}

export type FixupEngine = "document" | "stream" | "ghostscript";

/**
 * One correction the fix endpoint can apply. `addressesChecks` is advisory —
 * an explicit `fixups` selection runs unconditionally — but it drives
 * "which finding does this fix" in the UI and audit docs. `destructive` marks
 * corrections that remove content or semantics (opt-in review).
 */
export interface FixupDescriptor {
  code: string;
  engine: FixupEngine;
  addressesChecks: readonly string[];
  params: readonly FixupParamSpec[];
  destructive?: boolean;
}

export const PREFLIGHT_DETECTIONS: readonly DetectionDescriptor[] = [
  { code: "FONT_NOT_EMBEDDED", category: "FONTS", severity: "ERROR" },
  { code: "FONT_TYPE3", category: "FONTS", severity: "WARNING" },
  { code: "COLOR_RGB_USED", category: "COLOR", severity: "WARNING" },
  { code: "COLOR_SPOT", category: "COLOR", severity: "INFO" },
  { code: "IMAGE_LOW_RES", category: "IMAGES", severity: "WARNING" },
  { code: "TRIMBOX_MISSING", category: "GEOMETRY", severity: "WARNING" },
  { code: "BLEED_MISSING", category: "GEOMETRY", severity: "ERROR" },
  { code: "BLEED_INSUFFICIENT", category: "GEOMETRY", severity: "ERROR" },
  {
    code: "BLEED_UNPAINTED",
    category: "GEOMETRY",
    severity: "WARNING",
    renderPass: true,
  },
  { code: "ANNOTATION_IN_TRIM", category: "CONTENT", severity: "WARNING" },
  { code: "HAIRLINE", category: "CONTENT", severity: "WARNING" },
  { code: "TRANSPARENCY", category: "CONTENT", severity: "INFO" },
  { code: "OPTIONAL_CONTENT", category: "CONTENT", severity: "INFO" },
  { code: "MIXED_PAGE_SIZES", category: "DOCUMENT", severity: "INFO" },
  { code: "CONTENT_PARSE_ERROR", category: "CONTENT", severity: "WARNING" },
  { code: "OVERPRINT_WHITE", category: "COLOR", severity: "ERROR" },
  { code: "OVERPRINT_BLACK", category: "COLOR", severity: "WARNING" },
  { code: "TEXT_RICH_BLACK", category: "COLOR", severity: "WARNING" },
  { code: "TEXT_SMALL", category: "CONTENT", severity: "WARNING" },
  { code: "SAFETY_MARGIN", category: "GEOMETRY", severity: "WARNING" },
  { code: "EMPTY_PAGE", category: "DOCUMENT", severity: "INFO" },
  { code: "IMAGE_OVERSAMPLED", category: "IMAGES", severity: "INFO" },
  { code: "IMAGE_1BIT_LOW_RES", category: "IMAGES", severity: "WARNING" },
  { code: "INK_COVERAGE_HIGH", category: "COLOR", severity: "WARNING" },
  {
    code: "INK_COVERAGE_HIGH_RENDERED",
    category: "COLOR",
    severity: "WARNING",
    renderPass: true,
  },
  { code: "SPOT_ALIAS", category: "COLOR", severity: "WARNING" },
  { code: "SPOT_COUNT", category: "COLOR", severity: "INFO" },
  { code: "REGISTRATION_PAINT", category: "COLOR", severity: "INFO" },
  { code: "INVISIBLE_TEXT", category: "CONTENT", severity: "INFO" },
  { code: "PATTERN_USED", category: "COLOR", severity: "INFO" },
  { code: "SHADING_USED", category: "COLOR", severity: "INFO" },
  { code: "OBJECT_OUTSIDE_PAGE", category: "GEOMETRY", severity: "INFO" },
  { code: "OUTPUT_INTENT_MISSING", category: "COLOR", severity: "WARNING" },
  { code: "EMBEDDED_FILES", category: "DOCUMENT", severity: "WARNING" },
  { code: "FORM_FIELDS", category: "DOCUMENT", severity: "WARNING" },
  { code: "XFA_FORM", category: "DOCUMENT", severity: "WARNING" },
  { code: "SIGNATURES", category: "DOCUMENT", severity: "INFO" },
  { code: "JAVASCRIPT", category: "DOCUMENT", severity: "INFO" },
  { code: "USER_UNIT", category: "GEOMETRY", severity: "WARNING" },
  { code: "LAYERS_PRINT_OFF", category: "CONTENT", severity: "INFO" },
  { code: "CROPBOX_NE_MEDIA", category: "GEOMETRY", severity: "INFO" },
];

export const PREFLIGHT_FIXUPS: readonly FixupDescriptor[] = [
  {
    code: "REMOVE_JAVASCRIPT",
    engine: "document",
    addressesChecks: ["JAVASCRIPT"],
    params: [],
    destructive: true,
  },
  {
    code: "REMOVE_ATTACHMENTS",
    engine: "document",
    addressesChecks: ["EMBEDDED_FILES"],
    params: [],
    destructive: true,
  },
  {
    code: "FLATTEN_FORM",
    engine: "document",
    addressesChecks: ["FORM_FIELDS", "XFA_FORM"],
    params: [],
    destructive: true,
  },
  {
    code: "NORMALIZE_USER_UNIT",
    engine: "document",
    addressesChecks: ["USER_UNIT"],
    params: [],
  },
  {
    code: "SET_OUTPUT_INTENT",
    engine: "document",
    addressesChecks: ["OUTPUT_INTENT_MISSING"],
    params: [],
  },
  {
    code: "REMOVE_ANNOTATIONS_IN_TRIM",
    engine: "document",
    addressesChecks: ["ANNOTATION_IN_TRIM"],
    params: [],
    destructive: true,
  },
  {
    code: "MERGE_SPOT_ALIASES",
    engine: "document",
    addressesChecks: ["COLOR_SPOT", "SPOT_ALIAS"],
    params: [],
  },
  {
    code: "DOWNSAMPLE_IMAGES",
    engine: "document",
    addressesChecks: ["IMAGE_OVERSAMPLED"],
    params: [
      {
        key: "jpegQuality",
        kind: "number",
        min: 0,
        max: 1,
        minExclusive: true,
        default: 0.9,
      },
    ],
  },
  {
    code: "EXTEND_BLEED",
    engine: "document",
    addressesChecks: ["BLEED_MISSING", "BLEED_INSUFFICIENT", "BLEED_UNPAINTED"],
    params: [
      {
        key: "method",
        kind: "enum",
        options: ["MIRROR", "MIRROR_IMAGE", "PIXEL_REPEAT", "UPSCALE"],
        default: "MIRROR",
      },
    ],
  },
  {
    code: "SET_MISSING_BOXES",
    engine: "document",
    addressesChecks: ["TRIMBOX_MISSING", "BLEED_MISSING", "CROPBOX_NE_MEDIA"],
    params: [],
  },
  {
    code: "REMOVE_EMPTY_PAGES",
    engine: "document",
    addressesChecks: ["EMPTY_PAGE"],
    params: [],
    destructive: true,
  },
  {
    code: "DISCARD_CROPBOX",
    engine: "document",
    addressesChecks: ["CROPBOX_NE_MEDIA"],
    params: [],
    destructive: true,
  },
  {
    code: "CLIP_TO_CROPBOX",
    engine: "document",
    addressesChecks: ["OBJECT_OUTSIDE_PAGE"],
    params: [],
  },
  {
    code: "ENABLE_LAYER_PRINTING",
    engine: "document",
    addressesChecks: ["LAYERS_PRINT_OFF"],
    params: [],
  },
  {
    code: "REMOVE_INVISIBLE_TEXT",
    engine: "stream",
    addressesChecks: ["INVISIBLE_TEXT"],
    params: [],
    destructive: true,
  },
  {
    code: "REGISTRATION_TO_BLACK",
    engine: "stream",
    addressesChecks: ["REGISTRATION_PAINT"],
    params: [],
  },
  {
    code: "OVERPRINT_BLACK_TEXT",
    engine: "stream",
    addressesChecks: ["OVERPRINT_BLACK"],
    params: [],
  },
  {
    code: "KNOCKOUT_WHITE",
    engine: "stream",
    addressesChecks: ["OVERPRINT_WHITE"],
    params: [],
  },
  {
    code: "PURE_BLACK_TEXT",
    engine: "stream",
    addressesChecks: ["TEXT_RICH_BLACK"],
    params: [
      { key: "maxPt", kind: "number", min: 0, minExclusive: true, default: 24 },
    ],
  },
  {
    code: "SPOT_TO_CMYK",
    engine: "stream",
    addressesChecks: ["COLOR_SPOT", "SPOT_COUNT"],
    params: [],
    destructive: true,
  },
  {
    code: "REDUCE_INK_COVERAGE",
    engine: "stream",
    addressesChecks: ["INK_COVERAGE_HIGH", "INK_COVERAGE_HIGH_RENDERED"],
    params: [],
  },
  {
    code: "RGB_TO_CMYK",
    engine: "ghostscript",
    addressesChecks: ["COLOR_RGB_USED"],
    params: [],
  },
  {
    code: "FLATTEN_TRANSPARENCY",
    engine: "ghostscript",
    addressesChecks: ["TRANSPARENCY"],
    params: [],
    destructive: true,
  },
  {
    code: "TEXT_TO_OUTLINES",
    engine: "ghostscript",
    addressesChecks: ["FONT_NOT_EMBEDDED", "FONT_TYPE3"],
    params: [],
    destructive: true,
  },
];

/** Detection codes in declaration order — the `disabledChecks` vocabulary. */
export const PREFLIGHT_CHECK_IDS: readonly string[] = PREFLIGHT_DETECTIONS.map(
  (d) => d.code,
);

/** Correction codes in declaration order — the `fixups` vocabulary. */
export const PREFLIGHT_FIXUP_IDS: readonly string[] = PREFLIGHT_FIXUPS.map(
  (f) => f.code,
);

const FIXUPS_BY_CODE = new Map(PREFLIGHT_FIXUPS.map((f) => [f.code, f]));
const DETECTIONS_BY_CODE = new Map(
  PREFLIGHT_DETECTIONS.map((d) => [d.code, d]),
);

export function fixupByCode(code: string): FixupDescriptor | undefined {
  return FIXUPS_BY_CODE.get(code);
}

export function detectionByCode(code: string): DetectionDescriptor | undefined {
  return DETECTIONS_BY_CODE.get(code);
}

/** Parameter value map for one fixup, as edited in the UI and sent on the wire. */
export type FixupParamValues = Record<string, string | number>;
export type FixupParams = Record<string, FixupParamValues>;

/**
 * Catalog-side validation mirroring `parseFixupParams`: unknown fixup codes,
 * undeclared keys, enum values outside `options` and out-of-range numbers all
 * fail — the UI rejects before the backend does.
 */
export function validateFixupParams(params: FixupParams): boolean {
  for (const [code, values] of Object.entries(params)) {
    const spec = FIXUPS_BY_CODE.get(code);
    if (!spec) return false;
    for (const [key, value] of Object.entries(values)) {
      const param = spec.params.find((p) => p.key === key);
      if (!param) return false;
      if (param.kind === "enum") {
        if (typeof value !== "string" || !param.options?.includes(value)) {
          return false;
        }
      } else {
        if (typeof value !== "number" || !Number.isFinite(value)) return false;
        if (param.min !== undefined) {
          if (param.minExclusive ? value <= param.min : value < param.min) {
            return false;
          }
        }
        if (param.max !== undefined && value > param.max) return false;
      }
    }
  }
  return true;
}
