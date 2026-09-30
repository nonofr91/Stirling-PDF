import {
  Stack,
  TextInput,
  NumberInput,
  Checkbox,
  Text,
  Divider,
  Select,
  SimpleGrid,
} from "@mantine/core";
import { useTranslation } from "react-i18next";
import {
  SetPageBoxesParameters,
  parseBoxString,
  BLEED_METHODS,
  BleedMethod,
} from "@app/hooks/tools/setPageBoxes/useSetPageBoxesParameters";

export interface SetPageBoxesFieldsProps {
  parameters: SetPageBoxesParameters;
  onParameterChange: <K extends keyof SetPageBoxesParameters>(
    key: K,
    value: SetPageBoxesParameters[K],
  ) => void;
  disabled?: boolean;
  /** Current effective value per box, shown as the field's placeholder — the
      string the user would type to reproduce the box the overlay draws. */
  boxPlaceholders?: Partial<Record<(typeof BOX_FIELDS)[number], string>>;
}

const BOX_FIELDS = [
  "mediaBox",
  "cropBox",
  "trimBox",
  "bleedBox",
  "artBox",
] as const;

const BLEED_SIDE_FIELDS = [
  "bleedLeftMm",
  "bleedRightMm",
  "bleedTopMm",
  "bleedBottomMm",
] as const;

const numberOrUndefined = (v: string | number) =>
  typeof v === "number" ? v : undefined;

/**
 * The pure parameter inputs, shared by the tool's settings panel (which adds a
 * live preview of the document's boxes) and the automation variant (no file).
 */
