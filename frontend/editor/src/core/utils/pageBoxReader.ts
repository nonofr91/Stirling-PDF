import { PageBox } from "@app/constants/pageBoxConstants";
import type { PdfPageBoxes } from "@embedpdf/models";
import {
  getPdfiumModule,
  openRawDocumentSafe,
  closeDocAndFreeBuffer,
} from "@app/services/pdfiumService";

export interface BoxRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface PageBoxSnapshot {
  /** Effective rects — a named box absent from the page resolves to the
      MediaBox, matching PageBoxUtils.resolvePageBox on the backend (pdf-lib
      getters fall back to CropBox instead, which previewed the wrong crop). */
  boxes: Record<PageBox, BoxRect>;
  /** Boxes explicitly present in the page dictionary. */
  explicit: Set<PageBox>;
  /** Page rotation in degrees — box rects are in unrotated space, so
      thumbnail overlays are only aligned when this is 0. */
  rotation: number;
}

const NAMED_BOXES = ["BLEED_BOX", "TRIM_BOX", "ART_BOX"] as const;

type NamedBoxRects = Partial<Record<(typeof NAMED_BOXES)[number], BoxRect>>;

const toRect = (b: {
  left: number;
  top: number;
  right: number;
  bottom: number;
}): BoxRect => ({
  x: Math.min(b.left, b.right),
  y: Math.min(b.top, b.bottom),
  width: Math.abs(b.right - b.left),
  height: Math.abs(b.top - b.bottom),
});

function snapshotFromRects(
  media: BoxRect,
  crop: BoxRect,
  named: NamedBoxRects,
  rotation: number,
): PageBoxSnapshot {
  // PDFium reports CropBox already resolved (media when absent), so explicit
  // presence is only known for the named boxes; media/crop are always marked.
  const explicit = new Set<PageBox>(["MEDIA_BOX", "CROP_BOX"]);
  const boxes: Record<PageBox, BoxRect> = {
    MEDIA_BOX: media,
    CROP_BOX: crop,
    BLEED_BOX: media,
    TRIM_BOX: media,
    ART_BOX: media,
  };
  for (const name of NAMED_BOXES) {
    const rect = named[name];
    if (rect) {
      boxes[name] = rect;
      explicit.add(name);
    }
  }
  return { boxes, explicit, rotation };
}

/**
 * Builds a snapshot from the boxes the EmbedPDF viewer already resolved for a
 * page (`documentState.document.pages[i].boxes`) — no second parse of the file.
 * `rotation` is the page's Rotation enum (quarters of a turn), as the engine
 * reports it.
 */
export function snapshotFromEmbedPdfPage(
  boxes: PdfPageBoxes | undefined,
  rotation = 0,
): PageBoxSnapshot | null {
  if (!boxes?.media) {
    return null;
  }
  const media = toRect(boxes.media);
  const crop = boxes.crop ? toRect(boxes.crop) : media;
  return snapshotFromRects(
    media,
    crop,
    {
      BLEED_BOX: boxes.bleed && toRect(boxes.bleed),
      TRIM_BOX: boxes.trim && toRect(boxes.trim),
      ART_BOX: boxes.art && toRect(boxes.art),
    },
    rotation * 90,
  );
}

// EPDF_GetPageBoxByIndex slot order — mirrors the engine's readPageBoxes.
const PDFIUM_BOX_SLOTS: Record<PageBox, number> = {
  MEDIA_BOX: 0,
  CROP_BOX: 1,
  BLEED_BOX: 2,
  TRIM_BOX: 3,
  ART_BOX: 4,
};

/**
 * Box snapshots of every page, read through the shared PDFium engine. The
 * previous reader ran a second full pdf-lib parse of the file and kept the
 * document cached — on a large PDF that duplicated the viewer's memory. Page
 * boxes come straight from the page dictionaries here, without loading pages.
 * Entries are null where PDFium cannot resolve a page's boxes; the whole
 * result is null when the file cannot be opened at all.
 */
