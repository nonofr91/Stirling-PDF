import { useTranslation } from "react-i18next";
import { createToolFlow } from "@app/components/tools/shared/createToolFlow";
import TextToOutlinesSettings from "@app/components/tools/textToOutlines/TextToOutlinesSettings";
import { useTextToOutlinesParameters } from "@app/hooks/tools/textToOutlines/useTextToOutlinesParameters";
import { useTextToOutlinesOperation } from "@app/hooks/tools/textToOutlines/useTextToOutlinesOperation";
import { useBaseTool } from "@app/hooks/tools/shared/useBaseTool";
import { BaseToolProps, ToolComponent } from "@app/types/tool";

const TextToOutlines = (props: BaseToolProps) => {
  const { t } = useTranslation();

  const base = useBaseTool(
    "textToOutlines",
    useTextToOutlinesParameters,
    useTextToOutlinesOperation,
    props,
  );

  return createToolFlow({
    files: {
      selectedFiles: base.selectedFiles,
      isCollapsed: base.hasResults,
    },
    steps: [
      {
        title: t("textToOutlines.steps.about", "About Text to Outlines"),
        isCollapsed: base.settingsCollapsed,
        onCollapsedClick: base.hasResults
          ? base.handleSettingsReset
          : undefined,
        content: <TextToOutlinesSettings />,
      },
    ],
    executeButton: {
      text: t("textToOutlines.submit", "Convert Text to Outlines"),
      isVisible: !base.hasResults,
      loadingText: t("loading"),
      onClick: base.handleExecute,
      endpointEnabled: base.endpointEnabled,
      paramsValid: base.params.validateParameters(),
    },
    review: {
      isVisible: base.hasResults,
      operation: base.operation,
      title: t("textToOutlines.results.title", "Text to Outlines Results"),
      onFileClick: base.handleThumbnailClick,
      onUndo: base.handleUndo,
    },
  });
};

// Static method to get the operation hook for automation
TextToOutlines.tool = () => useTextToOutlinesOperation;

export default TextToOutlines as ToolComponent;
