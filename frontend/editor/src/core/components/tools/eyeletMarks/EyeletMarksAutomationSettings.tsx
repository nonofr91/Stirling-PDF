import { EyeletMarksParameters } from "@app/hooks/tools/eyeletMarks/useEyeletMarksParameters";
import EyeletMarksFields from "@app/components/tools/eyeletMarks/EyeletMarksFields";

interface EyeletMarksAutomationSettingsProps {
  parameters: EyeletMarksParameters;
  onParameterChange: <K extends keyof EyeletMarksParameters>(
    key: K,
    value: EyeletMarksParameters[K],
  ) => void;
  disabled?: boolean;
}

/**
 * Automation variant: no file is attached in the pipeline builder, so the
 * document-dependent preview (viewer overlay) stays out.
 */
const EyeletMarksAutomationSettings = ({
  parameters,
  onParameterChange,
  disabled = false,
}: EyeletMarksAutomationSettingsProps) => (
  <EyeletMarksFields
    parameters={parameters}
    onParameterChange={onParameterChange}
    disabled={disabled}
  />
);

export default EyeletMarksAutomationSettings;
