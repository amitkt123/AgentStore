import { describe, expect, it } from "vitest";
import { buildReplay } from "./graph";
import { formatMoney } from "@/lib/format";
import type { GateDetail, RunStep, RunStepKind } from "@/lib/api/types";

const at = (s: number) => new Date(Date.UTC(2026, 9, 25, 10, 0, s)).toISOString();
const step = (kind: RunStepKind, detail: object, start: number | null, end: number, id?: string): RunStep =>
  ({ id: id ?? `${kind}-${end}`, kind, detail: JSON.stringify(detail), startedAt: start === null ? null : at(start), createdAt: at(end) });
const gate = (action: string, status: GateDetail["status"], decidedAt: string | null = null, gateId = `g-${action}`): GateDetail =>
  ({ gateId, action, summary: "", payload: null, status, expiresAt: at(900), estimatedUnits: null, costIfApproved: null, preview: null, decidedVia: null, decidedByUserId: null, decidedAt, note: null });

describe("buildReplay", () => {
  it("puts each step in its lane, one beat per step when nothing overlaps", () => {
    const g = buildReplay([step("AGENT_LOG", { message: "Reading" }, null, 1), step("DATA_READ", { resource: "expenses", count: 5 }, 2, 3), step("USAGE", { amount: 5 }, null, 4), step("COMPLETE", {}, null, 5)], []);
    expect(g.lanes).toEqual(["Agent", "Your data", "Money", "Run"]);
    expect(g.blocks.map((b) => [b.lane, b.beat])).toEqual([["Agent", 0], ["Your data", 1], ["Money", 2], ["Run", 3]]);
    expect(g.blocks[1]).toMatchObject({ title: "Read your data", detail: "Expenses", metric: "5 rows", tone: "done" });
    expect(g.edges.map((e) => `${e.from}>${e.to}`)).toHaveLength(3);
  });

  it("puts overlapping calls in one beat, forking into it and joining out of it", () => {
    const g = buildReplay([step("AGENT_LOG", {}, null, 1, "n"), step("DATA_READ", { resource: "bank" }, 2, 6, "a"), step("DATA_READ", { resource: "ledger" }, 3, 5, "b"), step("LLM_CALL", { model: "m", tokens: 9 }, 7, 8, "t")], []);
    expect(g.blocks.find((b) => b.id === "b")!.beat).toBe(1);
    expect(g.edges.map((e) => `${e.from}>${e.to}`).sort()).toEqual(["a>t", "b>t", "n>a", "n>b"]);
  });

  it("merges an approved gate and draws the rejected path as not taken", () => {
    const g = buildReplay([step("WRITE_STAGED", { resource: "ledger", count: 2 }, 1, 2, "d"), step("GATE_REQUESTED", { action: "post", summary: "Post 2 entries?" }, null, 3, "q"), step("GATE_DECIDED", { action: "post", decision: "APPROVED" }, null, 183, "a"), step("DATA_WRITE", { resource: "ledger", count: 2 }, 184, 185, "w")], []);
    const gt = g.blocks.find((b) => b.label === "Asks you")!;
    expect(gt).toMatchObject({ title: "Post 2 entries?", metric: "waited 3 min" });
    expect(g.blocks.filter((b) => b.label === "Asks you")).toHaveLength(1);
    expect(g.edges.find((e) => e.to === "w")!.label).toBe("Approved");
    expect(g.blocks.find((b) => b.tone === "ghost")).toMatchObject({ title: "Stops here", detail: "Nothing is posted", beat: g.blocks.find((b) => b.id === "w")!.beat });
    expect(g.edges.find((e) => e.ghost)).toMatchObject({ from: gt.id, label: "Rejected" });
  });

  it("after a rejection the not-taken path names what would have been posted", () => {
    const g = buildReplay([step("WRITE_STAGED", { resource: "ledger", count: 2 }, 1, 2, "d"), step("GATE_REQUESTED", { action: "post", summary: "Post?" }, null, 3, "q"), step("GATE_DECIDED", { action: "post", decision: "REJECTED" }, null, 60, "r"), step("FAILED", {}, null, 61, "f")], []);
    expect(g.blocks.find((b) => b.tone === "ghost")).toMatchObject({ title: "Would have posted", detail: "Ledger", lane: "You", label: "Not taken" });
    expect(g.edges.find((e) => e.to === "f")!.label).toBe("Rejected");
    expect(g.edges.find((e) => e.ghost)!.label).toBe("Approved");
  });

  it("takes an expired gate's outcome from the gates list and labels the path taken as no answer", () => {
    const g = buildReplay([step("GATE_REQUESTED", { action: "post", summary: "Post?" }, null, 3, "q"), step("FAILED", {}, null, 700, "f")], [gate("post", "EXPIRED", at(600))]);
    expect(g.blocks.find((b) => b.id === "q")!.detail).toMatch(/^Expired/);
    expect(g.edges.find((e) => e.to === "f")!.label).toBe("No answer");
    expect(g.edges.find((e) => e.ghost)!.label).toBe("Approved");
  });

  it("never shows a gate with no record or decision as waiting (legacy agent-reported GATE)", () => {
    for (const kind of ["GATE", "GATE_REQUESTED"] as const) {
      const g = buildReplay([step(kind, {}, null, 3, "q"), step("COMPLETE", {}, null, 300, "e")], []);
      const ask = g.blocks.find((b) => b.id === "q")!;
      expect(ask).toMatchObject({ lane: "You", tone: "done", detail: null });
      expect(g.blocks.some((b) => b.tone === "ghost")).toBe(false);
      expect(g.edges).toEqual([{ from: "q", to: "e", label: null, ghost: false }]);
    }
  });

  it("shows a pending gate as waiting, with no fork yet", () => {
    const g = buildReplay([step("GATE_REQUESTED", { action: "post", summary: "Post?" }, null, 3, "q")], [gate("post", "PENDING")]);
    expect(g.blocks).toHaveLength(1);
    expect(g.blocks[0]).toMatchObject({ tone: "waiting", detail: "Waiting for you" });
    expect(g.blocks.some((b) => b.tone === "ghost")).toBe(false);
    expect(g.edges).toEqual([]);
  });

  it("pairs each request of a re-asked gate with its own decision", () => {
    const g = buildReplay([
      step("GATE_REQUESTED", { action: "post", summary: "One?" }, null, 1, "q1"), step("GATE_DECIDED", { action: "post", decision: "REJECTED" }, null, 2, "d1"),
      step("GATE_REQUESTED", { action: "post", summary: "Two?" }, null, 3, "q2"), step("GATE_DECIDED", { action: "post", decision: "APPROVED" }, null, 4, "d2"),
    ], []);
    const asks = g.blocks.filter((b) => b.label === "Asks you");
    expect(asks).toHaveLength(2);
    expect(asks[0].detail).toMatch(/^Rejected/);
    expect(asks[1].detail).toMatch(/^Approved/);
  });

  it("matches a re-asked gate to its own record by gateId, so a pending second ask still waits", () => {
    const g = buildReplay([
      step("GATE_REQUESTED", { gateId: "g1", action: "post", summary: "One?" }, null, 1, "q1"), step("GATE_DECIDED", { gateId: "g1", action: "post", decision: "REJECTED" }, null, 2, "d1"),
      step("GATE_REQUESTED", { gateId: "g2", action: "post", summary: "Two?" }, null, 3, "q2"),
    ], [gate("post", "REJECTED", at(2), "g1"), gate("post", "PENDING", null, "g2")]);
    const asks = g.blocks.filter((b) => b.label === "Asks you");
    expect(asks[1]).toMatchObject({ tone: "waiting", detail: "Waiting for you" });
    expect(g.blocks.filter((b) => b.tone === "ghost")).toHaveLength(1);
  });

  it("orders steps with no times by list order, one per beat, time blank", () => {
    const mk = (kind: RunStepKind): RunStep => ({ kind, detail: "{}", createdAt: null });
    const g = buildReplay([mk("AGENT_LOG"), mk("LLM_CALL"), mk("COMPLETE")], []);
    expect(g.blocks.map((b) => b.beat)).toEqual([0, 1, 2]);
    expect(g.blocks.every((b) => b.time === null)).toBe(true);
    expect(g.beats).toBe(3);
  });

  it("keeps a step of an unknown kind, in the Agent lane", () => {
    const g = buildReplay([{ kind: "SOMETHING_NEW" as RunStepKind, detail: "{}", createdAt: at(1) }], []);
    expect(g.blocks).toHaveLength(1);
    expect(g.blocks[0]).toMatchObject({ lane: "Agent", title: "something new" });
  });

  it("marks failures and blocked attempts as bad, in their lanes", () => {
    const g = buildReplay([step("SCOPE_VIOLATION", {}, null, 1), step("TIMEOUT", {}, null, 2)], []);
    expect(g.blocks[0]).toMatchObject({ lane: "Blocked", tone: "bad" });
    expect(g.blocks[1]).toMatchObject({ lane: "Run", tone: "bad" });
  });

  it("states the charge amount", () => {
    const g = buildReplay([step("USAGE", { amount: 12 }, null, 1)], []);
    expect(g.blocks[0].metric).toBe(formatMoney(12));
  });

  it("keeps internal ids out of a step's facts", () => {
    const g = buildReplay([step("DATA_READ", { gateId: "g-123", runId: "r-456", stepId: "s-1", id: "x-1", block: "gate:post", source: "sdk", resource: "ledger" }, null, 1, "r")], []);
    const facts = g.blocks[0].facts;
    for (const v of ["g-123", "r-456", "s-1", "x-1", "gate:post", "sdk"]) expect(facts.some(([, f]) => f === v)).toBe(false);
    expect(facts).toContainEqual(["Resource", "ledger"]);
  });
});
