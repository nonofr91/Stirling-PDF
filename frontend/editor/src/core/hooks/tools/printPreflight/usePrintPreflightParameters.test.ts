import { describe, expect, test } from "vitest";
import {
  defaultParameters,
  validatePrintPreflightParameters,
} from "@app/hooks/tools/printPreflight/usePrintPreflightParameters";

describe("validatePrintPreflightParameters", () => {
  test("accepts the defaults (all thresholds delegated to the backend)", () => {
    expect(validatePrintPreflightParameters(defaultParameters)).toBe(true);
    expect(defaultParameters.checkBleedCoverage).toBe(true);
  });

  test.each([0, 3, 10.5])("accepts a valid requiredBleedMm %s", (value) => {
    expect(
      validatePrintPreflightParameters({
        ...defaultParameters,
        requiredBleedMm: value,
      }),
    ).toBe(true);
  });

  test.each([-1, Number.NaN, Number.POSITIVE_INFINITY])(
    "rejects an invalid requiredBleedMm %s",
    (value) => {
      expect(
        validatePrintPreflightParameters({
          ...defaultParameters,
          requiredBleedMm: value,
        }),
      ).toBe(false);
    },
  );

  test.each([1, 150, 300])("accepts a valid minImageDpi %s", (value) => {
    expect(
      validatePrintPreflightParameters({
        ...defaultParameters,
        minImageDpi: value,
      }),
    ).toBe(true);
  });

  test.each([0, -72, Number.NaN])(
    "rejects an invalid minImageDpi %s",
    (value) => {
      expect(
        validatePrintPreflightParameters({
          ...defaultParameters,
          minImageDpi: value,
        }),
      ).toBe(false);
    },
  );

  test.each([0, 0.25, 1])("accepts a valid hairlineThresholdPt %s", (value) => {
    expect(
      validatePrintPreflightParameters({
        ...defaultParameters,
        hairlineThresholdPt: value,
      }),
    ).toBe(true);
  });

  test.each([-0.1, Number.NaN, Number.POSITIVE_INFINITY])(
    "rejects an invalid hairlineThresholdPt %s",
    (value) => {
      expect(
        validatePrintPreflightParameters({
          ...defaultParameters,
          hairlineThresholdPt: value,
        }),
      ).toBe(false);
    },
  );

  test.each([
    "minFontSizePt",
    "safetyMarginMm",
    "maxInkCoveragePercent",
    "maxSpotCount",
  ] as const)("rejects a negative %s", (key) => {
    expect(
      validatePrintPreflightParameters({ ...defaultParameters, [key]: -1 }),
    ).toBe(false);
  });

  test.each(["minImage1BitDpi", "maxImageDpi"] as const)(
    "rejects %s below 1 dpi",
    (key) => {
      expect(
        validatePrintPreflightParameters({ ...defaultParameters, [key]: 0 }),
      ).toBe(false);
    },
  );

  test.each([
    "minFontSizePt",
    "safetyMarginMm",
    "maxInkCoveragePercent",
    "minImage1BitDpi",
    "maxImageDpi",
    "maxSpotCount",
  ] as const)("accepts a valid %s", (key) => {
    expect(
      validatePrintPreflightParameters({ ...defaultParameters, [key]: 300 }),
    ).toBe(true);
  });

  test.each(["annotatedPdf", "reportPdf", "json"] as const)(
    "accepts reportFormat %s",
    (format) => {
      expect(
        validatePrintPreflightParameters({
          ...defaultParameters,
          reportFormat: format,
        }),
      ).toBe(true);
    },
  );

  test("rejects an unknown reportFormat", () => {
    expect(
      validatePrintPreflightParameters({
        ...defaultParameters,
        reportFormat: "docx" as never,
      }),
    ).toBe(false);
  });
});