export async function readPageBoxSnapshots(
  file: Blob,
): Promise<(PageBoxSnapshot | null)[] | null> {
  try {
    const m = await getPdfiumModule();
    const docPtr = await openRawDocumentSafe(await file.arrayBuffer());
    const buf = m.pdfium.wasmExports.malloc(16);
    try {
      const readBox = (pageIndex: number, slot: number): BoxRect | null => {
        if (!m.EPDF_GetPageBoxByIndex(docPtr, pageIndex, slot, buf)) {
          return null;
        }
        const left = m.pdfium.getValue(buf, "float");
        const top = m.pdfium.getValue(buf + 4, "float");
        const right = m.pdfium.getValue(buf + 8, "float");
        const bottom = m.pdfium.getValue(buf + 12, "float");
        const width = Math.abs(right - left);
        const height = Math.abs(top - bottom);
        if (width < 0.01 || height < 0.01) {
          return null;
        }
        return {
          x: Math.min(left, right),
          y: Math.min(top, bottom),
          width,
          height,
        };
      };

      const snapshots: (PageBoxSnapshot | null)[] = [];
      const pageCount = m.FPDF_GetPageCount(docPtr);
      for (let i = 0; i < pageCount; i++) {
        const media = readBox(i, PDFIUM_BOX_SLOTS.MEDIA_BOX);
        const crop = readBox(i, PDFIUM_BOX_SLOTS.CROP_BOX) ?? media;
        if (!media || !crop) {
          snapshots.push(null);
          continue;
        }
        const rotation =
          (m.EPDF_GetPageRotationByIndex?.(docPtr, i) ?? 0) * 90;
        snapshots.push(
          snapshotFromRects(
            media,
            crop,
            {
              BLEED_BOX: readBox(i, PDFIUM_BOX_SLOTS.BLEED_BOX) ?? undefined,
              TRIM_BOX: readBox(i, PDFIUM_BOX_SLOTS.TRIM_BOX) ?? undefined,
              ART_BOX: readBox(i, PDFIUM_BOX_SLOTS.ART_BOX) ?? undefined,
            },
            rotation,
          ),
        );
      }
      return snapshots;
    } finally {
      m.pdfium.wasmExports.free(buf);
      closeDocAndFreeBuffer(m, docPtr);
    }
  } catch {
    return null;
  }
}

/**
 * Maps a PDF-space rect (bottom-left origin) onto the region a viewer renders —
 * `visibleBox` is the page's effective CropBox — as 0–1 fractions in CSS space
 * (top-left origin). The viewer page container applies the page rotation to its
 * whole subtree, so fractions must be computed in unrotated space.
 */
export function pdfRectToPageFractions(
  rect: BoxRect,
  visibleBox: BoxRect,
): BoxRect {
  return {
    x: (rect.x - visibleBox.x) / visibleBox.width,
    y:
      (visibleBox.y + visibleBox.height - rect.y - rect.height) /
      visibleBox.height,
    width: rect.width / visibleBox.width,
    height: rect.height / visibleBox.height,
  };
}

const MM_TO_PT = 72 / 25.4;

const inset = (r: BoxRect, mm: number): BoxRect => {
  const d = mm * MM_TO_PT;
  return {
    x: r.x + d,
    y: r.y + d,
    width: Math.max(0, r.width - 2 * d),
    height: Math.max(0, r.height - 2 * d),
  };
};

const expand = (r: BoxRect, mm: number): BoxRect => {
  const d = mm * MM_TO_PT;
  return {
    x: r.x - d,
    y: r.y - d,
    width: r.width + 2 * d,
    height: r.height + 2 * d,
  };
};

const expandSides = (
  r: BoxRect,
  leftMm: number,
  rightMm: number,
  bottomMm: number,
  topMm: number,
): BoxRect => {
  const l = leftMm * MM_TO_PT;
  const b = bottomMm * MM_TO_PT;
  return {
    x: r.x - l,
    y: r.y - b,
    width: r.width + l + rightMm * MM_TO_PT,
    height: r.height + b + topMm * MM_TO_PT,
  };
};

const union = (a: BoxRect, b: BoxRect): BoxRect => {
  const x = Math.min(a.x, b.x);
  const y = Math.min(a.y, b.y);
  return {
    x,
    y,
    width: Math.max(a.x + a.width, b.x + b.width) - x,
    height: Math.max(a.y + a.height, b.y + b.height) - y,
  };
};

export interface ResultingBox {
  rect: BoxRect;
  /** Still absent from the page after apply (shows an inherited value). */
  inherited: boolean;
}

/**
 * Mirrors SetPageBoxesController.applyBoxes: explicit param > margin convenience >
 * existing page entry > copyMissingFromMediaBox fill. With generateBleed or
 * addCropMarks the MediaBox/CropBox grow to cover the generated content.
 */
