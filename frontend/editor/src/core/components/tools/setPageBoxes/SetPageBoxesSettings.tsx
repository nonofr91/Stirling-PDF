import { useEffect, useState } from "react";
import { Stack, Text } from "@mantine/core";
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
import SetPageBoxesFields from "@app/components/tools/setPageBoxes/SetPageBoxesFields";
import {
  computeResultingBoxes,
  pdfRectToPageFractions,
  readPageBoxSnapshots,
  PageBoxSnapshot,
} from "@app/utils/pageBoxReader";
import { PAGE_BOXES, PAGE_BOX_COLORS } from "@app/constants/pageBoxConstants";
import { useSetPageOverlay } from "@app/contexts/PageOverlayContext";
import { getFormFillFileId } from "@app/types/fileContext";

interface SetPageBoxesSettingsProps {
  parameters: SetPageBoxesParameters;
  onParameterChange: <K extends keyof SetPageBoxesParameters>(
    key: K,
    value: SetPageBoxesParameters[K],
  ) => void;
  disabled?: boolean;
}

const SetPageBoxesSettings = ({
  parameters,
  onParameterChange,
  disabled = false,
}: SetPageBoxesSettingsProps) => {
  const { t } = useTranslation();
  const [selectedFile = null] = useViewScopedFiles();
  const [selectedStub = null] = useViewScopedFileStubs();
  const [snapshots, setSnapshots] = useState<(PageBoxSnapshot | null)[] | null>(
    null,
  );
  const setOverlay = useSetPageOverlay();
  // The diagram and placeholders show the first page; the published overlay
  // resolves every page against its own boxes.
  const snapshot = snapshots?.[0] ?? null;

  useEffect(() => {
    let cancelled = false;
    if (!selectedFile) {
      setSnapshots(null);
      return;
    }
    readPageBoxSnapshots(selectedFile).then((s) => {
      if (!cancelled) setSnapshots(s);
    });
    return () => {
      cancelled = true;
    };
  }, [selectedFile]);

  // Preview the resulting boxes on the viewer's pages, each page resolved
  // against its own CropBox (the file is not modified yet).
  useEffect(() => {
    const documentKey = selectedFile ? getFormFillFileId(selectedFile) : null;
    if (!documentKey || !snapshots) {
      setOverlay(null);
      return;
    }
    setOverlay({
      documentKey,
      rects: [],
      rectsPerPage: snapshots.map((pageSnapshot) => {
        if (!pageSnapshot) return [];
        const result = computeResultingBoxes(
          parameters,
          pageSnapshot,
          parseBoxString,
        );
        return PAGE_BOXES.map((name) => ({
          ...pdfRectToPageFractions(
            result[name].rect,
            pageSnapshot.boxes.CROP_BOX,
          ),
          color: PAGE_BOX_COLORS[name],
          dashed: result[name].inherited,
          label: name.replace("_BOX", ""),
          kind: "box",
        }));
      }),
    });
  }, [selectedFile, snapshots, parameters, setOverlay]);

  useEffect(() => () => setOverlay(null), [setOverlay]);

  // Each field's placeholder echoes the box the overlay currently draws, so an
  // empty input visibly means "keep that rect".
  const fmt = (r: { x: number; y: number; width: number; height: number }) =>
    [r.x, r.y, r.width, r.height].map((n) => +n.toFixed(2)).join(",");
  const boxPlaceholders = snapshot
    ? {
        mediaBox: fmt(snapshot.boxes.MEDIA_BOX),
        cropBox: fmt(snapshot.boxes.CROP_BOX),
        trimBox: fmt(snapshot.boxes.TRIM_BOX),
        bleedBox: fmt(snapshot.boxes.BLEED_BOX),
        artBox: fmt(snapshot.boxes.ART_BOX),
      }
    : undefined;

  return (
    <Stack gap="md">
      <SetPageBoxesFields
        parameters={parameters}
        onParameterChange={onParameterChange}
        disabled={disabled}
        boxPlaceholders={boxPlaceholders}
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
