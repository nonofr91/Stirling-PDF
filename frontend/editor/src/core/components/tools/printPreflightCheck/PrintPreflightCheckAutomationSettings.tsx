import PrintPreflightStepSettings from "@app/components/tools/printPreflight/PrintPreflightStepSettings";
import type { PrintPreflightStepParameters } from "@app/hooks/tools/printPreflight/preflightStep";
import type { ToolAutomationSettingsProps } from "@app/hooks/tools/shared/toolOperationTypes";

const PrintPreflightCheckAutomationSettings = (
  props: ToolAutomationSettingsProps<PrintPreflightStepParameters>,
) => <PrintPreflightStepSettings {...props} variant="check" />;

export default PrintPreflightCheckAutomationSettings;
