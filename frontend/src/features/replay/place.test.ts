import { describe, expect, it } from "vitest";
import type { Lane, ReplayBlock, ReplayGraph } from "./graph";
import { BLOCK_H, BLOCK_W, COL, LANE_X, PAD, ROW, elbowPath, placeReplay } from "./place";

const block = (id: string, lane: Lane, beat: number): ReplayBlock => ({
  id, lane, beat, time: null, label: "x", title: "x", detail: null, metric: null, tone: "done", facts: [],
});
const graph = (blocks: ReplayBlock[], lanes: Lane[], beats: number): ReplayGraph => ({ lanes, blocks, edges: [], beats });
const box = (x: number, y: number) => ({ x, y, w: BLOCK_W, h: BLOCK_H });

describe("placeReplay", () => {
  it("gives each lane the height of its busiest beat", () => {
    const p = placeReplay(graph(
      [block("a", "Agent", 0), block("r1", "Your data", 1), block("r2", "Your data", 1), block("c", "Run", 2)],
      ["Agent", "Your data", "Run"], 3,
    ));
    expect(p.lanes.map((l) => l.height)).toEqual([ROW, 2 * ROW, ROW]);
    expect(p.lanes.map((l) => l.top)).toEqual([PAD, PAD + ROW, PAD + 3 * ROW]);
    expect(p.boxes.get("r2")!.y).toBe(p.boxes.get("r1")!.y + ROW);
    expect(p.height).toBe(PAD * 2 + 4 * ROW);
  });

  it("places beats left to right", () => {
    const p = placeReplay(graph([block("a", "Agent", 0), block("b", "Agent", 3)], ["Agent"], 4));
    expect(p.boxes.get("b")!.x).toBe(LANE_X + 3 * COL);
    expect(p.boxes.get("a")).toEqual({ x: LANE_X, y: PAD, w: BLOCK_W, h: BLOCK_H });
    expect(p.width).toBe(LANE_X + 4 * COL);
  });
});

describe("elbowPath", () => {
  it("draws a level arrow as one straight line", () => {
    expect(elbowPath(box(0, 0), box(240, 0)).d).toBe(`M180 46 H239`);
  });

  it("enters the next block level after turning", () => {
    const d = elbowPath(box(0, 0), box(240, 236)).d;
    expect(d.endsWith(`H239`)).toBe(true);
    expect(d).toContain("V");
  });

  it("labels beside the vertical run, or above a level line", () => {
    expect(elbowPath(box(0, 0), box(240, 0)).labelAt).toEqual({ x: 209.5, y: 38, anchor: "middle" });
    expect(elbowPath(box(0, 0), box(240, 236)).labelAt).toEqual({ x: 215.5, y: 164, anchor: "start" });
  });
});
