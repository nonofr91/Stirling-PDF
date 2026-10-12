import { describe, expect, test } from "vitest";
import {
  defaultParameters,
  validateEyeletMarksParameters,
  EyeletMarksParameters,
} from "@app/hooks/tools/eyeletMarks/useEyeletMarksParameters";
import {
  eyeletMarksFromApiParams,
  eyeletMarksToApiParams,
} from "@app/hooks/tools/eyeletMarks/useEyeletMarksOperation";

const countParams = (
  overrides: Partial<EyeletMarksParameters> = {},
): EyeletMarksParameters => ({
  ...defaultParameters,
  mode: "COUNT",
  countTop: 4,
  countBottom: 4,
  countLeft: 3,
  countRight: 3,
  ...overrides,
});

describe("eyeletMarks mappers", () => {
  test("blank layer name is omitted from the request body", () => {
    const api = eyeletMarksToApiParams(countParams());
    expect(api.mode).toBe("COUNT");
    expect(api.countTop).toBe(4);
    expect(api.offsetTopMm).toBe(15);
    expect(api.layerName).toBeUndefined();
  });

  test("api params map back with defaults for absent fields", () => {
    const params = eyeletMarksFromApiParams({ mode: "MAX_SPACING" });
    expect(params.mode).toBe("MAX_SPACING");
    expect(params.countTop).toBeUndefined();
    expect(params.layerName).toBe("");
  });
});

describe("validateEyeletMarksParameters", () => {
  test("count mode with enabled sides is valid", () => {
    expect(validateEyeletMarksParameters(countParams())).toBe(true);
  });

  test("count mode rejects a side count of 1", () => {
    expect(validateEyeletMarksParameters(countParams({ countTop: 1 }))).toBe(
      false,
    );
  });

  test("count mode requires at least one enabled side", () => {
    expect(
      validateEyeletMarksParameters(
        countParams({
          countTop: 0,
          countBottom: 0,
          countLeft: 0,
          countRight: 0,
        }),
      ),
    ).toBe(false);
  });

  test("max spacing mode requires a positive spacing on one side", () => {
    const params: EyeletMarksParameters = {
      ...defaultParameters,
      mode: "MAX_SPACING",
      maxSpacingTopMm: 100,
      maxSpacingRightMm: 0,
      maxSpacingBottomMm: 0,
      maxSpacingLeftMm: 0,
    };
    expect(validateEyeletMarksParameters(params)).toBe(true);
    expect(
      validateEyeletMarksParameters({ ...params, maxSpacingTopMm: 0 }),
    ).toBe(false);
  });

  test.each([
    ["eyeletDiameterMm", 0],
    ["eyeletDiameterMm", -2],
    ["eyeletDiameterMm", 101],
    ["strokeWidthPt", 0],
    ["offsetTopMm", -201],
    ["offsetTopMm", 201],
  ])("out-of-range %s=%s is rejected", (key, value) => {
    const params = { ...countParams(), [key]: value };
    expect(validateEyeletMarksParameters(params)).toBe(false);
  });

  test("a negative side offset is allowed", () => {
    expect(
      validateEyeletMarksParameters(countParams({ offsetTopMm: -10 })),
    ).toBe(true);
  });
});
