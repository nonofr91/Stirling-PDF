import { describe, expect, test } from "vitest";
import {
  cropFromApiParams,
  cropToApiParams,
} from "@app/hooks/tools/crop/useCropOperation";
import {
  CropParameters,
  defaultParameters,
} from "@app/hooks/tools/crop/useCropParameters";

describe("crop mappers", () => {
  // With autoCrop on the coordinates aren't sent, so they must not resurface on
  // the round trip; with autoCrop off the rectangle must survive intact.
  test.each<{ label: string; overrides: Partial<CropParameters> }>([
    { label: "autoCrop on", overrides: { autoCrop: true } },
    {
      label: "autoCrop off with a rectangle",
      overrides: {
        autoCrop: false,
        cropArea: { x: 10, y: 20, width: 300, height: 400 },
      },
    },
    {
      label: "cropToBox on",
      overrides: { cropToBox: true, pageBox: "TRIM_BOX" },
    },
  ])("round-trips backend params ($label)", ({ overrides }) => {
    const api = cropToApiParams({ ...defaultParameters, ...overrides });
    const roundTripped = cropToApiParams({
      ...defaultParameters,
      ...cropFromApiParams(api),
    });

    expect(roundTripped).toEqual(api);
  });

  test("cropToBox sends the box and drops the drawn rectangle", () => {
    const api = cropToApiParams({
      ...defaultParameters,
      cropToBox: true,
      pageBox: "BLEED_BOX",
      cropArea: { x: 10, y: 20, width: 300, height: 400 },
    });

    expect(api.cropToBox).toBe(true);
    expect(api.pageBox).toBe("BLEED_BOX");
    expect(api.autoCrop).toBe(false);
    expect(api.x).toBeUndefined();
    expect(api.width).toBeUndefined();
  });
});
