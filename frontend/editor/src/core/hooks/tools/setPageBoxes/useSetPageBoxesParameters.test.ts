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

describe("parseBoxString", () => {
  test("parses four finite numbers", () => {
    expect(parseBoxString("1,2,3,4")).toEqual([1, 2, 3, 4]);
    expect(parseBoxString("1,2")).toBeNull();
  });
});
