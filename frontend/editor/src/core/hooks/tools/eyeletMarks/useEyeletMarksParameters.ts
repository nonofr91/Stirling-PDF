import { BaseParameters } from "@app/types/parameters";
import {
  useBaseParameters,
  BaseParametersHook,
} from "@app/hooks/tools/shared/useBaseParameters";
import { EyeletMarkMode } from "@app/utils/eyeletMarkGeometry";

export interface EyeletMarksParameters extends BaseParameters {
  mode: EyeletMarkMode;
  /** Eyelet diameter in mm — the cross mark's span matches it. */
  eyeletDiameterMm?: number;
  strokeWidthPt?: number;
  /** Signed distance from the trim edge in mm: positive inside, negative outside. */
  offsetTopMm?: number;
  offsetRightMm?: number;
  offsetBottomMm?: number;
  offsetLeftMm?: number;
  /** COUNT mode: marks per side, ends included; 0 disables the side. */
  countTop?: number;
  countRight?: number;
  countBottom?: number;
  countLeft?: number;
  /** MAX_SPACING mode: largest allowed gap between marks; <= 0 disables. */
  maxSpacingTopMm?: number;
  maxSpacingRightMm?: number;
  maxSpacingBottomMm?: number;
  maxSpacingLeftMm?: number;
  /** Optional-content layer name; blank draws plain page content. */
  layerName: string;
}

export const defaultParameters: EyeletMarksParameters = {
  mode: "MAX_SPACING",
  eyeletDiameterMm: 10,
  strokeWidthPt: 0.5,
  offsetTopMm: 15,
  offsetRightMm: 15,
  offsetBottomMm: 15,
  offsetLeftMm: 15,
  countTop: 0,
  countRight: 0,
  countBottom: 0,
  countLeft: 0,
  maxSpacingTopMm: 500,
  maxSpacingRightMm: 500,
  maxSpacingBottomMm: 500,
  maxSpacingLeftMm: 500,
  layerName: "",
};

export type EyeletMarksParametersHook =
  BaseParametersHook<EyeletMarksParameters>;

const inRange = (v: number | undefined, min: number, max: number): boolean =>
  v === undefined || (Number.isFinite(v) && v >= min && v <= max);

const positive = (v: number | undefined, max: number): boolean =>
  v === undefined || (Number.isFinite(v) && v > 0 && v <= max);

const COUNTS = ["countTop", "countRight", "countBottom", "countLeft"] as const;
const SPACINGS = [
  "maxSpacingTopMm",
  "maxSpacingRightMm",
  "maxSpacingBottomMm",
  "maxSpacingLeftMm",
] as const;
const OFFSETS = [
  "offsetTopMm",
  "offsetRightMm",
  "offsetBottomMm",
  "offsetLeftMm",
] as const;

/** Shared by the tool's settings hook and its operationConfig so the editor
 *  and the pipeline builder reject the same requests the backend would. */
export function validateEyeletMarksParameters(
  params: EyeletMarksParameters,
): boolean {
  if (params.mode !== "COUNT" && params.mode !== "MAX_SPACING") return false;
  if (!positive(params.eyeletDiameterMm, 100)) return false;
  if (!positive(params.strokeWidthPt, Number.POSITIVE_INFINITY)) return false;
  for (const key of OFFSETS) {
    if (!inRange(params[key], -200, 200)) return false;
  }
  if (params.mode === "COUNT") {
    for (const key of COUNTS) {
      const v = params[key];
      if (v === undefined) continue;
      if (!Number.isInteger(v) || v < 0 || v > 500 || v === 1) return false;
    }
    return COUNTS.some((key) => (params[key] ?? 0) >= 2);
  }
  for (const key of SPACINGS) {
    if (!inRange(params[key], -Infinity, 20000)) return false;
  }
  // An absent spacing means the backend default (500mm) applies — the side is
  // enabled, unlike an explicit 0 which disables it.
  return SPACINGS.some(
    (key) => (params[key] ?? defaultParameters.maxSpacingTopMm!) > 0,
  );
}

export const useEyeletMarksParameters = (): EyeletMarksParametersHook => {
  return useBaseParameters({
    defaultParameters,
    endpointName: "eyelet-marks",
    validateFn: validateEyeletMarksParameters,
  });
};
