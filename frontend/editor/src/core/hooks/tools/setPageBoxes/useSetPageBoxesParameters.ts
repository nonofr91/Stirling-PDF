import { BaseParameters } from "@app/types/parameters";
import {
  useBaseParameters,
  BaseParametersHook,
} from "@app/hooks/tools/shared/useBaseParameters";

/** Box coordinates as "x,y,width,height" in points; empty means untouched. */
export interface SetPageBoxesParameters extends BaseParameters {
  mediaBox: string;
  cropBox: string;
  trimBox: string;
  bleedBox: string;
  artBox: string;
  trimMarginMm?: number;
  bleedMm?: number;
  copyMissingFromMediaBox: boolean;
  drawBoxes: boolean;
}

export const defaultParameters: SetPageBoxesParameters = {
  mediaBox: "",
  cropBox: "",
  trimBox: "",
  bleedBox: "",
  artBox: "",
  trimMarginMm: undefined,
  bleedMm: undefined,
  copyMissingFromMediaBox: false,
  drawBoxes: false,
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
    !params.copyMissingFromMediaBox &&
    !params.drawBoxes
  ) {
    return false;
  }

  return boxes.every((box) => {
    if (box.trim() === "") return true;
    const parsed = parseBoxString(box);
    return parsed !== null && parsed[2] > 0 && parsed[3] > 0;
  });
}

export const useSetPageBoxesParameters = (): SetPageBoxesParametersHook => {
  return useBaseParameters({
    defaultParameters,
    endpointName: "set-page-boxes",
    validateFn: validateSetPageBoxesParameters,
  });
};
