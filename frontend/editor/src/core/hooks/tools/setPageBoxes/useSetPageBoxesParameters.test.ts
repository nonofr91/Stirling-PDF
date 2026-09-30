import { describe, expect, test } from "vitest";
import {
  defaultParameters,
  parseBoxString,
  validateSetPageBoxesParameters,
} from "@app/hooks/tools/setPageBoxes/useSetPageBoxesParameters";
import {
  setPageBoxesFromApiParams,
  setPageBoxesToApiParams,
} from "@app/hooks/tools/setPageBoxes/useSetPageBoxesOperation";

describe("setPageBoxes mappers", () => {
  test("empty fields are omitted from the request body", () => {
    const api = setPageBoxesToApiParams({
      ...defaultParameters,
      trimBox: "20,30,420,630",
      bleedMm: 5,
    });

    expect(api.trimBox).toBe("20,30,420,630");
    expect(api.bleedMm).toBe(5);
    expect(api.mediaBox).toBeUndefined();
    expect(api.cropBox).toBeUndefined();
  });

  test("bleed generation options are mapped to the request body", () => {
    const api = setPageBoxesToApiParams({
      ...defaultParameters,
      generateBleed: true,
      bleedMethod: "PIXEL_REPEAT",
      bleedMm: 3,
      bleedLeftMm: 5,
      bleedCorners: false,
      bleedDpi: 200,
      bleedInsetMm: 1.5,
      addCropMarks: true,
      cropMarkLengthMm: 4,
      cropMarkOffsetMm: 2,
      cropMarkWeightPt: 0.5,
    });

    expect(api.generateBleed).toBe(true);
    expect(api.bleedMethod).toBe("PIXEL_REPEAT");
    expect(api.bleedLeftMm).toBe(5);
    expect(api.bleedRightMm).toBeUndefined();
    expect(api.bleedCorners).toBe(false);
    expect(api.bleedDpi).toBe(200);
    expect(api.addCropMarks).toBe(true);
    expect(api.cropMarkLengthMm).toBe(4);
    expect(api.cropMarkWeightPt).toBe(0.5);
  });

  test("api params map back with defaults for absent fields", () => {
    const params = setPageBoxesFromApiParams({ generateBleed: true });
    expect(params.generateBleed).toBe(true);
    expect(params.bleedMethod).toBe("MIRROR");
    expect(params.bleedCorners).toBe(true);
    expect(params.addCropMarks).toBe(false);
  });
});

describe("validateSetPageBoxesParameters", () => {
  test("rejects a no-op request", () => {
    expect(validateSetPageBoxesParameters(defaultParameters)).toBe(false);
  });

  test.each(["10,10,400,600", " 0 , 0 , 595 , 842 ", "-15,-15,630,875"])(
    "accepts a valid box string %s",
    (trimBox) => {
      expect(
        validateSetPageBoxesParameters({ ...defaultParameters, trimBox }),
      ).toBe(true);
    },
  );

  test.each([
    "1,2,3",
    "a,b,c,d",
    "10,10,0,600",
    "10,10,-5,600",
    "10,10,NaN,600",
  ])("rejects a malformed box string %s", (trimBox) => {
    expect(
      validateSetPageBoxesParameters({ ...defaultParameters, trimBox }),
    ).toBe(false);
  });

  test("accepts margin-only and copy-only requests", () => {
    expect(
      validateSetPageBoxesParameters({ ...defaultParameters, bleedMm: 3 }),
    ).toBe(true);
    expect(
      validateSetPageBoxesParameters({
        ...defaultParameters,
        copyMissingFromMediaBox: true,
      }),
    ).toBe(true);
  });

  test("generateBleed alone is not enough without a bleed source", () => {
    expect(
      validateSetPageBoxesParameters({
        ...defaultParameters,
        generateBleed: true,
      }),
    ).toBe(false);
  });

  test.each([
    { bleedMm: 3 },
    { bleedLeftMm: 3 },
    { bleedBox: "10,10,575,822" },
  ])("accepts generateBleed with a bleed source %o", (extra) => {
    expect(
      validateSetPageBoxesParameters({
        ...defaultParameters,
        generateBleed: true,
        ...extra,
      }),
    ).toBe(true);
  });

  test("rejects out-of-range dpi and negative inset for generateBleed", () => {
    expect(
      validateSetPageBoxesParameters({
        ...defaultParameters,
        generateBleed: true,
        bleedMm: 3,
        bleedDpi: 50,
      }),
    ).toBe(false);
    expect(
      validateSetPageBoxesParameters({
        ...defaultParameters,
        generateBleed: true,
        bleedMm: 3,
        bleedInsetMm: -1,
      }),
    ).toBe(false);
  });

  test("addCropMarks alone is a valid request but validates its dimensions", () => {
    expect(
      validateSetPageBoxesParameters({
        ...defaultParameters,
        addCropMarks: true,
      }),
    ).toBe(true);
    expect(
      validateSetPageBoxesParameters({
        ...defaultParameters,
        addCropMarks: true,
        cropMarkLengthMm: 0,
      }),
    ).toBe(false);
    expect(
      validateSetPageBoxesParameters({
        ...defaultParameters,
        addCropMarks: true,
        cropMarkOffsetMm: -1,
      }),
    ).toBe(false);
  });
});

