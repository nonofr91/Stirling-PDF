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
  TextToOutlinesParameters,
  defaultParameters,
} from "@app/hooks/tools/textToOutlines/useTextToOutlinesParameters";

const ENDPOINT = "/api/v1/misc/text-to-outlines" satisfies ToolEndpoint;
type TextToOutlinesApiParams = ToolApiParams[typeof ENDPOINT];

export const textToOutlinesToApiParams = (
  _parameters: TextToOutlinesParameters,
): TextToOutlinesApiParams => ({});

export const textToOutlinesFromApiParams = (
  _apiParams: TextToOutlinesApiParams,
): Partial<TextToOutlinesParameters> => ({});

export const buildTextToOutlinesFormData = (
  parameters: TextToOutlinesParameters,
  file: File,
): FormData =>
  objectToFormData(textToOutlinesToApiParams(parameters), {
    fileInput: file,
  });

export const textToOutlinesOperationConfig = defineSingleFileTool({
  buildFormData: buildTextToOutlinesFormData,
  toApiParams: textToOutlinesToApiParams,
  fromApiParams: textToOutlinesFromApiParams,
  operationType: "textToOutlines",
  endpoint: ENDPOINT,
  defaultParameters,
});

export const useTextToOutlinesOperation = () => {
  const { t } = useTranslation();

  return useToolOperation<TextToOutlinesParameters>({
    ...textToOutlinesOperationConfig,
    getErrorMessage: createStandardErrorHandler(
      t(
        "textToOutlines.error.failed",
        "An error occurred while converting text to outlines.",
      ),
    ),
  });
};
