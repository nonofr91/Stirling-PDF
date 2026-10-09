import { describe, expect, test } from "vitest";
import {
  parametersToProfile,
  profileToParameters,
} from "@app/hooks/tools/printPreflight/usePreflightProfiles";
import { defaultParameters } from "@app/hooks/tools/printPreflight/usePrintPreflightParameters";
import { PrintPreflightProfile } from "@app/types/printPreflight";

const fullProfile: PrintPreflightProfile = {
  name: "client-a",
  description: "test",
  builtin: false,
  requiredBleedMm: 5,
  minImageDpi: 300,
  hairlineThresholdPt: 0.5,
  checkBleedCoverage: false,
  minFontSizePt: 7,
  safetyMarginMm: 4,
  maxInkCoveragePercent: 280,
  renderedInkCoverage: true,
  minImage1BitDpi: 900,
  maxImageDpi: 400,
  maxSpotCount: 2,
  includeSummaryPage: false,
  disabledChecks: ["SAFETY_MARGIN"],
  fixups: ["EXTEND_BLEED"],
};

describe("profileToParameters", () => {
  test("maps every field", () => {
    const params = profileToParameters(fullProfile);
    expect(params.requiredBleedMm).toBe(5);
    expect(params.minImageDpi).toBe(300);
    expect(params.checkBleedCoverage).toBe(false);
    expect(params.renderedInkCoverage).toBe(true);
    expect(params.includeSummaryPage).toBe(false);
    expect(params.disabledChecks).toEqual(["SAFETY_MARGIN"]);
    expect(params.fixups).toEqual(["EXTEND_BLEED"]);
  });

  test("unset profile fields fall back to defaults, not stale input", () => {
    const sparse: PrintPreflightProfile = {
      name: "sparse",
      builtin: false,
      maxInkCoveragePercent: 250,
    };
    const params = profileToParameters(sparse);
    expect(params.maxInkCoveragePercent).toBe(250);
    expect(params.renderedInkCoverage).toBe(false);
    expect(params.minImageDpi).toBeUndefined();
    expect(params.checkBleedCoverage).toBe(true);
    expect(params.includeSummaryPage).toBe(true);
  });

  test("reportFormat is preserved from current parameters", () => {
    const params = profileToParameters(fullProfile, {
      ...defaultParameters,
      reportFormat: "fixedPdf",
    });
    expect(params.reportFormat).toBe("fixedPdf");
  });
});

describe("parametersToProfile", () => {
  test("round-trips through profileToParameters", () => {
    const body = parametersToProfile("client-a", "test", {
      ...defaultParameters,
      requiredBleedMm: 5,
      fixups: ["EXTEND_BLEED"],
    });
    const params = profileToParameters({ ...body, builtin: false });
    expect(params.requiredBleedMm).toBe(5);
    expect(params.fixups).toEqual(["EXTEND_BLEED"]);
  });

  test("undefined parameters serialize as null", () => {
    const body = parametersToProfile("x", "", defaultParameters);
    expect(body.requiredBleedMm).toBeNull();
    expect(body.fixups).toBeNull();
    expect(body.checkBleedCoverage).toBe(true);
  });
});
