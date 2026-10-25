export interface Box { x: number; y: number; w: number; h: number }
type Pt = { x: number; y: number };

const r = (n: number) => Math.round(n * 10) / 10;

/** True when b sits to the right of a, so the edge runs sideways rather than down. */
export const isSideways = (a: Box, b: Box): boolean => b.x >= a.x + a.w - 1;

/** Start, two control points and end of the curve from a to b: rightward when b sits to the right, else downward. */
function curve(a: Box, b: Box): [Pt, Pt, Pt, Pt] {
  if (isSideways(a, b)) {
    const s = { x: a.x + a.w, y: a.y + a.h / 2 }, e = { x: b.x, y: b.y + b.h / 2 };
    const k = Math.max(24, (e.x - s.x) / 2);
    return [s, { x: s.x + k, y: s.y }, { x: e.x - k, y: e.y }, e];
  }
  const s = { x: a.x + a.w / 2, y: a.y + a.h }, e = { x: b.x + b.w / 2, y: b.y };
  const k = Math.max(16, (e.y - s.y) / 2);
  return [s, { x: s.x, y: s.y + k }, { x: e.x, y: e.y - k }, e];
}

export function edgePath(a: Box, b: Box): string {
  const [s, c1, c2, e] = curve(a, b);
  return `M ${r(s.x)} ${r(s.y)} C ${r(c1.x)} ${r(c1.y)} ${r(c2.x)} ${r(c2.y)} ${r(e.x)} ${r(e.y)}`;
}

export function edgeMidpoint(a: Box, b: Box): Pt {
  const [s, c1, c2, e] = curve(a, b);
  return { x: r((s.x + 3 * c1.x + 3 * c2.x + e.x) / 8), y: r((s.y + 3 * c1.y + 3 * c2.y + e.y) / 8) };
}
