import { CutContourParameters } from "@app/hooks/tools/cutContour/useCutContourParameters";
import CutContourFields from "@app/components/tools/cutContour/CutContourFields";

interface CutContourAutomationSettingsProps {
  parameters: CutContourParameters;
  onParameterChange: <K extends keyof CutContourParameters>(
    key: K,
    value: CutContourParameters[K],
  ) => void;
  disabled?: boolean;
}

/**
 * Automation variant: no file is attached in the pipeline builder, so the
 * document-dependent preview (viewer overlay) stays out.
 */
const CutContourAutomationSettings = ({
  parameters,
  onParameterChange,
  disabled = false,
}: CutContourAutomationSettingsProps) => (
  <CutContourFields
    parameters={parameters}
    onParameterChange={onParameterChange}
    disabled={disabled}
  />
);

export default CutContourAutomationSettings;