describe("parseBoxString", () => {
  test("parses four finite numbers", () => {
    expect(parseBoxString("1,2,3,4")).toEqual([1, 2, 3, 4]);
    expect(parseBoxString("1,2")).toBeNull();
  });
});

import {
  computeResultingBoxes,
  PageBoxSnapshot,
  BoxRect,
} from "@app/utils/pageBoxReader";

const rect = (x: number, y: number, w: number, h: number): BoxRect => ({
  x,
  y,
  width: w,
  height: h,
});

// 595×842 MediaBox, no other box defined on the page
const bareSnapshot: PageBoxSnapshot = {
  boxes: {
    MEDIA_BOX: rect(0, 0, 595, 842),
    CROP_BOX: rect(0, 0, 595, 842),
    TRIM_BOX: rect(0, 0, 595, 842),
    BLEED_BOX: rect(0, 0, 595, 842),
    ART_BOX: rect(0, 0, 595, 842),
  },
  explicit: new Set(["MEDIA_BOX"]),
  rotation: 0,
};

describe("computeResultingBoxes", () => {
  test("trimMarginMm shrinks the MediaBox into a TrimBox", () => {
    const r = computeResultingBoxes(
      { ...defaultParameters, trimMarginMm: 10 },
      bareSnapshot,
      parseBoxString,
    );
    const mm10 = 10 * (72 / 25.4);
    expect(r.TRIM_BOX.rect.x).toBeCloseTo(mm10);
    expect(r.TRIM_BOX.rect.y).toBeCloseTo(mm10);
    expect(r.TRIM_BOX.rect.width).toBeCloseTo(595 - 2 * mm10);
    expect(r.TRIM_BOX.rect.height).toBeCloseTo(842 - 2 * mm10);
    expect(r.TRIM_BOX.inherited).toBe(false);
  });

  test("bleedMm expands around the resolved TrimBox", () => {
    const r = computeResultingBoxes(
      { ...defaultParameters, trimMarginMm: 10, bleedMm: 5 },
      bareSnapshot,
      parseBoxString,
    );
    const mm10 = 10 * (72 / 25.4);
    const mm5 = 5 * (72 / 25.4);
    expect(r.BLEED_BOX.rect.x).toBeCloseTo(mm10 - mm5);
    expect(r.BLEED_BOX.rect.width).toBeCloseTo(595 - 2 * mm10 + 2 * mm5);
  });

  test("copyMissingFromMediaBox fills only absent boxes", () => {
    const withTrim: PageBoxSnapshot = {
      boxes: {
        ...bareSnapshot.boxes,
        TRIM_BOX: rect(20, 30, 400, 600),
      },
      explicit: new Set(["MEDIA_BOX", "TRIM_BOX"]),
      rotation: 0,
    };
    const r = computeResultingBoxes(
      { ...defaultParameters, copyMissingFromMediaBox: true },
      withTrim,
      parseBoxString,
    );
    expect(r.TRIM_BOX.rect).toEqual(rect(20, 30, 400, 600));
    expect(r.CROP_BOX.rect).toEqual(rect(0, 0, 595, 842));
    expect(r.CROP_BOX.inherited).toBe(false);
  });

  test("marks boxes absent from the page as inherited", () => {
    const r = computeResultingBoxes(
      defaultParameters,
      bareSnapshot,
      parseBoxString,
    );
    expect(r.TRIM_BOX.inherited).toBe(true);
    expect(r.MEDIA_BOX.inherited).toBe(false);
  });

  test("generateBleed expands BleedBox per side and grows MediaBox", () => {
    const r = computeResultingBoxes(
      {
        ...defaultParameters,
        trimMarginMm: 10,
        generateBleed: true,
        bleedLeftMm: 8,
        bleedRightMm: 2,
        bleedBottomMm: 4,
        bleedTopMm: 0,
      },
      bareSnapshot,
      parseBoxString,
    );
    const pt = (mm: number) => mm * (72 / 25.4);
    const mm10 = pt(10);
    const bleed = r.BLEED_BOX.rect;
    expect(bleed.x).toBeCloseTo(mm10 - pt(8));
    expect(bleed.width).toBeCloseTo(595 - 2 * mm10 + pt(8) + pt(2));
    expect(bleed.y).toBeCloseTo(mm10 - pt(4));
    expect(bleed.height).toBeCloseTo(842 - 2 * mm10 + pt(4));
    // 8mm left bleed reaches x = 10mm - 8mm = 2mm > 0 → media stays A4.
    expect(r.MEDIA_BOX.rect).toEqual(rect(0, 0, 595, 842));
    expect(r.TRIM_BOX.inherited).toBe(false);
  });

  test("crop marks grow MediaBox past the trim", () => {
    const r = computeResultingBoxes(
      {
        ...defaultParameters,
        trimMarginMm: 5,
        addCropMarks: true,
      },
      bareSnapshot,
      parseBoxString,
    );
    // marks extent = 3mm offset + 5mm length = 8mm past the 5mm trim margin.
    const expected = (8 - 5) * (72 / 25.4);
    expect(r.MEDIA_BOX.rect.x).toBeCloseTo(-expected);
    expect(r.CROP_BOX.rect.x).toBeCloseTo(-expected);
  });

  test("a BleedBox past the page edge grows MediaBox and CropBox", () => {
    // A BleedBox beyond the MediaBox is dead geometry — the backend grows the
    // visible page to cover it, so the preview must show the same growth.
    const r = computeResultingBoxes(
      { ...defaultParameters, trimMarginMm: 5, bleedMm: 10 },
      bareSnapshot,
      parseBoxString,
    );
    // 10mm bleed around a 5mm-inset trim reaches 5mm past the page edge.
    const expected = (10 - 5) * (72 / 25.4);
    expect(r.MEDIA_BOX.rect.x).toBeCloseTo(-expected);
    expect(r.CROP_BOX.rect.x).toBeCloseTo(-expected);
    expect(r.BLEED_BOX.rect.x).toBeCloseTo(-expected);
  });

  test("an explicit bleedBox contributes to the growth", () => {
    const bleed = `0,0,${595 + 30},${842 + 30}`;
    const r = computeResultingBoxes(
      { ...defaultParameters, bleedBox: bleed },
      bareSnapshot,
      parseBoxString,
    );
    expect(r.MEDIA_BOX.rect.width).toBeCloseTo(595 + 30);
    expect(r.CROP_BOX.rect.width).toBeCloseTo(595 + 30);
  });
});
