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
  validateEyeletMarksParameters,
  EyeletMarksParameters,
  defaultParameters,
} from "@app/hooks/tools/eyeletMarks/useEyeletMarksParameters";

const ENDPOINT = "/api/v1/general/eyelet-marks" satisfies ToolEndpoint;
type EyeletMarksApiParams = ToolApiParams[typeof ENDPOINT];

const nonEmpty = (value: string): string | undefined =>
  value.trim() === "" ? undefined : value;

export const eyeletMarksToApiParams = (
  parameters: EyeletMarksParameters,
): EyeletMarksApiParams => ({
  mode: parameters.mode,
  eyeletDiameterMm: parameters.eyeletDiameterMm,
  strokeWidthPt: parameters.strokeWidthPt,
  offsetTopMm: parameters.offsetTopMm,
  offsetRightMm: parameters.offsetRightMm,
  offsetBottomMm: parameters.offsetBottomMm,
  offsetLeftMm: parameters.offsetLeftMm,
  countTop: parameters.countTop,
  countRight: parameters.countRight,
  countBottom: parameters.countBottom,
  countLeft: parameters.countLeft,
  maxSpacingTopMm: parameters.maxSpacingTopMm,
  maxSpacingRightMm: parameters.maxSpacingRightMm,
  maxSpacingBottomMm: parameters.maxSpacingBottomMm,
  maxSpacingLeftMm: parameters.maxSpacingLeftMm,
  layerName: nonEmpty(parameters.layerName),
});

export const eyeletMarksFromApiParams = (
  apiParams: EyeletMarksApiParams,
): Partial<EyeletMarksParameters> => ({
  mode: apiParams.mode ?? defaultParameters.mode,
  eyeletDiameterMm: apiParams.eyeletDiameterMm,
  strokeWidthPt: apiParams.strokeWidthPt,
  offsetTopMm: apiParams.offsetTopMm,
  offsetRightMm: apiParams.offsetRightMm,
  offsetBottomMm: apiParams.offsetBottomMm,
  offsetLeftMm: apiParams.offsetLeftMm,
  countTop: apiParams.countTop,
  countRight: apiParams.countRight,
  countBottom: apiParams.countBottom,
  countLeft: apiParams.countLeft,
  maxSpacingTopMm: apiParams.maxSpacingTopMm,
  maxSpacingRightMm: apiParams.maxSpacingRightMm,
  maxSpacingBottomMm: apiParams.maxSpacingBottomMm,
  maxSpacingLeftMm: apiParams.maxSpacingLeftMm,
  layerName: apiParams.layerName ?? defaultParameters.layerName,
});

export const buildEyeletMarksFormData = (
  parameters: EyeletMarksParameters,
  file: File,
): FormData =>
  objectToFormData(eyeletMarksToApiParams(parameters), { fileInput: file });

export const eyeletMarksOperationConfig = defineSingleFileTool({
  validateParams: validateEyeletMarksParameters,
  buildFormData: buildEyeletMarksFormData,
  toApiParams: eyeletMarksToApiParams,
  fromApiParams: eyeletMarksFromApiParams,
  operationType: "eyeletMarks",
  endpoint: ENDPOINT,
  defaultParameters,
});

export const useEyeletMarksOperation = () => {
  const { t } = useTranslation();

  return useToolOperation<EyeletMarksParameters>({
    ...eyeletMarksOperationConfig,
    getErrorMessage: createStandardErrorHandler(
      t(
        "eyeletMarks.error.failed",
        "An error occurred while adding eyelet marks.",
      ),
    ),
  });
};
