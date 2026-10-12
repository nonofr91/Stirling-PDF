import {
  Checkbox,
  Fieldset,
  NumberInput,
  Select,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
} from "@mantine/core";
import { useTranslation } from "react-i18next";
import {
  EyeletMarksParameters,
  defaultParameters,
} from "@app/hooks/tools/eyeletMarks/useEyeletMarksParameters";
import { EyeletMarkMode } from "@app/utils/eyeletMarkGeometry";

export interface EyeletMarksFieldsProps {
  parameters: EyeletMarksParameters;
  onParameterChange: <K extends keyof EyeletMarksParameters>(
    key: K,
    value: EyeletMarksParameters[K],
  ) => void;
  disabled?: boolean;
}

type SideKey = "top" | "right" | "bottom" | "left";

const SIDES: SideKey[] = ["top", "right", "bottom", "left"];

const COUNT_KEYS: Record<SideKey, keyof EyeletMarksParameters> = {
  top: "countTop",
  right: "countRight",
  bottom: "countBottom",
  left: "countLeft",
};
const SPACING_KEYS: Record<SideKey, keyof EyeletMarksParameters> = {
  top: "maxSpacingTopMm",
  right: "maxSpacingRightMm",
  bottom: "maxSpacingBottomMm",
  left: "maxSpacingLeftMm",
};
const OFFSET_KEYS: Record<SideKey, keyof EyeletMarksParameters> = {
  top: "offsetTopMm",
  right: "offsetRightMm",
  bottom: "offsetBottomMm",
  left: "offsetLeftMm",
};

const numberOrUndefined = (v: string | number) =>
  typeof v === "number" ? v : undefined;

const sideEnabled = (
  parameters: EyeletMarksParameters,
  side: SideKey,
): boolean => {
  // Cleared fields are omitted from the request and rebound to backend
  // defaults — a cleared spacing means "500 mm, enabled" while a cleared
  // count means "0, disabled" — so the checkbox mirrors the same fallback.
  if (parameters.mode === "COUNT") {
    return ((parameters[COUNT_KEYS[side]] as number | undefined) ?? 0) > 0;
  }
  const key = SPACING_KEYS[side];
  return (
    ((parameters[key] as number | undefined) ??
      (defaultParameters[key] as number | undefined) ??
      0) > 0
  );
};

/**
 * The pure parameter inputs, shared by the tool's settings panel (which adds a
 * live preview of the marks) and the automation variant (no file).
 */
