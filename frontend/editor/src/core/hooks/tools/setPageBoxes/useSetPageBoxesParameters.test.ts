import { describe, expect, test } from "vitest";
import {
  defaultParameters,
  parseBoxString,
  validateSetPageBoxesParameters,
} from "@app/hooks/tools/setPageBoxes/useSetPageBoxesParameters";
import { setPageBoxesToApiParams } from "@app/hooks/tools/setPageBoxes/useSetPageBoxesOperation";

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
});
test("accepts drawBoxes alone (draw existing boxes without changes)", () => {
  expect(
    validateSetPageBoxesParameters({ ...defaultParameters, drawBoxes: true }),
  ).toBe(true);
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
});
