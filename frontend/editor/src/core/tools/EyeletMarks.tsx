import { useTranslation } from "react-i18next";
import { createToolFlow } from "@app/components/tools/shared/createToolFlow";
import EyeletMarksSettings from "@app/components/tools/eyeletMarks/EyeletMarksSettings";
import { useEyeletMarksParameters } from "@app/hooks/tools/eyeletMarks/useEyeletMarksParameters";
import { useEyeletMarksOperation } from "@app/hooks/tools/eyeletMarks/useEyeletMarksOperation";
import { useBaseTool } from "@app/hooks/tools/shared/useBaseTool";
import { BaseToolProps, ToolComponent } from "@app/types/tool";

const EyeletMarks = (props: BaseToolProps) => {
  const { t } = useTranslation();

  const base = useBaseTool(
    "eyeletMarks",
    useEyeletMarksParameters,
    useEyeletMarksOperation,
    props,
  );

  return createToolFlow({
    files: {
      selectedFiles: base.selectedFiles,
      isCollapsed: base.hasResults,
    },
    steps: [
      {
        title: t("eyeletMarks.steps.configure", "Eyelet Mark Settings"),
        isCollapsed: base.settingsCollapsed,
        onCollapsedClick: base.hasResults
          ? base.handleSettingsReset
          : undefined,
        content: (
          <EyeletMarksSettings
            parameters={base.params.parameters}
            onParameterChange={base.params.updateParameter}
            disabled={base.endpointLoading}
          />
        ),
      },
    ],
    executeButton: {
      text: t("eyeletMarks.submit", "Add Eyelet Marks"),
      isVisible: !base.hasResults,
      loadingText: t("loading"),
      onClick: base.handleExecute,
      endpointEnabled: base.endpointEnabled,
      paramsValid: base.params.validateParameters(),
    },
    review: {
      isVisible: base.hasResults,
      operation: base.operation,
      title: t("eyeletMarks.results.title", "Eyelet Marks Results"),
      onFileClick: base.handleThumbnailClick,
      onUndo: base.handleUndo,
    },
  });
};

// Static method to get the operation hook for automation
EyeletMarks.tool = () => useEyeletMarksOperation;

export default EyeletMarks as ToolComponent;
