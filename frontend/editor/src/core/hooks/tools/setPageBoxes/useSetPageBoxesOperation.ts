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
  validateSetPageBoxesParameters,
  SetPageBoxesParameters,
  defaultParameters,
} from "@app/hooks/tools/setPageBoxes/useSetPageBoxesParameters";

const ENDPOINT = "/api/v1/general/set-page-boxes" satisfies ToolEndpoint;
type SetPageBoxesApiParams = ToolApiParams[typeof ENDPOINT];

const nonEmpty = (value: string): string | undefined =>
  value.trim() === "" ? undefined : value;

export const setPageBoxesToApiParams = (
  parameters: SetPageBoxesParameters,
): SetPageBoxesApiParams => ({
  mediaBox: nonEmpty(parameters.mediaBox),
  cropBox: nonEmpty(parameters.cropBox),
  trimBox: nonEmpty(parameters.trimBox),
  bleedBox: nonEmpty(parameters.bleedBox),
  artBox: nonEmpty(parameters.artBox),
  trimMarginMm: parameters.trimMarginMm,
  bleedMm: parameters.bleedMm,
  copyMissingFromMediaBox: parameters.copyMissingFromMediaBox,
  drawBoxes: parameters.drawBoxes,
});

export const setPageBoxesFromApiParams = (
  apiParams: SetPageBoxesApiParams,
): Partial<SetPageBoxesParameters> => ({
  mediaBox: apiParams.mediaBox ?? defaultParameters.mediaBox,
  cropBox: apiParams.cropBox ?? defaultParameters.cropBox,
  trimBox: apiParams.trimBox ?? defaultParameters.trimBox,
  bleedBox: apiParams.bleedBox ?? defaultParameters.bleedBox,
  artBox: apiParams.artBox ?? defaultParameters.artBox,
  trimMarginMm: apiParams.trimMarginMm,
  bleedMm: apiParams.bleedMm,
  copyMissingFromMediaBox:
    apiParams.copyMissingFromMediaBox ??
    defaultParameters.copyMissingFromMediaBox,
  drawBoxes: apiParams.drawBoxes ?? defaultParameters.drawBoxes,
});

export const buildSetPageBoxesFormData = (
  parameters: SetPageBoxesParameters,
  file: File,
): FormData =>
  objectToFormData(setPageBoxesToApiParams(parameters), { fileInput: file });

export const setPageBoxesOperationConfig = defineSingleFileTool({
  validateParams: validateSetPageBoxesParameters,
  buildFormData: buildSetPageBoxesFormData,
  toApiParams: setPageBoxesToApiParams,
  fromApiParams: setPageBoxesFromApiParams,
  operationType: "setPageBoxes",
  endpoint: ENDPOINT,
  defaultParameters,
});

export const useSetPageBoxesOperation = () => {
  const { t } = useTranslation();

  return useToolOperation<SetPageBoxesParameters>({
    ...setPageBoxesOperationConfig,
    getErrorMessage: createStandardErrorHandler(
      t(
        "setPageBoxes.error.failed",
        "An error occurred while setting page boxes.",
      ),
    ),
  });
};
