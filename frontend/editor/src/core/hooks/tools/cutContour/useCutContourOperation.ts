import { useTranslation } from "react-i18next";
import {
  useToolOperation,
  defineSingleFileTool,
} from "@app/hooks/tools/shared/useToolOperation";
import {
  objectToFormData,
  type ToolApiParams,
  type ToolEndpoint,
} from "@app/hooks/tools/shared/toolApiMapping";
import { createStandardErrorHandler } from "@app/utils/toolErrorHandler";
import {
  validateCutContourParameters,
  CutContourParameters,
  defaultParameters,
} from "@app/hooks/tools/cutContour/useCutContourParameters";

const ENDPOINT = "/api/v1/general/cut-contour" satisfies ToolEndpoint;
type CutContourApiParams = ToolApiParams[typeof ENDPOINT];

const nonEmpty = (value: string): string | undefined =>
  value.trim() === "" ? undefined : value;

export const cutContourToApiParams = (
  parameters: CutContourParameters,
): CutContourApiParams => ({
  roi:
    parameters.roiPoints.length >= 6
      ? parameters.roiPoints.join(",")
      : undefined,
  roiPage: parameters.roiPage > 0 ? parameters.roiPage : undefined,
  extractionMode: parameters.extractionMode,
  dpi: parameters.dpi,
  alphaThreshold: parameters.alphaThreshold,
  backgroundTolerance: parameters.backgroundTolerance,
  minAreaMm2: parameters.minAreaMm2,
  smoothness: parameters.smoothness,
  offsetMm: parameters.offsetMm,
  keepHoles: parameters.keepHoles,
  aiThreshold: parameters.aiThreshold,
  aiModelId: nonEmpty(parameters.aiModelId),
  spotName: parameters.spotName,
  strokeWidthPt: parameters.strokeWidthPt,
  clipArtwork: parameters.clipArtwork,
  bleedMm: parameters.bleedMm,
  trimToContour: parameters.trimToContour,
  processingSteps: parameters.processingSteps,
  layerName: nonEmpty(parameters.layerName),
});

export const cutContourFromApiParams = (
  apiParams: CutContourApiParams,
): Partial<CutContourParameters> => ({
  extractionMode:
    apiParams.extractionMode ?? defaultParameters.extractionMode,
  dpi: apiParams.dpi,
  alphaThreshold: apiParams.alphaThreshold,
  backgroundTolerance: apiParams.backgroundTolerance,
  mergeGapMm: apiParams.mergeGapMm,
  roiPoints:
    apiParams.roi
      ?.split(",")
      .map(Number)
      .filter((v) => Number.isFinite(v)) ?? [],
  roiPage: apiParams.roiPage ?? 0,
  minAreaMm2: apiParams.minAreaMm2,
  smoothness: apiParams.smoothness,
  offsetMm: apiParams.offsetMm,
  keepHoles: apiParams.keepHoles ?? defaultParameters.keepHoles,
  aiThreshold: apiParams.aiThreshold,
  aiModelId: apiParams.aiModelId ?? defaultParameters.aiModelId,
  spotName: apiParams.spotName ?? defaultParameters.spotName,
  strokeWidthPt: apiParams.strokeWidthPt,
  clipArtwork: apiParams.clipArtwork ?? defaultParameters.clipArtwork,
  bleedMm: apiParams.bleedMm,
  trimToContour: apiParams.trimToContour ?? defaultParameters.trimToContour,
  processingSteps:
    apiParams.processingSteps ?? defaultParameters.processingSteps,
  layerName: apiParams.layerName ?? defaultParameters.layerName,
});

export const buildCutContourFormData = (
  parameters: CutContourParameters,
  file: File,
): FormData =>
  objectToFormData(cutContourToApiParams(parameters), { fileInput: file });

export const cutContourOperationConfig = defineSingleFileTool({
  validateParams: validateCutContourParameters,
  buildFormData: buildCutContourFormData,
  toApiParams: cutContourToApiParams,
  fromApiParams: cutContourFromApiParams,
  operationType: "cutContour",
  endpoint: ENDPOINT,
  defaultParameters,
});

export const useCutContourOperation = () => {
  const { t } = useTranslation();

  return useToolOperation<CutContourParameters>({
    ...cutContourOperationConfig,
    getErrorMessage: createStandardErrorHandler(
      t(
        "cutContour.error.failed",
        "An error occurred while creating the cut contour.",
      ),
    ),
  });
};
