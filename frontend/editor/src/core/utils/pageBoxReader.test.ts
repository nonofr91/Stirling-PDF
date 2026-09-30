import { describe, expect, it } from "vitest";
import type { PdfPageBoxes } from "@embedpdf/models";
import {
  pdfRectToPageFractions,
  snapshotFromEmbedPdfPage,
} from "@app/utils/pageBoxReader";

const box = (
  left: number,
  bottom: number,
  right: number,
  top: number,
): PdfPageBoxes["media"] => ({ left, top, right, bottom });

describe("snapshotFromEmbedPdfPage", () => {
  it("returns null when the page carries no boxes", () => {
    expect(snapshotFromEmbedPdfPage(undefined)).toBeNull();
  });

  it("maps embedpdf boxes to rects and marks declared boxes explicit", () => {
    const snap = snapshotFromEmbedPdfPage({
      media: box(0, 0, 595, 842),
      crop: box(0, 0, 595, 842),
      trim: box(20, 30, 420, 630),
      bleed: box(10, 15, 430, 645),
    })!;

    expect(snap.boxes.TRIM_BOX).toEqual({
      x: 20,
      y: 30,
      width: 400,
      height: 600,
    });
    expect(snap.explicit.has("MEDIA_BOX")).toBe(true);
    expect(snap.explicit.has("TRIM_BOX")).toBe(true);
    expect(snap.explicit.has("BLEED_BOX")).toBe(true);
    expect(snap.explicit.has("ART_BOX")).toBe(false);
  });

  it("falls back to the MediaBox for absent named boxes, like the backend", () => {
    const snap = snapshotFromEmbedPdfPage({
      media: box(0, 0, 595, 842),
      crop: box(10, 10, 585, 832),
    })!;

    // PageBoxUtils.resolvePageBox resolves a missing named box to the
    // MediaBox — not to the CropBox the PDF spec would inherit.
    expect(snap.boxes.TRIM_BOX).toEqual(snap.boxes.MEDIA_BOX);
    expect(snap.boxes.BLEED_BOX).toEqual(snap.boxes.MEDIA_BOX);
    expect(snap.boxes.ART_BOX).toEqual(snap.boxes.MEDIA_BOX);
    expect(snap.boxes.TRIM_BOX).not.toEqual(snap.boxes.CROP_BOX);
  });

  it("converts the Rotation enum (quarters of a turn) to degrees", () => {
    const boxes: PdfPageBoxes = {
      media: box(0, 0, 100, 200),
      crop: box(0, 0, 100, 200),
    };
    expect(snapshotFromEmbedPdfPage(boxes, 0)!.rotation).toBe(0);
    expect(snapshotFromEmbedPdfPage(boxes, 2)!.rotation).toBe(180);
  });

  it("normalizes rects given in a flipped corner order", () => {
    const snap = snapshotFromEmbedPdfPage({
      media: { left: 0, top: 0, right: 595, bottom: 842 },
      crop: { left: 0, top: 0, right: 595, bottom: 842 },
    })!;
    expect(snap.boxes.MEDIA_BOX).toEqual({
      x: 0,
      y: 0,
      width: 595,
      height: 842,
    });
  });
});

describe("pdfRectToPageFractions", () => {
  const visible = { x: 0, y: 0, width: 600, height: 800 };

  it("maps a rect covering the visible box to the full page", () => {
    expect(pdfRectToPageFractions(visible, visible)).toEqual({
      x: 0,
      y: 0,
      width: 1,
      height: 1,
    });
  });

  it("flips the Y axis: a rect at the PDF bottom sits at the CSS bottom", () => {
    const f = pdfRectToPageFractions(
      { x: 0, y: 0, width: 600, height: 200 },
      visible,
    );
    expect(f.y).toBeCloseTo(0.75);
  });

  it("offsets rects against a non-origin CropBox", () => {
    const cropBox = { x: 10, y: 20, width: 400, height: 600 };
    expect(pdfRectToPageFractions(cropBox, cropBox)).toEqual({
      x: 0,
      y: 0,
      width: 1,
      height: 1,
    });
  });

  it("can produce fractions outside 0–1 for boxes extending past the crop box", () => {
    const bleed = { x: -10, y: -10, width: 620, height: 820 };
    const f = pdfRectToPageFractions(bleed, visible);
    expect(f.x).toBeLessThan(0);
    expect(f.y).toBeLessThan(0);
    expect(f.width).toBeGreaterThan(1);
    expect(f.height).toBeGreaterThan(1);
  });
});
