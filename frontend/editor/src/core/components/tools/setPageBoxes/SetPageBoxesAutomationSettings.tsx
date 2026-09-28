import { SetPageBoxesParameters } from "@app/hooks/tools/setPageBoxes/useSetPageBoxesParameters";
import SetPageBoxesFields from "@app/components/tools/setPageBoxes/SetPageBoxesFields";

interface SetPageBoxesAutomationSettingsProps {
  parameters: SetPageBoxesParameters;
  onParameterChange: <K extends keyof SetPageBoxesParameters>(
    key: K,
    value: SetPageBoxesParameters[K],
  ) => void;
  disabled?: boolean;
}

/**
 * Automation variant: no file is attached in the pipeline builder, so the
 * document-dependent preview (snapshot, diagram, viewer overlay) stays out.
 */
const SetPageBoxesAutomationSettings = ({
  parameters,
  onParameterChange,
  disabled = false,
}: SetPageBoxesAutomationSettingsProps) => (
  <SetPageBoxesFields
    parameters={parameters}
    onParameterChange={onParameterChange}
    disabled={disabled}
  />
);

export default SetPageBoxesAutomationSettings;
