import { useEffect, useRef, useState } from "react";
import { Checkbox, Group, Stack, Text } from "@mantine/core";
import { Button } from "@app/ui/Button";
import { useTranslation } from "react-i18next";
import apiClient from "@app/services/apiClient";
import CutContourFields from "@app/components/tools/cutContour/CutContourFields";
import { buildCutContourFormData } from "@app/hooks/tools/cutContour/useCutContourOperation";
import { CutContourParameters } from "@app/hooks/tools/cutContour/useCutContourParameters";
import { useViewScopedFiles } from "@app/hooks/tools/shared/useViewScopedFiles";
import { readPageBoxSnapshots, BoxRect } from "@app/utils/pageBoxReader";
import {
  PageOverlayPath,
  useSetPageOverlay,
} from "@app/contexts/PageOverlayContext";
import { getFormFillFileId } from "@app/types/fileContext";
import { extractErrorMessage } from "@app/utils/toolErrorHandler";

interface CutContourSettingsProps {
  parameters: CutContourParameters;
  onParameterChange: <K extends keyof CutContourParameters>(
    key: K,
    value: CutContourParameters[K],
  ) => void;
  disabled?: boolean;
}

interface PreviewPage {
  page: number;
  mode: string;
  space: [number, number, number, number];
  paths: number[][];
  holes: boolean[];
}

const CONTOUR_COLOR = "var(--mantine-color-pink-6)";

/** PDF user-space point → CSS-space fraction of the page region the viewer
 *  renders (the CropBox). `space` is the traced render rect, used when the
 *  page's boxes cannot be read. */
function toFractions(
  ring: number[],
  crop: BoxRect | null,
  space: [number, number, number, number],
): number[] {
  const box = crop ?? {
    x: space[0],
    y: space[1],
    width: space[2],
    height: space[3],
  };
  const out = new Array<number>(ring.length);
  for (let i = 0; i < ring.length; i += 2) {
    out[i] = (ring[i] - box.x) / box.width;
    out[i + 1] = (box.y + box.height - ring[i + 1]) / box.height;
  }
  return out;
}

const PREVIEW_DEBOUNCE_MS = 700;

