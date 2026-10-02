import { useTranslation } from "react-i18next";
import { createToolFlow } from "@app/components/tools/shared/createToolFlow";
import CutContourSettings from "@app/components/tools/cutContour/CutContourSettings";
import { useCutContourParameters } from "@app/hooks/tools/cutContour/useCutContourParameters";
import { useCutContourOperation } from "@app/hooks/tools/cutContour/useCutContourOperation";
import { useBaseTool } from "@app/hooks/tools/shared/useBaseTool";
import { BaseToolProps, ToolComponent } from "@app/types/tool";

const CutContour = (props: BaseToolProps) => {
  const { t } = useTranslation();

  const base = useBaseTool(
    "cutContour",
    useCutContourParameters,
    useCutContourOperation,
    props,
  );

  return createToolFlow({
    files: {
      selectedFiles: base.selectedFiles,
      isCollapsed: base.hasResults,
    },
    steps: [
      {
        title: t("cutContour.steps.configure", "Cut Contour Settings"),
        isCollapsed: base.settingsCollapsed,
        onCollapsedClick: base.hasResults
          ? base.handleSettingsReset
          : undefined,
        content: (
          <CutContourSettings
            parameters={base.params.parameters}
            onParameterChange={base.params.updateParameter}
            disabled={base.endpointLoading}
          />
        ),
      },
    ],
    executeButton: {
      text: t("cutContour.submit", "Create Cut Contour"),
      isVisible: !base.hasResults,
      loadingText: t("loading"),
      onClick: base.handleExecute,
      endpointEnabled: base.endpointEnabled,
      paramsValid: base.params.validateParameters(),
    },
    review: {
      isVisible: base.hasResults,
      operation: base.operation,
      title: t("cutContour.results.title", "Cut Contour Results"),
      onFileClick: base.handleThumbnailClick,
      onUndo: base.handleUndo,
    },
  });
};

// Static method to get the operation hook for automation
CutContour.tool = () => useCutContourOperation;

export default CutContour as ToolComponent;
