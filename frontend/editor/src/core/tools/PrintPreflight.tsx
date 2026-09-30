import { useTranslation } from "react-i18next";
import { createToolFlow } from "@app/components/tools/shared/createToolFlow";
import PrintPreflightSettings from "@app/components/tools/printPreflight/PrintPreflightSettings";
import PrintPreflightResults from "@app/components/tools/printPreflight/PrintPreflightResults";
import { usePrintPreflightParameters } from "@app/hooks/tools/printPreflight/usePrintPreflightParameters";
import {
  usePrintPreflightOperation,
  PrintPreflightOperationHook,
} from "@app/hooks/tools/printPreflight/usePrintPreflightOperation";
import { useBaseTool } from "@app/hooks/tools/shared/useBaseTool";
import { BaseToolProps, ToolComponent } from "@app/types/tool";

const PrintPreflight = (props: BaseToolProps) => {
  const { t } = useTranslation();

  const base = useBaseTool(
    "printPreflight",
    usePrintPreflightParameters,
    usePrintPreflightOperation,
    props,
  );

  const operation = base.operation as PrintPreflightOperationHook;
  const showResults =
    operation.results.length > 0 ||
    base.operation.isLoading ||
    !!base.operation.errorMessage;

  return createToolFlow({
    files: {
      selectedFiles: base.selectedFiles,
      isCollapsed: operation.results.length > 0,
    },
    steps: [
      {
        title: t("printPreflight.options.stepTitle", "Check thresholds"),
        isCollapsed: base.settingsCollapsed,
        onCollapsedClick: base.settingsCollapsed
          ? base.handleSettingsReset
          : undefined,
        content: (
          <PrintPreflightSettings
            parameters={base.params.parameters}
            onParameterChange={base.params.updateParameter}
            disabled={base.endpointLoading}
          />
        ),
      },
      {
        title: t("printPreflight.results", "Results"),
        isVisible: showResults,
        isCollapsed: false,
        content: (
          <PrintPreflightResults
            operation={operation}
            isLoading={base.operation.isLoading}
            errorMessage={base.operation.errorMessage}
          />
        ),
      },
    ],
    executeButton: {
      text: t("printPreflight.submit", "Run preflight"),
      isVisible: true,
      loadingText: t("loading"),
      onClick: base.handleExecute,
      endpointEnabled: base.endpointEnabled,
      paramsValid: base.params.validateParameters(),
    },
    review: {
      isVisible: false,
      operation: base.operation,
      title: t("printPreflight.results", "Results"),
      onUndo: base.handleUndo,
    },
  });
};

PrintPreflight.tool = () => usePrintPreflightOperation;

export default PrintPreflight as ToolComponent;
