import { describe, expect, test } from "vitest";
import {
  computeEyeletMarks,
  EyeletSideGeometry,
  EyeletMarkMode,
} from "@app/utils/eyeletMarkGeometry";
import type { BoxRect } from "@app/utils/pageBoxReader";

const MM = 72 / 25.4;
const TRIM: BoxRect = { x: 0, y: 0, width: 600, height: 800 };
const OFF = 15 * MM;

const sides = (
  overrides: Partial<
    Record<"top" | "right" | "bottom" | "left", Partial<EyeletSideGeometry>>
  > = {},
): Record<"top" | "right" | "bottom" | "left", EyeletSideGeometry> => ({
  top: { offsetMm: 15, count: 0, maxSpacingMm: 0, ...overrides.top },
  right: { offsetMm: 15, count: 0, maxSpacingMm: 0, ...overrides.right },
  bottom: { offsetMm: 15, count: 0, maxSpacingMm: 0, ...overrides.bottom },
  left: { offsetMm: 15, count: 0, maxSpacingMm: 0, ...overrides.left },
});

const hasMarkAt = (
  marks: { x: number; y: number }[],
  x: number,
  y: number,
): boolean =>
  marks.some((p) => Math.abs(p.x - x) < 0.05 && Math.abs(p.y - y) < 0.05);

describe("computeEyeletMarks", () => {
  test("COUNT on four sides dedups the four corners", () => {
    const marks = computeEyeletMarks(
      "COUNT",
      TRIM,
      sides({
        top: { count: 2 },
        right: { count: 2 },
        bottom: { count: 2 },
        left: { count: 2 },
      }),
    );
    expect(marks).toHaveLength(4);
    expect(marks.every((m) => m.corner)).toBe(true);
    expect(hasMarkAt(marks, OFF, 800 - OFF)).toBe(true);
    expect(hasMarkAt(marks, 600 - OFF, 800 - OFF)).toBe(true);
    expect(hasMarkAt(marks, 600 - OFF, OFF)).toBe(true);
    expect(hasMarkAt(marks, OFF, OFF)).toBe(true);
  });

  test("COUNT on the top side alone spans the full width", () => {
    const marks = computeEyeletMarks(
      "COUNT",
      TRIM,
      sides({ top: { count: 4 } }),
    );
    expect(marks).toHaveLength(4);
    expect(hasMarkAt(marks, 0, 800 - OFF)).toBe(true);
    expect(hasMarkAt(marks, 200, 800 - OFF)).toBe(true);
    expect(hasMarkAt(marks, 400, 800 - OFF)).toBe(true);
    expect(hasMarkAt(marks, 600, 800 - OFF)).toBe(true);
  });

  test("adjacent sides share one corner mark", () => {
    const marks = computeEyeletMarks(
      "COUNT",
      TRIM,
      sides({ top: { count: 2 }, left: { count: 2 } }),
    );
    expect(marks).toHaveLength(3);
    expect(marks.filter((m) => m.corner)).toHaveLength(1);
    expect(hasMarkAt(marks, OFF, 800 - OFF)).toBe(true);
    expect(hasMarkAt(marks, 600, 800 - OFF)).toBe(true);
    expect(hasMarkAt(marks, OFF, 0)).toBe(true);
  });

  test("MAX_SPACING derives the count from the largest gap", () => {
    const marks = computeEyeletMarks(
      "MAX_SPACING",
      TRIM,
      sides({ top: { maxSpacingMm: 100 } }),
    );
    // 100 mm = 283.46 pt over a 600 pt span → 3 intervals → 4 marks.
    expect(marks).toHaveLength(4);
    expect(hasMarkAt(marks, 0, 800 - OFF)).toBe(true);
    expect(hasMarkAt(marks, 600, 800 - OFF)).toBe(true);
  });

  test("MAX_SPACING beyond the span keeps only the ends", () => {
    const marks = computeEyeletMarks(
      "MAX_SPACING",
      TRIM,
      sides({ top: { maxSpacingMm: 500 } }),
    );
    expect(marks).toHaveLength(2);
  });

  test("a negative offset places the line outside the trim", () => {
    const marks = computeEyeletMarks(
      "COUNT",
      TRIM,
      sides({ top: { count: 2, offsetMm: -10 } }),
    );
    expect(marks.every((p) => Math.abs(p.y - (800 + 10 * MM)) < 0.05)).toBe(
      true,
    );
  });

  test.each<[EyeletMarkMode, Parameters<typeof sides>[0]]>([
    ["COUNT", { top: { count: 1 } }],
    ["COUNT", {}],
    ["MAX_SPACING", {}],
    ["MAX_SPACING", { top: { maxSpacingMm: 0 } }],
  ])("invalid configuration %s %j yields no marks", (mode, overrides) => {
    expect(computeEyeletMarks(mode, TRIM, sides(overrides))).toHaveLength(0);
  });

  test("offsets overlapping across a side yield no marks on it", () => {
    const marks = computeEyeletMarks(
      "COUNT",
      TRIM,
      sides({
        top: { count: 4 },
        left: { count: 2, offsetMm: 200 },
        right: { count: 2, offsetMm: 200 },
      }),
    );
    // 200 mm offsets on a 600 pt trim leave a negative top interval: only the
    // two lateral sides paint, 4 marks after corner dedup, none mid-page.
    expect(marks).toHaveLength(4);
    expect(hasMarkAt(marks, 389, 800 - OFF)).toBe(false);
    expect(hasMarkAt(marks, 211, 800 - OFF)).toBe(false);
  });
});
