import { useEffect, useRef, useState } from "react";
import { Checkbox, Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import EyeletMarksFields from "@app/components/tools/eyeletMarks/EyeletMarksFields";
import {
  EyeletMarksParameters,
  defaultParameters,
} from "@app/hooks/tools/eyeletMarks/useEyeletMarksParameters";
import { useViewScopedFiles } from "@app/hooks/tools/shared/useViewScopedFiles";
import {
  readPageBoxSnapshots,
  pdfRectToPageFractions,
  PageBoxSnapshot,
} from "@app/utils/pageBoxReader";
import {
  computeEyeletMarks,
  EyeletSide,
  EyeletSideGeometry,
} from "@app/utils/eyeletMarkGeometry";
import {
  PageOverlayPath,
  useSetPageOverlay,
} from "@app/contexts/PageOverlayContext";
import { getFormFillFileId } from "@app/types/fileContext";

interface EyeletMarksSettingsProps {
  parameters: EyeletMarksParameters;
  onParameterChange: <K extends keyof EyeletMarksParameters>(
    key: K,
    value: EyeletMarksParameters[K],
  ) => void;
  disabled?: boolean;
}

const MARK_COLOR = "var(--mantine-color-orange-6)";
const MM_TO_PT = 72 / 25.4;
/** Thin-rect arm under this width vanishes on large-format pages in preview. */
const MIN_ARM_PT = 1.5;

const rectPath = (
  x: number,
  y: number,
  w: number,
  h: number,
  crop: { x: number; y: number; width: number; height: number },
): PageOverlayPath => {
  const f = pdfRectToPageFractions({ x, y, width: w, height: h }, crop);
  return {
    points: [
      f.x,
      f.y,
      f.x + f.width,
      f.y,
      f.x + f.width,
      f.y + f.height,
      f.x,
      f.y + f.height,
    ],
    color: MARK_COLOR,
  };
};

const ptFrac = (
  x: number,
  y: number,
  crop: { x: number; y: number; width: number; height: number },
): [number, number] => [
  (x - crop.x) / crop.width,
  (crop.y + crop.height - y) / crop.height,
];

/** A thin rectangle rotated to the unit direction (ux, uy) → overlay polygon. */
const armPath = (
  cx: number,
  cy: number,
  ux: number,
  uy: number,
  radiusPt: number,
  armPt: number,
  crop: { x: number; y: number; width: number; height: number },
): PageOverlayPath => {
  const px = -uy;
  const py = ux;
  const hw = armPt / 2;
  const corners = [
    [cx + ux * radiusPt + px * hw, cy + uy * radiusPt + py * hw],
    [cx - ux * radiusPt + px * hw, cy - uy * radiusPt + py * hw],
    [cx - ux * radiusPt - px * hw, cy - uy * radiusPt - py * hw],
    [cx + ux * radiusPt - px * hw, cy + uy * radiusPt - py * hw],
  ];
  return {
    points: corners.flatMap(([x, y]) => ptFrac(x, y, crop)),
    color: MARK_COLOR,
  };
};

/**
 * The mark as two thin rectangles in PDF space → overlay polygons. Corner marks
 * (two enabled sides meeting) are rotated 45° so they read differently, like
 * the diagonal crosses the backend paints. Marks with a negative offset land
 * outside the rendered page: the overlay viewport clips them, while the
 * backend grows MediaBox/CropBox to fit the full cross.
 */
const markPaths = (
  cx: number,
  cy: number,
  radiusPt: number,
  armPt: number,
  crop: { x: number; y: number; width: number; height: number },
  corner: boolean,
): PageOverlayPath[] =>
  corner
    ? [
        armPath(cx, cy, Math.SQRT1_2, Math.SQRT1_2, radiusPt, armPt, crop),
        armPath(cx, cy, Math.SQRT1_2, -Math.SQRT1_2, radiusPt, armPt, crop),
      ]
    : [
        rectPath(cx - radiusPt, cy - armPt / 2, 2 * radiusPt, armPt, crop),
        rectPath(cx - armPt / 2, cy - radiusPt, armPt, 2 * radiusPt, crop),
      ];

const EyeletMarksSettings = ({
  parameters,
  onParameterChange,
  disabled = false,
}: EyeletMarksSettingsProps) => {
  const { t } = useTranslation();
  const [selectedFile = null] = useViewScopedFiles();
  const [previewEnabled, setPreviewEnabled] = useState(true);
  const [markCount, setMarkCount] = useState<number | null>(null);
  const setOverlay = useSetPageOverlay();
  // Single-entry cache: only the current document's snapshots are reusable.
  const snapshotsRef = useRef<{
    key: string;
    value: (PageBoxSnapshot | null)[];
  } | null>(null);

  useEffect(() => {
    const documentKey = selectedFile ? getFormFillFileId(selectedFile) : null;
    if (!documentKey || !selectedFile || !previewEnabled || disabled) {
      setOverlay(null);
      setMarkCount(null);
      return;
    }

    let cancelled = false;
    void (async () => {
      let snapshots =
        snapshotsRef.current?.key === documentKey
          ? snapshotsRef.current.value
          : null;
      if (!snapshots) {
        const read = await readPageBoxSnapshots(selectedFile);
        if (cancelled) return;
        if (!read) {
          setOverlay(null);
          setMarkCount(null);
          return;
        }
        snapshots = read;
        snapshotsRef.current = { key: documentKey, value: snapshots };
      }

      // Cleared fields are omitted from the request, so the backend applies
      // its own defaults (15mm offset, 500mm spacing, count disabled) — the
      // preview must fall back to the same values to stay faithful.
      const sides: Record<EyeletSide, EyeletSideGeometry> = {
        top: {
          offsetMm: parameters.offsetTopMm ?? defaultParameters.offsetTopMm!,
          count: parameters.countTop ?? 0,
          maxSpacingMm:
            parameters.maxSpacingTopMm ?? defaultParameters.maxSpacingTopMm!,
        },
        right: {
          offsetMm: parameters.offsetRightMm ?? defaultParameters.offsetTopMm!,
          count: parameters.countRight ?? 0,
          maxSpacingMm:
            parameters.maxSpacingRightMm ?? defaultParameters.maxSpacingTopMm!,
        },
        bottom: {
          offsetMm: parameters.offsetBottomMm ?? defaultParameters.offsetTopMm!,
          count: parameters.countBottom ?? 0,
          maxSpacingMm:
            parameters.maxSpacingBottomMm ?? defaultParameters.maxSpacingTopMm!,
        },
        left: {
          offsetMm: parameters.offsetLeftMm ?? defaultParameters.offsetTopMm!,
          count: parameters.countLeft ?? 0,
          maxSpacingMm:
            parameters.maxSpacingLeftMm ?? defaultParameters.maxSpacingTopMm!,
        },
      };

      const radiusPt = ((parameters.eyeletDiameterMm ?? 10) / 2) * MM_TO_PT;
      const armPt = Math.max(parameters.strokeWidthPt ?? 0.5, MIN_ARM_PT);

      let total = 0;
      const pathsPerPage: PageOverlayPath[][] = snapshots.map((snapshot) => {
        if (!snapshot) return [];
        const marks = computeEyeletMarks(
          parameters.mode,
          snapshot.boxes.TRIM_BOX,
          sides,
        );
        total += marks.length;
        return marks.flatMap((m) =>
          markPaths(
            m.x,
            m.y,
            radiusPt,
            armPt,
            snapshot.boxes.CROP_BOX,
            m.corner,
          ),
        );
      });
      if (cancelled) return;
      setOverlay({ documentKey, rects: [], pathsPerPage });
      setMarkCount(total);
    })();

    return () => {
      cancelled = true;
    };
  }, [selectedFile, parameters, previewEnabled, disabled, setOverlay]);

  useEffect(() => () => setOverlay(null), [setOverlay]);

  return (
    <Stack gap="md">
      <EyeletMarksFields
        parameters={parameters}
        onParameterChange={onParameterChange}
        disabled={disabled}
      />

      {selectedFile && (
        <Stack gap={4}>
          <Checkbox
            label={t(
              "eyeletMarks.preview.toggle",
              "Preview eyelet marks on the document",
            )}
            checked={previewEnabled}
            onChange={(e) => setPreviewEnabled(e.currentTarget.checked)}
            disabled={disabled}
          />
          {previewEnabled && markCount !== null && (
            <Text size="xs" c="dimmed">
              {t("eyeletMarks.preview.count", "Marks drawn:")} {markCount}
            </Text>
          )}
        </Stack>
      )}
    </Stack>
  );
};

export default EyeletMarksSettings;
