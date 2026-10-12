/**
 * Mirrors EyeletMarkPlacer.placements (app/common): every mark centre in PDF
 * user space, deduplicated so a shared corner is painted once. Used by the
 * settings panel to preview exactly what the backend will paint.
 */

import type { BoxRect } from "@app/utils/pageBoxReader";

export type EyeletMarkMode = "COUNT" | "MAX_SPACING";

export type EyeletSide = "top" | "right" | "bottom" | "left";

export const EYELET_SIDES: EyeletSide[] = ["top", "right", "bottom", "left"];

export interface EyeletSideGeometry {
  /** Signed distance from the trim edge in mm: positive inside, negative outside. */
  offsetMm: number;
  /** COUNT mode: marks on this side including both ends; 0 disables the side. */
  count: number;
  /** MAX_SPACING mode: largest allowed gap between marks; <= 0 disables. */
  maxSpacingMm: number;
}

export interface EyeletMarkPoint {
  x: number;
  y: number;
  /** Two enabled sides meet here: painted as a diagonal cross, not a straight one. */
  corner: boolean;
}

const MM_TO_PT = 72 / 25.4;
const mmToPt = (mm: number) => mm * MM_TO_PT;

const enabled = (mode: EyeletMarkMode, side: EyeletSideGeometry): boolean =>
  mode === "COUNT" ? side.count > 0 : side.maxSpacingMm > 0;

/**
 * The side's mark centres. Returns an empty list for a disabled side and for
 * the configurations the backend would reject (a single mark on a side, or
 * offsets that consume the whole span) — the preview then draws nothing.
 */
function sidePoints(
  mode: EyeletMarkMode,
  side: EyeletSideGeometry,
  a0: { x: number; y: number },
  a1: { x: number; y: number },
): { x: number; y: number }[] {
  // The segment is axis-aligned, so the span is a signed coordinate delta —
  // hypot() would hide a reversed interval where neighbouring offsets overlap.
  const span = a1.x - a0.x + (a1.y - a0.y);
  if (span <= 0) {
    return [];
  }
  let n: number;
  if (mode === "COUNT") {
    if (side.count <= 1) {
      return [];
    }
    n = side.count;
  } else {
    n = Math.ceil(span / mmToPt(side.maxSpacingMm)) + 1;
  }
  const out: { x: number; y: number }[] = [];
  for (let i = 0; i < n; i++) {
    const t = i / (n - 1);
    out.push({ x: a0.x + t * (a1.x - a0.x), y: a0.y + t * (a1.y - a0.y) });
  }
  return out;
}

/**
 * All mark centres for one page, `trim` in PDF points (bottom-left origin).
 * Corner marks land at the intersection of the two enabled sides' offset
 * lines; a corner with a single enabled side gets the end mark of that side.
 */
export function computeEyeletMarks(
  mode: EyeletMarkMode,
  trim: BoxRect,
  sides: Record<EyeletSide, EyeletSideGeometry>,
): EyeletMarkPoint[] {
  const on = Object.fromEntries(
    EYELET_SIDES.map((s) => [s, enabled(mode, sides[s])]),
  ) as Record<EyeletSide, boolean>;
  if (!EYELET_SIDES.some((s) => on[s])) {
    return [];
  }

  const lx = trim.x;
  const ly = trim.y;
  const rx = trim.x + trim.width;
  const ty = trim.y + trim.height;

  // The along-axis interval is shortened by the enabled neighbours' offsets so
  // every side's endpoints land exactly on the shared corner marks.
  const x0 = on.left ? lx + mmToPt(sides.left.offsetMm) : lx;
  const x1 = on.right ? rx - mmToPt(sides.right.offsetMm) : rx;
  const y0 = on.bottom ? ly + mmToPt(sides.bottom.offsetMm) : ly;
  const y1 = on.top ? ty - mmToPt(sides.top.offsetMm) : ty;

  const dedup = new Map<string, EyeletMarkPoint>();
  const put = (x: number, y: number) => {
    const key = `${Math.round(x * 1000)}:${Math.round(y * 1000)}`;
    const existing = dedup.get(key);
    if (existing) {
      existing.corner = true;
    } else {
      dedup.set(key, { x, y, corner: false });
    }
  };

  if (on.top) {
    const y = ty - mmToPt(sides.top.offsetMm);
    for (const p of sidePoints(mode, sides.top, { x: x0, y }, { x: x1, y })) {
      put(p.x, p.y);
    }
  }
  if (on.right) {
    const x = rx - mmToPt(sides.right.offsetMm);
    for (const p of sidePoints(mode, sides.right, { x, y: y0 }, { x, y: y1 })) {
      put(p.x, p.y);
    }
  }
  if (on.bottom) {
    const y = ly + mmToPt(sides.bottom.offsetMm);
    for (const p of sidePoints(
      mode,
      sides.bottom,
      { x: x0, y },
      { x: x1, y },
    )) {
      put(p.x, p.y);
    }
  }
  if (on.left) {
    const x = lx + mmToPt(sides.left.offsetMm);
    for (const p of sidePoints(mode, sides.left, { x, y: y0 }, { x, y: y1 })) {
      put(p.x, p.y);
    }
  }
  return [...dedup.values()];
}
