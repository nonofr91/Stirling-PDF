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
  CutContourParameters,
  EXTRACTION_MODES,
  ExtractionMode,
} from "@app/hooks/tools/cutContour/useCutContourParameters";

export interface CutContourFieldsProps {
  parameters: CutContourParameters;
  onParameterChange: <K extends keyof CutContourParameters>(
    key: K,
    value: CutContourParameters[K],
  ) => void;
  disabled?: boolean;
}

const numberOrUndefined = (v: string | number) =>
  typeof v === "number" ? v : undefined;

/**
 * The pure parameter inputs, shared by the tool's settings panel (which adds a
 * live contour preview on the viewer) and the automation variant (no file).
 */
const CutContourFields = ({
  parameters,
  onParameterChange,
  disabled = false,
}: CutContourFieldsProps) => {
  const { t } = useTranslation();

  const mode = parameters.extractionMode;
  const showAlpha = mode === "AUTO" || mode === "ALPHA";
  const showBackground = mode === "AUTO" || mode === "BACKGROUND";
  const showAi = mode === "AUTO" || mode === "AI";

  return (
    <Stack gap="md">
      <Text size="sm" c="dimmed">
        {t(
          "cutContour.help",
          "Traces the silhouette of the artwork on each page and writes a closed cut path as a CutContour spot colour on a dedicated layer — ready for RIPs, plotters and die cutters.",
        )}
      </Text>

      <Select
        label={t("cutContour.extractionMode", "Subject detection")}
        data={EXTRACTION_MODES.map((m) => ({
          value: m,
          label: t(`cutContour.modes.${m}`, m),
        }))}
        value={mode}
        onChange={(v) =>
          onParameterChange("extractionMode", (v ?? "AUTO") as ExtractionMode)
        }
        allowDeselect={false}
        disabled={disabled}
      />

      <SimpleGrid cols={2}>
        {showAlpha && (
          <NumberInput
            label={t("cutContour.alphaThreshold", "Alpha threshold")}
            description={t(
              "cutContour.alphaThresholdHint",
              "Pixels more transparent than this count as background",
            )}
            value={parameters.alphaThreshold}
            onChange={(v) =>
              onParameterChange("alphaThreshold", numberOrUndefined(v))
            }
            min={0}
            max={255}
            placeholder="16"
            disabled={disabled}
          />
        )}
        {showBackground && (
          <NumberInput
            label={t("cutContour.backgroundTolerance", "Background tolerance")}
            description={t(
              "cutContour.backgroundToleranceHint",
              "Colour distance from the page edge still counted as background",
            )}
            value={parameters.backgroundTolerance}
            onChange={(v) =>
              onParameterChange("backgroundTolerance", numberOrUndefined(v))
            }
            min={0}
            max={255}
            placeholder="32"
            disabled={disabled}
          />
        )}
        {showAi && (
          <>
            <NumberInput
              label={t("cutContour.aiThreshold", "AI confidence")}
              value={parameters.aiThreshold}
              onChange={(v) =>
                onParameterChange("aiThreshold", numberOrUndefined(v))
              }
              min={0}
              max={1}
              step={0.05}
              decimalScale={2}
              placeholder="0.4"
              disabled={disabled}
            />
            <TextInput
              label={t("cutContour.aiModelId", "AI model id")}
              description={t(
                "cutContour.aiModelIdHint",
                "Catalog model for matting; empty uses the default (u2net)",
              )}
              value={parameters.aiModelId}
              onChange={(e) =>
                onParameterChange("aiModelId", e.currentTarget.value)
              }
              placeholder="u2net"
              disabled={disabled}
            />
          </>
        )}
      </SimpleGrid>

      <SimpleGrid cols={2}>
        <NumberInput
          label={t("cutContour.dpi", "Detection resolution (dpi)")}
          value={parameters.dpi}
          onChange={(v) => onParameterChange("dpi", numberOrUndefined(v))}
          min={72}
          max={600}
          placeholder="150"
          disabled={disabled}
        />
        <NumberInput
          label={t("cutContour.minAreaMm2", "Minimum area (mm²)")}
          description={t(
            "cutContour.minAreaMm2Hint",
            "Smaller disconnected parts are dropped as noise",
          )}
          value={parameters.minAreaMm2}
          onChange={(v) =>
            onParameterChange("minAreaMm2", numberOrUndefined(v))
          }
          min={0}
          decimalScale={2}
          placeholder="1"
          disabled={disabled}
        />
        <NumberInput
          label={t("cutContour.mergeGapMm", "Merge gaps up to (mm)")}
          description={t(
            "cutContour.mergeGapMmHint",
            "Elements closer than this merge under a single outline; 0 keeps pieces separate",
          )}
          value={parameters.mergeGapMm}
          onChange={(v) =>
            onParameterChange("mergeGapMm", numberOrUndefined(v))
          }
          min={0}
          decimalScale={1}
          placeholder="8"
          disabled={disabled}
        />
        <NumberInput
          label={t("cutContour.smoothness", "Smoothing (0–100)")}
          value={parameters.smoothness}
          onChange={(v) =>
            onParameterChange("smoothness", numberOrUndefined(v))
          }
          min={0}
          max={100}
          placeholder="20"
          disabled={disabled}
        />
        <NumberInput
          label={t("cutContour.offsetMm", "Cut line offset (mm)")}
          description={t(
            "cutContour.offsetMmHint",
            "Moves the cut path outward; negative moves it inside the silhouette",
          )}
          value={parameters.offsetMm}
          onChange={(v) => onParameterChange("offsetMm", numberOrUndefined(v))}
          decimalScale={2}
          placeholder="0"
          disabled={disabled}
        />
      </SimpleGrid>

      <Checkbox
        label={t(
          "cutContour.keepHoles",
          "Keep inner holes as cut contours (e.g. the counter of an 'o')",
        )}
        checked={parameters.keepHoles}
        onChange={(e) =>
          onParameterChange("keepHoles", e.currentTarget.checked)
        }
        disabled={disabled}
      />

      <Divider />

      <Stack gap="xs">
        <Text size="sm" fw={500}>
          {t("cutContour.cutLine.section", "Cut line")}
        </Text>
        <SimpleGrid cols={2}>
          <TextInput
            label={t("cutContour.spotName", "Spot colour name")}
            description={t(
              "cutContour.spotNameHint",
              "Case-sensitive; RIPs key on 'CutContour'",
            )}
            value={parameters.spotName}
            onChange={(e) =>
              onParameterChange("spotName", e.currentTarget.value)
            }
            placeholder="CutContour"
            error={
              parameters.spotName.trim() === ""
                ? t("cutContour.spotNameError", "Spot name is required")
                : undefined
            }
            disabled={disabled}
          />
          <NumberInput
            label={t("cutContour.strokeWidthPt", "Stroke width (pt)")}
            value={parameters.strokeWidthPt}
            onChange={(v) =>
              onParameterChange("strokeWidthPt", numberOrUndefined(v))
            }
            min={0.1}
            decimalScale={2}
            placeholder="0.25"
            disabled={disabled}
          />
        </SimpleGrid>
        <TextInput
          label={t("cutContour.layerName", "Layer name (optional)")}
          value={parameters.layerName}
          onChange={(e) =>
            onParameterChange("layerName", e.currentTarget.value)
          }
          placeholder={parameters.spotName || "CutContour"}
          disabled={disabled}
        />
        <Checkbox
          label={t(
            "cutContour.processingSteps",
            "Tag layer as ISO 19593-1 cutting step (non-printing)",
          )}
          checked={parameters.processingSteps}
          onChange={(e) =>
            onParameterChange("processingSteps", e.currentTarget.checked)
          }
          disabled={disabled}
        />
      </Stack>

      <Divider />

      <Stack gap="xs">
        <Text size="sm" fw={500}>
          {t("cutContour.output.section", "Output")}
        </Text>
        <Checkbox
          label={t(
            "cutContour.clipArtwork",
            "Clip page content to the cut contour",
          )}
          checked={parameters.clipArtwork}
          onChange={(e) =>
            onParameterChange("clipArtwork", e.currentTarget.checked)
          }
          disabled={disabled}
        />
        <NumberInput
          label={t("cutContour.bleedMm", "Bleed beyond cut line (mm)")}
          description={t(
            "cutContour.bleedMmHint",
            "Repeats edge pixels past the contour so trimming never shows a white edge",
          )}
          value={parameters.bleedMm}
          onChange={(v) => onParameterChange("bleedMm", numberOrUndefined(v))}
          min={0}
          max={50}
          decimalScale={2}
          placeholder="0"
          disabled={disabled}
        />
        <Checkbox
          label={t(
            "cutContour.trimToContour",
            "Set TrimBox to the contour bounding box",
          )}
          checked={parameters.trimToContour}
          onChange={(e) =>
            onParameterChange("trimToContour", e.currentTarget.checked)
          }
          disabled={disabled}
        />
      </Stack>
    </Stack>
  );
};

export default CutContourFields;