export function computeResultingBoxes(
  params: {
    mediaBox: string;
    cropBox: string;
    trimBox: string;
    bleedBox: string;
    artBox: string;
    trimMarginMm?: number;
    bleedMm?: number;
    copyMissingFromMediaBox: boolean;
    generateBleed?: boolean;
    bleedTopMm?: number;
    bleedRightMm?: number;
    bleedBottomMm?: number;
    bleedLeftMm?: number;
    addCropMarks?: boolean;
    cropMarkLengthMm?: number;
    cropMarkOffsetMm?: number;
  },
  snapshot: PageBoxSnapshot,
  parseBox: (value: string) => number[] | null,
): Record<PageBox, ResultingBox> {
  const parse = (v: string): BoxRect | null => {
    const n = parseBox(v);
    return n ? { x: n[0], y: n[1], width: n[2], height: n[3] } : null;
  };

  const result = {} as Record<PageBox, ResultingBox>;
  const setFrom = (box: PageBox, rect: BoxRect | null) => {
    result[box] = rect
      ? { rect, inherited: false }
      : { rect: snapshot.boxes[box], inherited: !snapshot.explicit.has(box) };
  };

  setFrom("MEDIA_BOX", parse(params.mediaBox));
  const effectiveMedia = result.MEDIA_BOX.rect;

  let trimRect = parse(params.trimBox);
  if (!trimRect && (params.trimMarginMm ?? 0) > 0) {
    trimRect = inset(effectiveMedia, params.trimMarginMm!);
  }
  setFrom("TRIM_BOX", trimRect);

  let bleedRect = parse(params.bleedBox);
  if (params.generateBleed) {
    // Negative/undefined per-side values fall back to bleedMm, like the backend.
    const side = (v?: number) =>
      Math.max(0, v !== undefined && v >= 0 ? v : (params.bleedMm ?? 0));
    const generated = expandSides(
      result.TRIM_BOX.rect,
      side(params.bleedLeftMm),
      side(params.bleedRightMm),
      side(params.bleedBottomMm),
      side(params.bleedTopMm),
    );
    bleedRect = bleedRect ? union(bleedRect, generated) : generated;
  } else if (!bleedRect && (params.bleedMm ?? 0) > 0) {
    bleedRect = expand(result.TRIM_BOX.rect, params.bleedMm!);
  }
  setFrom("BLEED_BOX", bleedRect);

  setFrom("CROP_BOX", parse(params.cropBox));
  setFrom("ART_BOX", parse(params.artBox));

  let growTo: BoxRect | null = null;
  if (params.generateBleed || params.addCropMarks) {
    const marksMm = params.addCropMarks
      ? (params.cropMarkOffsetMm ?? 3) + (params.cropMarkLengthMm ?? 5)
      : 0;
    const marksArea = expand(result.TRIM_BOX.rect, marksMm);
    // With generateBleed the bleed rect is the generated target and already
    // covers the trim; without it the existing bleed box is left as is.
    growTo = params.generateBleed
      ? union(result.BLEED_BOX.rect, marksArea)
      : marksArea;
    // The controller materializes the TrimBox it painted bleed around.
    result.TRIM_BOX = { rect: result.TRIM_BOX.rect, inherited: false };
  }
  // A BleedBox past the page edge is dead geometry (renderers clip to the
  // MediaBox): the backend grows MediaBox/CropBox to cover the param-set
  // bleed as well. An inherited page BleedBox does not trigger growth.
  if (bleedRect) {
    growTo = growTo ? union(growTo, bleedRect) : bleedRect;
  }
  if (growTo) {
    result.MEDIA_BOX = {
      rect: union(result.MEDIA_BOX.rect, growTo),
      inherited: false,
    };
    result.CROP_BOX = {
      rect: union(result.CROP_BOX.rect, growTo),
      inherited: false,
    };
  }

  if (params.copyMissingFromMediaBox) {
    // `inherited` is exactly "no param, no margin, no dict entry" — the
    // controller's condition for filling from the MediaBox.
    for (const box of [
      "CROP_BOX",
      "TRIM_BOX",
      "BLEED_BOX",
      "ART_BOX",
    ] as PageBox[]) {
      if (result[box].inherited) {
        result[box] = { rect: effectiveMedia, inherited: false };
      }
    }
  }

  return result;
}
