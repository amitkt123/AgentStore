import { layoutBars } from "./timeline";
import type { RunListItem } from "@/lib/api/types";

const run = (id: string, agent: string, start: string, end: string | null): RunListItem => ({
  id, displayId: id, agent, version: "1", status: "COMPLETED", terminalReason: null, reachedBlock: null,
  startedAt: start, completedAt: end, charged: 0, held: 0, decidedBy: null,
});

const now = new Date("2026-09-29T10:30:00").getTime();

it("puts each agent in its own lane and scales bars to the window", () => {
  const out = layoutBars(
    [run("R1", "A", "2026-09-29T08:00:00", "2026-09-29T09:00:00"), run("R2", "B", "2026-09-29T09:00:00", null)],
    now,
  );
  expect(out.startHour).toBe(8);
  expect(out.endHour).toBe(11);
  expect(out.lanes.map((l) => l.agent)).toEqual(["A", "B"]);
  expect(out.lanes[0].bars[0].leftPct).toBeCloseTo(0);
  expect(out.lanes[0].bars[0].widthPct).toBeCloseTo(33.33, 1);
  expect(out.lanes[1].bars[0].leftPct).toBeCloseTo(33.33, 1);
});

it("ignores runs from other days and runs with no start", () => {
  const out = layoutBars([run("R1", "A", "2026-09-28T08:00:00", "2026-09-28T09:00:00"), { ...run("R2", "A", "x", null), startedAt: null }], now);
  expect(out.lanes).toEqual([]);
});

it("keeps a very short run visible", () => {
  const out = layoutBars([run("R1", "A", "2026-09-29T08:00:00", "2026-09-29T08:00:05")], now);
  expect(out.lanes[0].bars[0].widthPct).toBeGreaterThanOrEqual(1.5);
});
