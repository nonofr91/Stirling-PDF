import { Stack, TextInput, NumberInput, Checkbox, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import {
  SetPageBoxesParameters,
  parseBoxString,
} from "@app/hooks/tools/setPageBoxes/useSetPageBoxesParameters";

interface SetPageBoxesSettingsProps {
  parameters: SetPageBoxesParameters;
  onParameterChange: <K extends keyof SetPageBoxesParameters>(
    key: K,
    value: SetPageBoxesParameters[K],
  ) => void;
  disabled?: boolean;
}

const BOX_FIELDS = [
  "mediaBox",
  "cropBox",
  "trimBox",
  "bleedBox",
  "artBox",
] as const;

const SetPageBoxesSettings = ({
  parameters,
  onParameterChange,
  disabled = false,
}: SetPageBoxesSettingsProps) => {
  const { t } = useTranslation();

  const boxError = (value: string) =>
    value.trim() !== "" && parseBoxString(value) === null
      ? t(
          "setPageBoxes.boxFormatError",
          "Expected four numbers: x,y,width,height",
        )
      : undefined;

  return (
    <Stack gap="md">
      <Text size="sm" c="dimmed">
        {t(
          "setPageBoxes.help",
          "Set page boxes in points as x,y,width,height. Leave a field empty to keep the existing box.",
        )}
      </Text>

      {BOX_FIELDS.map((field) => (
        <TextInput
          key={field}
          label={t(`setPageBoxes.${field}`, field)}
          placeholder="0,0,595,842"
          value={parameters[field]}
          onChange={(e) => onParameterChange(field, e.currentTarget.value)}
          error={boxError(parameters[field])}
          disabled={disabled}
        />
      ))}

      <NumberInput
        label={t(
          "setPageBoxes.trimMarginMm",
          "Trim margin (mm, shrink MediaBox into TrimBox)",
        )}
        value={parameters.trimMarginMm}
        onChange={(v) =>
          onParameterChange(
            "trimMarginMm",
            typeof v === "number" ? v : undefined,
          )
        }
        min={0}
        decimalScale={2}
        disabled={disabled}
      />

      <NumberInput
        label={t("setPageBoxes.bleedMm", "Bleed (mm, expand around TrimBox)")}
        value={parameters.bleedMm}
        onChange={(v) =>
          onParameterChange("bleedMm", typeof v === "number" ? v : undefined)
        }
        min={0}
        decimalScale={2}
        disabled={disabled}
      />

      <Checkbox
        label={t(
          "setPageBoxes.copyMissingFromMediaBox",
          "Copy MediaBox into boxes left unset",
        )}
        checked={parameters.copyMissingFromMediaBox}
        onChange={(e) =>
          onParameterChange("copyMissingFromMediaBox", e.currentTarget.checked)
        }
        disabled={disabled}
      />
    </Stack>
  );
};

export default SetPageBoxesSettings;
