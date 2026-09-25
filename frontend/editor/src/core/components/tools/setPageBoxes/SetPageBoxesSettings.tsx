import { useEffect, useState } from "react";
import { Stack, TextInput, NumberInput, Checkbox, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import {
  SetPageBoxesParameters,
  parseBoxString,
} from "@app/hooks/tools/setPageBoxes/useSetPageBoxesParameters";
import {
  useViewScopedFiles,
  useViewScopedFileStubs,
} from "@app/hooks/tools/shared/useViewScopedFiles";
import PageBoxDiagram from "@app/components/tools/shared/PageBoxDiagram";
import {
  computeResultingBoxes,
  readPageBoxSnapshot,
  PageBoxSnapshot,
} from "@app/utils/pageBoxReader";
import { PAGE_BOXES } from "@app/constants/pageBoxConstants";

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
  const [selectedFile = null] = useViewScopedFiles();
  const [selectedStub = null] = useViewScopedFileStubs();
  const [snapshot, setSnapshot] = useState<PageBoxSnapshot | null>(null);

  useEffect(() => {
    let cancelled = false;
    if (!selectedFile) {
      setSnapshot(null);
      return;
    }
    readPageBoxSnapshot(selectedFile).then((s) => {
      if (!cancelled) setSnapshot(s);
    });
    return () => {
      cancelled = true;
    };
  }, [selectedFile]);

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

      <Checkbox
        label={t(
          "setPageBoxes.drawBoxes",
          "Draw the page boxes on a document layer (proof overlay)",
        )}
        checked={parameters.drawBoxes}
        onChange={(e) =>
          onParameterChange("drawBoxes", e.currentTarget.checked)
        }
        disabled={disabled}
      />

      {snapshot && (
        <Stack gap={4}>
          <Text size="sm" fw={500}>
            {t("setPageBoxes.preview", "Resulting page boxes")}
          </Text>
          <PageBoxDiagram
            mediaBox={
              computeResultingBoxes(parameters, snapshot, parseBoxString)
                .MEDIA_BOX.rect
            }
            background={
              selectedStub?.thumbnailUrl && snapshot.rotation % 360 === 0
                ? {
                    src: selectedStub.thumbnailUrl,
                    rect: snapshot.boxes.CROP_BOX,
                  }
                : undefined
            }
            boxes={(() => {
              const result = computeResultingBoxes(
                parameters,
                snapshot,
                parseBoxString,
              );
              return PAGE_BOXES.map((name) => ({
                name,
                rect: result[name].rect,
                inherited: result[name].inherited,
              }));
            })()}
          />
          <Text size="xs" c="dimmed">
            {t(
              "setPageBoxes.inheritedHint",
              "* box absent from the page — shown value is inherited",
            )}
          </Text>
        </Stack>
      )}
    </Stack>
  );
};

export default SetPageBoxesSettings;