const CutContourSettings = ({
  parameters,
  onParameterChange,
  disabled = false,
}: CutContourSettingsProps) => {
  const { t } = useTranslation();
  const [selectedFile = null] = useViewScopedFiles();
  const [previewEnabled, setPreviewEnabled] = useState(true);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [previewModes, setPreviewModes] = useState<string[] | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const setOverlay = useSetPageOverlay();
  const requestSeq = useRef(0);
  const [drawing, setDrawing] = useState(false);

  useEffect(() => {
    const documentKey = selectedFile ? getFormFillFileId(selectedFile) : null;
    if (!documentKey || !selectedFile) {
      setOverlay(null);
      setPreviewModes(null);
      setPreviewError(null);
      return;
    }

    if (drawing) {
      // While armed, the overlay captures a rough perimeter instead of previewing.
      setOverlay({
        documentKey,
        rects: [],
        drawRequest: {
          color: "var(--mantine-color-teal-6)",
          onComplete: (pageIndex, points) => {
            onParameterChange("roiPoints", points);
            onParameterChange("roiPage", pageIndex + 1);
            setDrawing(false);
          },
        },
      });
      return;
    }

    const roiPathsPerPage: PageOverlayPath[][] = [];
    if (parameters.roiPoints.length >= 6 && parameters.roiPage > 0) {
      roiPathsPerPage[parameters.roiPage - 1] = [
        {
          points: parameters.roiPoints,
          color: "var(--mantine-color-teal-6)",
          dashed: true,
        },
      ];
    }

    if (!previewEnabled || disabled) {
      setOverlay({
        documentKey,
        rects: [],
        pathsPerPage: roiPathsPerPage,
      });
      setPreviewModes(null);
      setPreviewError(null);
      return;
    }

    const seq = ++requestSeq.current;
    const timer = setTimeout(() => {
      setPreviewLoading(true);
      void (async () => {
        try {
          const [response, snapshots] = await Promise.all([
            apiClient.post(
              "/api/v1/general/cut-contour-preview",
              buildCutContourFormData(parameters, selectedFile),
            ),
            readPageBoxSnapshots(selectedFile),
          ]);
          if (seq !== requestSeq.current) return;
          const pages = (response.data?.pages ?? []) as PreviewPage[];
          const pathsPerPage = pages.map((p): PageOverlayPath[] =>
            p.paths.map((ring, i) => ({
              points: toFractions(
                ring,
                snapshots?.[p.page - 1]?.boxes.CROP_BOX ?? null,
                p.space,
              ),
              color: CONTOUR_COLOR,
              hole: p.holes[i] ?? false,
            })),
          );
          if (parameters.roiPage > 0 && parameters.roiPoints.length >= 6) {
            const list = pathsPerPage[parameters.roiPage - 1];
            if (list) {
              list.push({
                points: parameters.roiPoints,
                color: "var(--mantine-color-teal-6)",
                dashed: true,
              });
            }
          }
          setOverlay({ documentKey, rects: [], pathsPerPage });
          setPreviewModes(pages.map((p) => p.mode));
          setPreviewError(null);
        } catch (e) {
          if (seq !== requestSeq.current) return;
          setOverlay(null);
          setPreviewModes(null);
          setPreviewError(extractErrorMessage(e));
        } finally {
          if (seq === requestSeq.current) setPreviewLoading(false);
        }
      })();
    }, PREVIEW_DEBOUNCE_MS);

    return () => clearTimeout(timer);
  }, [
    selectedFile,
    parameters,
    previewEnabled,
    disabled,
    drawing,
    setOverlay,
    t,
  ]);

  useEffect(() => () => setOverlay(null), [setOverlay]);

  return (
    <Stack gap="md">
      <CutContourFields
        parameters={parameters}
        onParameterChange={onParameterChange}
        disabled={disabled}
      />

      {selectedFile && (
        <Stack gap={4}>
          <Group gap="xs">
            <Button
              size="sm"
              variant={drawing ? "secondary" : "tertiary"}
              accent={drawing ? "success" : "default"}
              disabled={disabled}
              onClick={() => setDrawing((d) => !d)}
            >
              {drawing
                ? t("cutContour.roi.drawing", "Draw the perimeter…")
                : t("cutContour.roi.draw", "Draw perimeter")}
            </Button>
            {parameters.roiPoints.length >= 6 && (
              <Button
                size="sm"
                variant="quiet"
                accent="danger"
                disabled={disabled}
                onClick={() => {
                  onParameterChange("roiPoints", []);
                  onParameterChange("roiPage", 0);
                }}
              >
                {t("cutContour.roi.clear", "Clear")}
              </Button>
            )}
          </Group>
          {parameters.roiPoints.length >= 6 && (
            <Text size="xs" c="dimmed">
              {t(
                "cutContour.roi.active",
                "Perimeter set — the ring inside it defines the background colour",
              )}
            </Text>
          )}
          <Checkbox
            label={t(
              "cutContour.preview.toggle",
              "Preview cut contour on the document",
            )}
            checked={previewEnabled}
            onChange={(e) => setPreviewEnabled(e.currentTarget.checked)}
            disabled={disabled}
          />
          {previewEnabled && previewLoading && (
            <Text size="xs" c="dimmed">
              {t("cutContour.preview.loading", "Tracing contour…")}
            </Text>
          )}
          {previewEnabled && previewModes && !previewLoading && (
            <Text size="xs" c="dimmed">
              {t("cutContour.preview.mode", "Detection used:")}{" "}
              {previewModes
                .map((m, i) =>
                  previewModes.length > 1 ? `p${i + 1}: ${m}` : m,
                )
                .join(", ")}
            </Text>
          )}
          {previewEnabled && previewError && !previewLoading && (
            <Text size="xs" c="red">
              {previewError}
            </Text>
          )}
        </Stack>
      )}
    </Stack>
  );
};

export default CutContourSettings;
