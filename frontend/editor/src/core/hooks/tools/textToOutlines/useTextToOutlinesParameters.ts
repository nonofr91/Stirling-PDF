import { BaseParameters } from "@app/types/parameters";
import {
  useBaseParameters,
  BaseParametersHook,
} from "@app/hooks/tools/shared/useBaseParameters";

export type TextToOutlinesParameters = BaseParameters;

export const defaultParameters: TextToOutlinesParameters = {};

export type TextToOutlinesParametersHook =
  BaseParametersHook<TextToOutlinesParameters>;

export const useTextToOutlinesParameters = (): TextToOutlinesParametersHook => {
  return useBaseParameters({
    defaultParameters,
    endpointName: "text-to-outlines",
  });
};
