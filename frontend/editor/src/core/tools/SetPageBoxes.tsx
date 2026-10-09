import { useTranslation } from "react-i18next";
import { createToolFlow } from "@app/components/tools/shared/createToolFlow";
import SetPageBoxesSettings from "@app/components/tools/setPageBoxes/SetPageBoxesSettings";
import { useSetPageBoxesParameters } from "@app/hooks/tools/setPageBoxes/useSetPageBoxesParameters";
import { useSetPageBoxesOperation } from "@app/hooks/tools/setPageBoxes/useSetPageBoxesOperation";
import { useBaseTool } from "@app/hooks/tools/shared/useBaseTool";
import { BaseToolProps, ToolComponent } from "@app/types/tool";

const SetPageBoxes = (props: BaseToolProps) => {
  const { t } = useTranslation();

  const base = useBaseTool(
    "setPageBoxes",
    useSetPageBoxesParameters,
    useSetPageBoxesOperation,
    props,
  );

  return createToolFlow({
    files: {
      selectedFiles: base.selectedFiles,
      isCollapsed: base.hasResults,
    },
    steps: [
      {
        title: t("setPageBoxes.steps.configure", "Page Box Settings"),
        isCollapsed: base.settingsCollapsed,
        onCollapsedClick: base.hasResults
          ? base.handleSettingsReset
          : undefined,
        content: (
          <SetPageBoxesSettings
            parameters={base.params.parameters}
            onParameterChange={base.params.updateParameter}
            disabled={base.endpointLoading}
          />
        ),
      },
    ],
    executeButton: {
      text: t("setPageBoxes.submit", "Set Page Boxes"),
      isVisible: !base.hasResults,
      loadingText: t("loading"),
      onClick: base.handleExecute,
      endpointEnabled: base.endpointEnabled,
      paramsValid: base.params.validateParameters(),
    },
    review: {
      isVisible: base.hasResults,
      operation: base.operation,
      title: t("setPageBoxes.results.title", "Set Page Boxes Results"),
      onFileClick: base.handleThumbnailClick,
      onUndo: base.handleUndo,
    },
  });
};

// Static method to get the operation hook for automation
SetPageBoxes.tool = () => useSetPageBoxesOperation;

export default SetPageBoxes as ToolComponent;
