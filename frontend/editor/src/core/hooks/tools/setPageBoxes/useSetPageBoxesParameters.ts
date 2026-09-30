import { BaseParameters } from "@app/types/parameters";
import {
  useBaseParameters,
  BaseParametersHook,
} from "@app/hooks/tools/shared/useBaseParameters";

export type BleedMethod =
  | "MIRROR"
  | "MIRROR_IMAGE"
  | "PIXEL_REPEAT"
  | "UPSCALE";

export const BLEED_METHODS: BleedMethod[] = [
  "MIRROR",
  "MIRROR_IMAGE",
  "PIXEL_REPEAT",
  "UPSCALE",
];

/** Box coordinates as "x,y,width,height" in points; empty means untouched. */
export interface SetPageBoxesParameters extends BaseParameters {
  mediaBox: string;
  cropBox: string;
  trimBox: string;
  bleedBox: string;
  artBox: string;
  trimMarginMm?: number;
  bleedMm?: number;
  deriveFromCropMarks: boolean;
  copyMissingFromMediaBox: boolean;
  generateBleed: boolean;
  bleedMethod: BleedMethod;
  /** Per-side bleed widths; undefined falls back to bleedMm on the backend. */
  bleedTopMm?: number;
  bleedRightMm?: number;
  bleedBottomMm?: number;
  bleedLeftMm?: number;
  bleedCorners: boolean;
  bleedDpi?: number;
  bleedInsetMm?: number;
  addCropMarks: boolean;
  cropMarkLengthMm?: number;
  cropMarkOffsetMm?: number;
  cropMarkWeightPt?: number;
}

export const defaultParameters: SetPageBoxesParameters = {
  mediaBox: "",
  cropBox: "",
  trimBox: "",
  bleedBox: "",
  artBox: "",
  trimMarginMm: undefined,
  bleedMm: undefined,
  deriveFromCropMarks: false,
  copyMissingFromMediaBox: false,
  generateBleed: false,
  bleedMethod: "MIRROR",
  bleedTopMm: undefined,
  bleedRightMm: undefined,
  bleedBottomMm: undefined,
  bleedLeftMm: undefined,
  bleedCorners: true,
  bleedDpi: undefined,
  bleedInsetMm: undefined,
  addCropMarks: false,
  cropMarkLengthMm: undefined,
  cropMarkOffsetMm: undefined,
  cropMarkWeightPt: undefined,
};

export type SetPageBoxesParametersHook =
  BaseParametersHook<SetPageBoxesParameters>;

export const parseBoxString = (value: string): number[] | null => {
  const parts = value.split(",").map((part) => Number(part.trim()));
  if (parts.length !== 4 || parts.some((n) => !Number.isFinite(n))) {
    return null;
  }
  return parts;
};

/** Whether these parameters are complete enough to run. Shared by the tool's settings
 * hook and its operationConfig, so the editor and the pipeline builder agree. */
export function validateSetPageBoxesParameters(
  params: SetPageBoxesParameters,
): boolean {
  const boxes = [
    params.mediaBox,
    params.cropBox,
    params.trimBox,
    params.bleedBox,
    params.artBox,
  ];

  const hasExplicitBox = boxes.some((box) => box.trim() !== "");
  const hasMargin = (params.trimMarginMm ?? 0) > 0 || (params.bleedMm ?? 0) > 0;
  if (
    !hasExplicitBox &&
    !hasMargin &&
    !params.deriveFromCropMarks &&
    !params.copyMissingFromMediaBox &&
    !params.generateBleed &&
    !params.addCropMarks
  ) {
    return false;
  }

  const boxesValid = boxes.every((box) => {
    if (box.trim() === "") return true;
    const parsed = parseBoxString(box);
    return parsed !== null && parsed[2] > 0 && parsed[3] > 0;
  });
  if (!boxesValid) return false;

  if (params.generateBleed) {
    const anySide =
      (params.bleedMm ?? 0) > 0 ||
      (params.bleedTopMm ?? 0) > 0 ||
      (params.bleedRightMm ?? 0) > 0 ||
      (params.bleedBottomMm ?? 0) > 0 ||
      (params.bleedLeftMm ?? 0) > 0 ||
      params.bleedBox.trim() !== "";
    if (!anySide) return false;
    const dpi = params.bleedDpi;
    if (dpi !== undefined && (dpi < 72 || dpi > 600)) return false;
    if ((params.bleedInsetMm ?? 0) < 0) return false;
  }

  if (params.addCropMarks) {
    if ((params.cropMarkLengthMm ?? 5) <= 0) return false;
    if ((params.cropMarkOffsetMm ?? 3) < 0) return false;
    if ((params.cropMarkWeightPt ?? 0.25) <= 0) return false;
  }

  return true;
}

export const useSetPageBoxesParameters = (): SetPageBoxesParametersHook => {
  return useBaseParameters({
    defaultParameters,
    endpointName: "set-page-boxes",
    validateFn: validateSetPageBoxesParameters,
  });
};
