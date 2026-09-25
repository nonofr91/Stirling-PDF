/**
 * CropAutomationSettings - Used for automation only
 *
 * Simplified crop settings for automation that doesn't require a file preview.
 * Allows users to manually enter crop coordinates and dimensions.
 */

import { Stack, Checkbox } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { CropParameters } from "@app/hooks/tools/crop/useCropParameters";
import { Rectangle } from "@app/utils/cropCoordinates";
import { PageBox } from "@app/constants/pageBoxConstants";
import CropCoordinateInputs from "@app/components/tools/crop/CropCoordinateInputs";
import PageBoxSelect from "@app/components/tools/shared/PageBoxSelect";

interface CropAutomationSettingsProps {
  parameters: CropParameters;
  onParameterChange: <K extends keyof CropParameters>(
    key: K,
    value: CropParameters[K],
  ) => void;
  disabled?: boolean;
}

const CropAutomationSettings = ({
  parameters,
  onParameterChange,
  disabled = false,
}: CropAutomationSettingsProps) => {
  const { t } = useTranslation();

  // Handle coordinate changes
  const handleCoordinateChange = (
    field: keyof Rectangle,
    value: number | string,
  ) => {
    const numValue = typeof value === "string" ? parseFloat(value) : value;
    if (isNaN(numValue)) return;

    const newCropArea = { ...parameters.cropArea, [field]: numValue };
    onParameterChange("cropArea", newCropArea);
  };

  return (
    <Stack gap="md">
      <Checkbox
        label={t("crop.cropToBox", "Crop to a named page box")}
        checked={parameters.cropToBox}
        onChange={(e) =>
          onParameterChange("cropToBox", e.currentTarget.checked)
        }
        disabled={disabled}
      />

      {parameters.cropToBox && (
        <PageBoxSelect
          value={parameters.pageBox}
          onChange={(v: PageBox) => onParameterChange("pageBox", v)}
          disabled={disabled}
        />
      )}

      {!parameters.cropToBox && (
        <CropCoordinateInputs
          cropArea={parameters.cropArea}
          onCoordinateChange={handleCoordinateChange}
          disabled={disabled}
          showAutomationInfo={true}
        />
      )}
    </Stack>
  );
};

export default CropAutomationSettings;
