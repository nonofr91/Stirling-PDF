import { useMemo } from "react";
import { Stack, Text, Group, Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { PageBox } from "@app/constants/pageBoxConstants";
import { BoxRect } from "@app/utils/pageBoxReader";

export interface DiagramBox {
  name: PageBox;
  rect: BoxRect;
  /** Drawn with a dashed stroke: box absent from the page (inherited value). */
  inherited?: boolean;
}

interface PageBoxDiagramProps {
  /** Canvas all boxes are scaled against — the page's MediaBox. */
  mediaBox: BoxRect;
  boxes: DiagramBox[];
  highlight?: PageBox;
  height?: number;
  /** Rendered page image drawn at the given PDF-space rect — pass the
      effective CropBox rect, which is what viewers render. */
  background?: { src: string; rect: BoxRect };
}

const BOX_COLORS: Record<PageBox, string> = {
  MEDIA_BOX: "var(--mantine-color-gray-6)",
  CROP_BOX: "var(--mantine-color-blue-6)",
  TRIM_BOX: "var(--mantine-color-green-6)",
  BLEED_BOX: "var(--mantine-color-red-6)",
  ART_BOX: "var(--mantine-color-violet-6)",
};

const PageBoxDiagram = ({
  mediaBox,
  boxes,
  highlight,
  height = 180,
  background,
}: PageBoxDiagramProps) => {
  const { t } = useTranslation();

  // Largest first so nested boxes stay visible on top.
  const ordered = useMemo(
    () =>
      [...boxes].sort(
        (a, b) => b.rect.width * b.rect.height - a.rect.width * a.rect.height,
      ),
    [boxes],
  );

  if (mediaBox.width <= 0 || mediaBox.height <= 0) return null;

  // Bleed/art boxes may extend beyond the MediaBox: canvas = union of all
  // rects, padded, so nothing is clipped at the viewport edge.
  const union = boxes.reduce(
    (acc, { rect }) => ({
      x: Math.min(acc.x, rect.x),
      y: Math.min(acc.y, rect.y),
      x2: Math.max(acc.x2, rect.x + rect.width),
      y2: Math.max(acc.y2, rect.y + rect.height),
    }),
    {
      x: mediaBox.x,
      y: mediaBox.y,
      x2: mediaBox.x + mediaBox.width,
      y2: mediaBox.y + mediaBox.height,
    },
  );
  const cw = union.x2 - union.x;
  const ch = union.y2 - union.y;
  const pad = 0.06 * Math.max(cw, ch);
  const scale = height / (ch + 2 * pad);
  const width = (cw + 2 * pad) * scale;

  // PDF origin is bottom-left; SVG origin is top-left.
  const toSvg = (r: BoxRect) => ({
    x: (r.x - union.x + pad) * scale,
    y: (union.y2 + pad - r.y - r.height) * scale,
    width: r.width * scale,
    height: r.height * scale,
  });

  const formatRect = (r: BoxRect) =>
    `${r.x.toFixed(1)},${r.y.toFixed(1)} ${r.width.toFixed(1)}×${r.height.toFixed(1)} pt`;

  return (
    <Stack gap="xs">
      <Box
        component="div"
        style={{ display: "flex", justifyContent: "center" }}
      >
        <svg
          width={width}
          height={height}
          role="img"
          aria-label={t("pageBox.diagram", "Page box diagram")}
        >
          {/* Page background = MediaBox */}
          <rect
            {...toSvg(mediaBox)}
            fill="var(--mantine-color-white)"
            stroke="var(--mantine-color-gray-5)"
          />
          {background && (
            <image
              href={background.src}
              {...toSvg(background.rect)}
              preserveAspectRatio="none"
            />
          )}
          {ordered.map(({ name, rect, inherited }) => {
            const isHighlight = name === highlight;
            return (
              <rect
                key={name}
                {...toSvg(rect)}
                fill="none"
                stroke={BOX_COLORS[name]}
                strokeWidth={isHighlight ? 2.5 : 1.2}
                strokeDasharray={
                  name === "MEDIA_BOX" ? undefined : inherited ? "3 3" : "5 2"
                }
              />
            );
          })}
        </svg>
      </Box>
      <Group gap="xs" justify="center" wrap="wrap">
        {boxes.map(({ name, rect, inherited }) => (
          <Text
            key={name}
            size="xs"
            c={name === highlight ? undefined : "dimmed"}
            fw={name === highlight ? 600 : 400}
            title={formatRect(rect)}
          >
            <span style={{ color: BOX_COLORS[name] }}>■</span>{" "}
            {name.replace("_BOX", "")}
            {inherited ? "*" : ""}
          </Text>
        ))}
      </Group>
    </Stack>
  );
};

export default PageBoxDiagram;
