import { describe, expect, test } from "vitest";
import {
  defaultParameters,
  validateCutContourParameters,
} from "@app/hooks/tools/cutContour/useCutContourParameters";
import {
  cutContourFromApiParams,
  cutContourToApiParams,
} from "@app/hooks/tools/cutContour/useCutContourOperation";

describe("cutContour mappers", () => {
  test("blank optional strings are omitted from the request body", () => {
    const api = cutContourToApiParams({
      ...defaultParameters,
      bleedMm: 3,
    });

    expect(api.spotName).toBe("CutContour");
    expect(api.bleedMm).toBe(3);
    expect(api.aiModelId).toBeUndefined();
    expect(api.layerName).toBeUndefined();
  });

  test("api params map back with defaults for absent fields", () => {
    const params = cutContourFromApiParams({ extractionMode: "ALPHA" });
    expect(params.extractionMode).toBe("ALPHA");
    expect(params.spotName).toBe("CutContour");
    expect(params.keepHoles).toBe(false);
    expect(params.clipArtwork).toBe(false);
    expect(params.processingSteps).toBe(true);
  });
});

describe("validateCutContourParameters", () => {
  test("defaults are valid", () => {
    expect(validateCutContourParameters(defaultParameters)).toBe(true);
  });

  test("blank spot name is rejected", () => {
    expect(
      validateCutContourParameters({ ...defaultParameters, spotName: "  " }),
    ).toBe(false);
  });

  test.each([
    ["dpi", 30],
    ["dpi", 601],
    ["alphaThreshold", 300],
    ["backgroundTolerance", -1],
    ["minAreaMm2", -0.5],
    ["smoothness", 101],
    ["offsetMm", 60],
    ["offsetMm", -60],
    ["aiThreshold", 1.5],
    ["strokeWidthPt", 0],
    ["strokeWidthPt", -1],
    ["bleedMm", 51],
    ["bleedMm", -2],
  ] as const)("%s=%s is rejected", (key, value) => {
    expect(
      validateCutContourParameters({ ...defaultParameters, [key]: value }),
    ).toBe(false);
  });

  test.each([
    ["dpi", 72],
    ["dpi", 600],
    ["alphaThreshold", 0],
    ["offsetMm", 50],
    ["offsetMm", -50],
    ["aiThreshold", 0],
    ["aiThreshold", 1],
    ["bleedMm", 50],
    ["strokeWidthPt", 0.1],
  ] as const)("%s=%s is accepted", (key, value) => {
    expect(
      validateCutContourParameters({ ...defaultParameters, [key]: value }),
    ).toBe(true);
  });

  test("undefined optional fields stay valid", () => {
    expect(
      validateCutContourParameters({
        ...defaultParameters,
        dpi: undefined,
        smoothness: undefined,
        bleedMm: undefined,
      }),
    ).toBe(true);
  });
});