const EyeletMarksFields = ({
  parameters,
  onParameterChange,
  disabled = false,
}: EyeletMarksFieldsProps) => {
  const { t } = useTranslation();

  const toggleSide = (side: SideKey, enabled: boolean) => {
    const key =
      parameters.mode === "COUNT" ? COUNT_KEYS[side] : SPACING_KEYS[side];
    if (!enabled) {
      onParameterChange(key, 0);
      return;
    }
    const prev = parameters[key] as number | undefined;
    if (parameters.mode === "COUNT") {
      // Re-enabling a side left at 0 restores the smallest valid count (1 is
      // rejected by the backend: it cannot honour both end marks).
      onParameterChange(key, prev && prev >= 2 ? prev : 2);
    } else {
      onParameterChange(
        key,
        prev && prev > 0 ? prev : (defaultParameters[key] as number),
      );
    }
  };

  return (
    <Stack gap="md">
      <Text size="sm" c="dimmed">
        {t(
          "eyeletMarks.help",
          "Draw a cross at each eyelet (grommet) position along the trim edges. Corners always carry a mark; spacing per side is either a fixed count or a maximum gap.",
        )}
      </Text>

      <Select
        label={t("eyeletMarks.mode.label", "Placement mode")}
        data={[
          {
            value: "MAX_SPACING",
            label: t(
              "eyeletMarks.mode.maxSpacing",
              "Maximum spacing between marks",
            ),
          },
          {
            value: "COUNT",
            label: t("eyeletMarks.mode.count", "Fixed mark count per side"),
          },
        ]}
        value={parameters.mode}
        onChange={(v) =>
          onParameterChange("mode", (v ?? "MAX_SPACING") as EyeletMarkMode)
        }
        allowDeselect={false}
        disabled={disabled}
      />

      <SimpleGrid cols={2}>
        <NumberInput
          label={t("eyeletMarks.diameterMm.label", "Eyelet diameter (mm)")}
          description={t(
            "eyeletMarks.diameterMm.desc",
            "The cross span matches the eyelet diameter",
          )}
          value={parameters.eyeletDiameterMm}
          onChange={(v) =>
            onParameterChange("eyeletDiameterMm", numberOrUndefined(v))
          }
          min={0.5}
          max={100}
          decimalScale={2}
          placeholder={String(defaultParameters.eyeletDiameterMm)}
          disabled={disabled}
        />
        <NumberInput
          label={t("eyeletMarks.strokeWidthPt.label", "Stroke width (pt)")}
          value={parameters.strokeWidthPt}
          onChange={(v) =>
            onParameterChange("strokeWidthPt", numberOrUndefined(v))
          }
          min={0.1}
          decimalScale={2}
          placeholder={String(defaultParameters.strokeWidthPt)}
          disabled={disabled}
        />
      </SimpleGrid>

      <SimpleGrid cols={{ base: 1, sm: 2 }}>
        {SIDES.map((side) => {
          const enabled = sideEnabled(parameters, side);
          const distKey =
            parameters.mode === "COUNT" ? COUNT_KEYS[side] : SPACING_KEYS[side];
          return (
            <Fieldset
              key={side}
              legend={
                <Checkbox
                  label={t(`eyeletMarks.sides.${side}`, side)}
                  checked={enabled}
                  onChange={(e) => toggleSide(side, e.currentTarget.checked)}
                  disabled={disabled}
                />
              }
              disabled={disabled}
            >
              <Stack gap="xs">
                <NumberInput
                  label={t(
                    "eyeletMarks.offsetMm.label",
                    "Distance from trim (mm)",
                  )}
                  description={t(
                    "eyeletMarks.offsetMm.desc",
                    "Positive inside the trim, negative outside",
                  )}
                  value={parameters[OFFSET_KEYS[side]]}
                  onChange={(v) =>
                    onParameterChange(OFFSET_KEYS[side], numberOrUndefined(v))
                  }
                  min={-200}
                  max={200}
                  decimalScale={2}
                  placeholder={String(defaultParameters.offsetTopMm)}
                  disabled={disabled || !enabled}
                />
                {parameters.mode === "COUNT" ? (
                  <NumberInput
                    label={t("eyeletMarks.count.label", "Marks on this side")}
                    description={t(
                      "eyeletMarks.count.desc",
                      "Equidistant, both ends included",
                    )}
                    value={parameters[distKey]}
                    onChange={(v) =>
                      onParameterChange(distKey, numberOrUndefined(v))
                    }
                    min={2}
                    max={500}
                    allowDecimal={false}
                    placeholder="2"
                    disabled={disabled || !enabled}
                  />
                ) : (
                  <NumberInput
                    label={t(
                      "eyeletMarks.spacingMm.label",
                      "Max gap between marks (mm)",
                    )}
                    value={parameters[distKey]}
                    onChange={(v) =>
                      onParameterChange(distKey, numberOrUndefined(v))
                    }
                    min={1}
                    max={20000}
                    decimalScale={2}
                    placeholder={String(defaultParameters.maxSpacingTopMm)}
                    disabled={disabled || !enabled}
                  />
                )}
              </Stack>
            </Fieldset>
          );
        })}
      </SimpleGrid>

      <TextInput
        label={t("eyeletMarks.layerName.label", "Layer name (optional)")}
        description={t(
          "eyeletMarks.layerName.desc",
          "Group the marks under an optional-content layer; blank draws plain content",
        )}
        value={parameters.layerName}
        onChange={(e) => onParameterChange("layerName", e.currentTarget.value)}
        placeholder="EyeletMarks"
        disabled={disabled}
      />
    </Stack>
  );
};

export default EyeletMarksFields;
