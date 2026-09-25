import { describe, expect, it } from "vitest";
import { PDFDocument } from "@cantoo/pdf-lib";
import { readPageBoxSnapshot } from "@app/utils/pageBoxReader";

const makePdfFile = async (
  boxes: Partial<
    Record<"trim" | "bleed" | "art" | "crop", [number, number, number, number]>
  > = {},
): Promise<File> => {
  const doc = await PDFDocument.create();
  const page = doc.addPage([595, 842]);
  if (boxes.crop) page.setCropBox(...boxes.crop);
  if (boxes.trim) page.setTrimBox(...boxes.trim);
  if (boxes.bleed) page.setBleedBox(...boxes.bleed);
  if (boxes.art) page.setArtBox(...boxes.art);
  const bytes = await doc.save();
  const ab = bytes.buffer.slice(
    bytes.byteOffset,
    bytes.byteOffset + bytes.byteLength,
  ) as ArrayBuffer;
  // The test env's File.arrayBuffer() does not preserve the blob bytes;
  // readPageBoxSnapshot only needs arrayBuffer(), so provide it directly.
  return { arrayBuffer: async () => ab } as File;
};

describe("readPageBoxSnapshot", () => {
  it("detects explicit boxes and inherits the rest per PDF spec", async () => {
    const file = await makePdfFile({
      trim: [20, 30, 400, 600],
      bleed: [10, 15, 420, 630],
    });
    const snap = await readPageBoxSnapshot(file);
    expect(snap).not.toBeNull();
    expect(snap!.explicit.has("MEDIA_BOX")).toBe(true);
    expect(snap!.explicit.has("TRIM_BOX")).toBe(true);
    expect(snap!.explicit.has("BLEED_BOX")).toBe(true);
    expect(snap!.explicit.has("CROP_BOX")).toBe(false);
    expect(snap!.explicit.has("ART_BOX")).toBe(false);
    expect(snap!.boxes.TRIM_BOX).toEqual({
      x: 20,
      y: 30,
      width: 400,
      height: 600,
    });
    // boxes absent from the dict still report the spec fallback value
    expect(snap!.boxes.CROP_BOX).toEqual(snap!.boxes.MEDIA_BOX);
    expect(snap!.boxes.ART_BOX).toEqual(snap!.boxes.CROP_BOX);
  });

  it("returns null for an unparseable file", async () => {
    const garbage = new TextEncoder().encode("not a pdf").buffer as ArrayBuffer;
    const file = { arrayBuffer: async () => garbage } as File;
    expect(await readPageBoxSnapshot(file)).toBeNull();
  });
});
