import type { Box } from "@/features/flow/geometry";
import type { Lane, ReplayGraph } from "./graph";

export const COL = 240, ROW = 118, BLOCK_W = 180, BLOCK_H = 92, LANE_X = 110, PAD = 14;

export interface Placed {
  boxes: Map<string, Box>;
  lanes: { lane: Lane; top: number; height: number }[];
  width: number;
  height: number;
}

/** Column = beat; each lane is as tall as its busiest beat, stacked top to bottom. */
export function placeReplay(g: ReplayGraph): Placed {
  const boxes = new Map<string, Box>();
  const lanes: Placed["lanes"] = [];
  let row = 0;
  for (const lane of g.lanes) {
    const seen = new Map<number, number>(); // beat -> blocks placed so far in this lane
    let rows = 1;
    for (const b of g.blocks) {
      if (b.lane !== lane) continue;
      const i = seen.get(b.beat) ?? 0;
      seen.set(b.beat, i + 1);
      rows = Math.max(rows, i + 1);
      boxes.set(b.id, { x: LANE_X + b.beat * COL, y: PAD + (row + i) * ROW, w: BLOCK_W, h: BLOCK_H });
    }
    lanes.push({ lane, top: PAD + row * ROW, height: rows * ROW });
    row += rows;
  }
  return { boxes, lanes, width: LANE_X + g.beats * COL, height: PAD * 2 + row * ROW };
}

/** Elbow from a's right edge to b's left edge (the mockup's edgePath). */
export function elbowPath(a: Box, b: Box): { d: string; labelAt: { x: number; y: number; anchor: "middle" | "start" } } {
  const x1 = a.x + a.w, y1 = a.y + a.h / 2, x2 = b.x - 1, y2 = b.y + b.h / 2;
  if (Math.abs(y1 - y2) < 2) return { d: `M${x1} ${y1} H${x2}`, labelAt: { x: (x1 + x2) / 2, y: y1 - 8, anchor: "middle" } };
  const mx = x1 + (x2 - x1) / 2, dir = Math.sign(y2 - y1);
  const rr = Math.min(10, Math.abs(y2 - y1) / 2, (x2 - x1) / 2 - 2);
  return {
    d: `M${x1} ${y1} H${mx - rr} Q${mx} ${y1} ${mx} ${y1 + dir * rr} V${y2 - dir * rr} Q${mx} ${y2} ${mx + rr} ${y2} H${x2}`,
    labelAt: { x: mx + 6, y: (y1 + y2) / 2, anchor: "start" },
  };
}
