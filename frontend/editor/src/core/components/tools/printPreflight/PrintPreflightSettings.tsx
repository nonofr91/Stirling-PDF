import { Stack, Text, Checkbox, NumberInput } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { PrintPreflightParameters } from "@app/hooks/tools/printPreflight/usePrintPreflightParameters";

interface PrintPreflightSettingsProps {
  parameters: PrintPreflightParameters;
  onParameterChange: <K extends keyof PrintPreflightParameters>(
    key: K,
    value: PrintPreflightParameters[K],
  ) => void;
  disabled?: boolean;
}

const PrintPreflightSettings = ({
  parameters,
  onParameterChange,
  disabled = false,
}: PrintPreflightSettingsProps) => {
  const { t } = useTranslation();

  return (
    <Stack gap="md">
      <NumberInput
        label={t("printPreflight.requiredBleedMm.label", "Required bleed (mm)")}
        description={t(
          "printPreflight.requiredBleedMm.help",
          "Bleed width expected on every side past the trim. 3 mm is standard in Europe.",
        )}
        placeholder="3"
        min={0}
        decimalScale={1}
        value={parameters.requiredBleedMm ?? undefined}
        onChange={(value) =>
          onParameterChange(
            "requiredBleedMm",
            value === "" || value === undefined ? undefined : Number(value),
          )
        }
        disabled={disabled}
      />

      <NumberInput
        label={t(
          "printPreflight.minImageDpi.label",
          "Minimum image resolution (dpi)",
        )}
        description={t(
          "printPreflight.minImageDpi.help",
          "Images placed below this effective resolution are flagged. 300 is ideal for offset; 150 is a common minimum.",
        )}
        placeholder="150"
        min={1}
        value={parameters.minImageDpi ?? undefined}
        onChange={(value) =>
          onParameterChange(
            "minImageDpi",
            value === "" || value === undefined ? undefined : Number(value),
          )
        }
        disabled={disabled}
      />

      <NumberInput
        label={t(
          "printPreflight.hairlineThresholdPt.label",
          "Hairline threshold (pt)",
        )}
        description={t(
          "printPreflight.hairlineThresholdPt.help",
          "Strokes thinner than this are flagged — they may drop out on press.",
        )}
        placeholder="0.25"
        min={0}
        decimalScale={2}
        value={parameters.hairlineThresholdPt ?? undefined}
        onChange={(value) =>
          onParameterChange(
            "hairlineThresholdPt",
            value === "" || value === undefined ? undefined : Number(value),
          )
        }
        disabled={disabled}
      />

      <Checkbox
        checked={parameters.checkBleedCoverage}
        onChange={(event) =>
          onParameterChange("checkBleedCoverage", event.currentTarget.checked)
        }
        disabled={disabled}
        label={
          <div>
            <Text size="sm">
              {t(
                "printPreflight.checkBleedCoverage.label",
                "Check bleed is painted",
              )}
            </Text>
            <Text size="xs" c="dimmed">
              {t(
                "printPreflight.checkBleedCoverage.desc",
                "Render each page to verify the bleed area is actually covered by content, not just declared.",
              )}
            </Text>
          </div>
        }
      />
    </Stack>
  );
};

export default PrintPreflightSettings;
