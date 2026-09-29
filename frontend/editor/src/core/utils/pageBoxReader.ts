import { PAGE_BOXES, PageBox } from "@app/constants/pageBoxConstants";

export interface BoxRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface PageBoxSnapshot {
  /** Effective rects (spec fallback applied, e.g. TrimBox falls back to CropBox). */
  boxes: Record<PageBox, BoxRect>;
  /** Boxes explicitly present in the page dictionary. */
  explicit: Set<PageBox>;
  /** Page rotation in degrees — box rects are in unrotated space, so
      thumbnail overlays are only aligned when this is 0. */
  rotation: number;
}

const BOX_PDF_NAMES: Record<PageBox, string> = {
  MEDIA_BOX: "MediaBox",
  CROP_BOX: "CropBox",
  TRIM_BOX: "TrimBox",
  BLEED_BOX: "BleedBox",
  ART_BOX: "ArtBox",
};

type PdfLib = typeof import("@cantoo/pdf-lib");

interface LoadedPdf {
  pdfLib: PdfLib;
  doc: import("@cantoo/pdf-lib").PDFDocument;
}

// One pdf-lib parse per file, shared by every page-indexed read.
const docCache = new WeakMap<Blob, Promise<LoadedPdf | null>>();

function loadPdf(file: Blob): Promise<LoadedPdf | null> {
  let cached = docCache.get(file);
  if (!cached) {
    cached = import("@cantoo/pdf-lib")
      .then(async (pdfLib) => ({
        pdfLib,
        doc: await pdfLib.PDFDocument.load(await file.arrayBuffer(), {
          ignoreEncryption: true,
        }),
      }))
      .catch(() => null);
    docCache.set(file, cached);
  }
  return cached;
}

/**
 * Reads the five page boxes of `pageIndex` (default: first page). Returns null
 * when the file cannot be parsed or the page is out of range — callers should
 * render nothing rather than an empty frame.
 */
export async function readPageBoxSnapshot(
  file: Blob,
  pageIndex = 0,
): Promise<PageBoxSnapshot | null> {
  const loaded = await loadPdf(file);
  if (!loaded || pageIndex < 0 || pageIndex >= loaded.doc.getPageCount()) {
    return null;
  }
  const { pdfLib } = loaded;
  const page = loaded.doc.getPage(pageIndex);
  const boxes = {
    MEDIA_BOX: page.getMediaBox(),
    CROP_BOX: page.getCropBox(),
    TRIM_BOX: page.getTrimBox(),
    BLEED_BOX: page.getBleedBox(),
    ART_BOX: page.getArtBox(),
  };
  const explicit = new Set<PageBox>();
  for (const box of PAGE_BOXES) {
    if (page.node.get(pdfLib.PDFName.of(BOX_PDF_NAMES[box]))) {
      explicit.add(box);
    }
  }
  return { boxes, explicit, rotation: page.getRotation().angle };
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

  if (params.generateBleed || params.addCropMarks) {
    const marksMm = params.addCropMarks
      ? (params.cropMarkOffsetMm ?? 3) + (params.cropMarkLengthMm ?? 5)
      : 0;
    const marksArea = expand(result.TRIM_BOX.rect, marksMm);
    // With generateBleed the bleed rect is the generated target and already
    // covers the trim; without it the existing bleed box is left as is.
    const required = params.generateBleed
      ? union(result.BLEED_BOX.rect, marksArea)
      : marksArea;
    result.MEDIA_BOX = {
      rect: union(result.MEDIA_BOX.rect, required),
      inherited: false,
    };
    result.CROP_BOX = {
      rect: union(result.CROP_BOX.rect, required),
      inherited: false,
    };
    // The controller materializes the TrimBox it painted bleed around.
    result.TRIM_BOX = { rect: result.TRIM_BOX.rect, inherited: false };
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