const SetPageBoxesFields = ({
  parameters,
  onParameterChange,
  disabled = false,
  boxPlaceholders,
}: SetPageBoxesFieldsProps) => {
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
          placeholder={boxPlaceholders?.[field] ?? "0,0,595,842"}
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
          onParameterChange("trimMarginMm", numberOrUndefined(v))
        }
        min={0}
        decimalScale={2}
        disabled={disabled}
      />

      <NumberInput
        label={t("setPageBoxes.bleedMm", "Bleed (mm, expand around TrimBox)")}
        value={parameters.bleedMm}
        onChange={(v) => onParameterChange("bleedMm", numberOrUndefined(v))}
        min={0}
        decimalScale={2}
        disabled={disabled}
      />

      <Checkbox
        label={
          <div>
            <Text size="sm">
              {t(
                "setPageBoxes.deriveFromCropMarks.label",
                "Derive TrimBox from crop marks",
              )}
            </Text>
            <Text size="xs" c="dimmed">
              {t(
                "setPageBoxes.deriveFromCropMarks.desc",
                "Detect painted cut marks to place the TrimBox. Used only when no TrimBox or trim margin is given; fails on pages without clear marks.",
              )}
            </Text>
          </div>
        }
        checked={parameters.deriveFromCropMarks}
        onChange={(e) =>
          onParameterChange("deriveFromCropMarks", e.currentTarget.checked)
        }
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

      <Divider />

      <Stack gap="xs">
        <Text size="sm" fw={500}>
          {t("setPageBoxes.generateBleed.section", "Generate bleed")}
        </Text>
        <Text size="xs" c="dimmed">
          {t(
            "setPageBoxes.generateBleed.hint",
            "A BleedBox alone is only geometry. This paints extra content past the trim edge (like PitStop's Add Bleed) so trimming never shows a white edge.",
          )}
        </Text>
        <Checkbox
          label={t(
            "setPageBoxes.generateBleed.enable",
            "Paint bleed content between TrimBox and BleedBox",
          )}
          checked={parameters.generateBleed}
          onChange={(e) =>
            onParameterChange("generateBleed", e.currentTarget.checked)
          }
          disabled={disabled}
        />

        {parameters.generateBleed && (
          <Stack gap="sm" mt="xs">
            <Select
              label={t("setPageBoxes.generateBleed.method", "Bleed method")}
              data={BLEED_METHODS.map((method) => ({
                value: method,
                label: t(
                  `setPageBoxes.generateBleed.methods.${method}`,
                  method,
                ),
              }))}
              value={parameters.bleedMethod}
              onChange={(v) =>
                onParameterChange("bleedMethod", (v ?? "MIRROR") as BleedMethod)
              }
              allowDeselect={false}
              disabled={disabled}
            />
            <SimpleGrid cols={2}>
              {BLEED_SIDE_FIELDS.map((field) => (
                <NumberInput
                  key={field}
                  label={t(`setPageBoxes.generateBleed.${field}`, field)}
                  placeholder={t(
                    "setPageBoxes.generateBleed.sideFallback",
                    "bleedMm",
                  )}
                  value={parameters[field]}
                  onChange={(v) =>
                    onParameterChange(field, numberOrUndefined(v))
                  }
                  min={0}
                  decimalScale={2}
                  disabled={disabled}
                />
              ))}
            </SimpleGrid>
            <Checkbox
              label={t(
                "setPageBoxes.generateBleed.corners",
                "Generate bleed in the corners too",
              )}
              checked={parameters.bleedCorners}
              onChange={(e) =>
                onParameterChange("bleedCorners", e.currentTarget.checked)
              }
              disabled={disabled}
            />
            <SimpleGrid cols={2}>
              <NumberInput
                label={t("setPageBoxes.generateBleed.dpi", "Render DPI")}
                value={parameters.bleedDpi}
                onChange={(v) =>
                  onParameterChange("bleedDpi", numberOrUndefined(v))
                }
                min={72}
                max={600}
                placeholder="300"
                disabled={disabled}
              />
              <NumberInput
                label={t(
                  "setPageBoxes.generateBleed.inset",
                  "Source inset (mm, skip inner margin)",
                )}
                value={parameters.bleedInsetMm}
                onChange={(v) =>
                  onParameterChange("bleedInsetMm", numberOrUndefined(v))
                }
                min={0}
                decimalScale={2}
                disabled={disabled}
              />
            </SimpleGrid>
          </Stack>
        )}
      </Stack>

      <Divider />

      <Stack gap="xs">
        <Text size="sm" fw={500}>
          {t("setPageBoxes.cropMarks.section", "Crop marks")}
        </Text>
        <Checkbox
          label={t(
            "setPageBoxes.cropMarks.enable",
            "Draw crop marks at the TrimBox corners",
          )}
          checked={parameters.addCropMarks}
          onChange={(e) =>
            onParameterChange("addCropMarks", e.currentTarget.checked)
          }
          disabled={disabled}
        />
        {parameters.addCropMarks && (
          <SimpleGrid cols={3} mt="xs">
            <NumberInput
              label={t("setPageBoxes.cropMarks.length", "Length (mm)")}
              value={parameters.cropMarkLengthMm}
              onChange={(v) =>
                onParameterChange("cropMarkLengthMm", numberOrUndefined(v))
              }
              min={0.1}
              decimalScale={2}
              placeholder="5"
              disabled={disabled}
            />
            <NumberInput
              label={t("setPageBoxes.cropMarks.offset", "Offset (mm)")}
              value={parameters.cropMarkOffsetMm}
              onChange={(v) =>
                onParameterChange("cropMarkOffsetMm", numberOrUndefined(v))
              }
              min={0}
              decimalScale={2}
              placeholder="3"
              disabled={disabled}
            />
            <NumberInput
              label={t("setPageBoxes.cropMarks.weight", "Stroke (pt)")}
              value={parameters.cropMarkWeightPt}
              onChange={(v) =>
                onParameterChange("cropMarkWeightPt", numberOrUndefined(v))
              }
              min={0.1}
              decimalScale={2}
              placeholder="0.25"
              disabled={disabled}
            />
          </SimpleGrid>
        )}
      </Stack>
    </Stack>
  );
};

export default SetPageBoxesFields;
