import type { GateDetail, RunStep } from "@/lib/api/types";
import { stepNote, stepText } from "@/features/runs/stepText";
import { formatMoney, formatTime, formatWhen, humanize } from "@/lib/format";

export type Lane = "Agent" | "Your data" | "You" | "Money" | "Blocked" | "Run";
export const LANES: Lane[] = ["Agent", "Your data", "You", "Money", "Blocked", "Run"];
export type Tone = "done" | "waiting" | "bad" | "ghost";
export interface ReplayBlock {
  id: string; lane: Lane; beat: number; time: string | null;
  label: string; title: string; detail: string | null; metric: string | null; tone: Tone;
  facts: [string, string][];
  /** Set on an approval block that matched a gate record. */
  gateId?: string;
}
export type EdgeLabel = "Approved" | "Rejected" | "No answer";
export interface ReplayEdge { from: string; to: string; label: EdgeLabel | null; ghost: boolean }
export interface ReplayGraph { lanes: Lane[]; blocks: ReplayBlock[]; edges: ReplayEdge[]; beats: number }

const LANE_OF: Record<string, [Lane, string]> = {
  AGENT_LOG: ["Agent", "Agent note"], LLM_CALL: ["Agent", "Model call"],
  DATA_READ: ["Your data", "Read"], READ: ["Your data", "Read"],
  WRITE_STAGED: ["Your data", "Draft"], DATA_WRITE: ["Your data", "Write"], WRITE: ["Your data", "Write"],
  GATE_REQUESTED: ["You", "Asks you"], GATE: ["You", "Asks you"],
  USAGE: ["Money", "Charge"], COST: ["Money", "Charge"], COST_CAP_REACHED: ["Money", "Charge"],
  SCOPE_VIOLATION: ["Blocked", "Blocked"], NETWORK_BLOCKED: ["Blocked", "Blocked"],
  COMPLETE: ["Run", "End"], FAILED: ["Run", "End"], TIMEOUT: ["Run", "End"], CANCELLED: ["Run", "End"],
};
const BAD = new Set(["FAILED", "TIMEOUT", "CANCELLED", "COST_CAP_REACHED", "SCOPE_VIOLATION", "NETWORK_BLOCKED"]);
const OUTCOME_WORD = { APPROVED: "Approved", REJECTED: "Rejected", EXPIRED: "Expired" } as const;

type Detail = Record<string, unknown>;
const parse = (s: RunStep): Detail => {
  try { const v: unknown = JSON.parse(s.detail); return v && typeof v === "object" ? (v as Detail) : {}; } catch { return {}; }
};
const str = (v: unknown): string | null => (typeof v === "string" && v ? v : null);
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);
const ms = (iso: string | null | undefined): number | null => { if (!iso) return null; const t = Date.parse(iso); return Number.isNaN(t) ? null : t; };

/** The gate a step belongs to: its action, else its block (legacy "gate:<action>"). */
const gateKey = (s: RunStep, d: Detail): string | null => str(d.action) ?? (s.block ? s.block.replace(/^gate:/, "") : null);

type Outcome = "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED";
interface Item { block: ReplayBlock; start: number | null; end: number | null; outcome?: Outcome; stagedResource?: string | null }

/** Internal keys never shown as facts: raw ids and plumbing. */
const INTERNAL = new Set(["block", "source"]);
const internal = (k: string) => INTERNAL.has(k) || k === "id" || /Id$/.test(k);

const factsOf = (s: RunStep, d: Detail, time: string | null): [string, string][] => {
  const facts: [string, string][] = [["Step", stepText(s)]];
  if (time) facts.push(["Time", time]);
  for (const [k, v] of Object.entries(d)) {
    if (v === null || v === undefined || typeof v === "object" || internal(k)) continue;
    facts.push([humanize(k), String(v)]);
  }
  return facts;
};

export function buildReplay(steps: RunStep[], gates: GateDetail[], { canDecide = true }: { canDecide?: boolean } = {}): ReplayGraph {
  const consumed = new Set<number>();
  const items: Item[] = [];
  let lastStaged: string | null = null;

  steps.forEach((s, i) => {
    if (consumed.has(i)) return;
    const d = parse(s);
    const id = s.id ?? `step-${i}`;
    const [lane, label] = LANE_OF[s.kind] ?? ["Agent", "Agent note"];
    const time = s.createdAt ? formatWhen(s.createdAt) : null;
    const start = ms(s.startedAt), end = ms(s.createdAt);
    const tone: Tone = BAD.has(s.kind) ? "bad" : "done";
    let block: ReplayBlock = { id, lane, beat: 0, time, label, title: stepText(s), detail: null, metric: null, tone, facts: factsOf(s, d, time) };

    if (s.kind === "GATE_REQUESTED" || s.kind === "GATE") {
      const key = gateKey(s, d);
      let decided: { step: RunStep; d: Detail } | null = null;
      for (let j = i + 1; j < steps.length; j++) {
        const t = steps[j];
        if (consumed.has(j)) continue;
        const td = parse(t);
        if (t.kind === "GATE_REQUESTED" && gateKey(t, td) === key) break;
        if (t.kind === "GATE_DECIDED" && gateKey(t, td) === key) { decided = { step: t, d: td }; consumed.add(j); break; }
      }
      const gateId = str(d.gateId);
      const match = gateId ? gates.find((g) => g.gateId === gateId) : key ? gates.filter((g) => g.action === key).sort((a, b) => (ms(b.decidedAt) ?? 0) - (ms(a.decidedAt) ?? 0))[0] : undefined;
      const dec = str(decided?.d.decision);
      // No decision step and no gate record (a legacy agent-reported GATE): an ask with no known outcome, never "waiting".
      const outcome: Outcome | undefined = dec === "APPROVED" || dec === "REJECTED" || dec === "EXPIRED" ? dec : match?.status;
      const decidedIso = decided?.step.createdAt ?? match?.decidedAt ?? (outcome === "EXPIRED" ? match?.expiresAt ?? null : null);
      block = { ...block, title: str(d.summary) ?? stepText(s), tone: outcome === "PENDING" ? "waiting" : "done", gateId: match?.gateId };
      if (outcome === "PENDING") block.detail = canDecide && match?.viewerCanDecide ? "Waiting for you" : "Waiting for an approver";
      else if (outcome) {
        block.detail = `${OUTCOME_WORD[outcome]} at ${decidedIso ? formatTime(decidedIso) : "–"}`;
        const a = ms(s.createdAt), b = ms(decidedIso);
        if (a !== null && b !== null) block.metric = `waited ${Math.max(0, Math.round((b - a) / 60000))} min`;
        block.facts.push(["Outcome", OUTCOME_WORD[outcome]]);
        if (decidedIso) block.facts.push(["Decided", formatWhen(decidedIso)]);
      }
      const note = str(decided?.d.note) ?? match?.note;
      if (note) block.facts.push(["Note", note]);
      items.push({ block, start: null, end: end, outcome, stagedResource: lastStaged });
      return;
    }
    if (s.kind === "GATE_DECIDED") { // a decision with no request before it: keep it visible
      block = { ...block, detail: str(d.decision) ? humanize(String(d.decision)) : null };
    } else if (s.kind === "AGENT_LOG") {
      block.detail = stepNote(s);
    } else if (lane === "Your data") {
      const resource = str(d.resource);
      if (resource) block.detail = humanize(resource);
      const count = num(d.count);
      if (count !== null) block.metric = `${count} rows`;
      if (s.kind === "WRITE_STAGED") lastStaged = resource;
    } else if (s.kind === "LLM_CALL") {
      block.detail = str(d.model);
      const tokens = num(d.tokens);
      if (tokens !== null) block.metric = `${tokens} tokens`;
    } else if (lane === "Money") {
      const amount = num(d.amount);
      if (amount !== null) block.metric = formatMoney(amount);
    }
    items.push({ block, start, end });
  });

  // Beats: chained overlap of [startedAt, createdAt] spans; a step with no startedAt never overlaps.
  const beats: Item[][] = [];
  let cur: Item[] = [];
  for (const it of items) {
    const joins = it.start !== null && it.end !== null && cur.some((o) => o.start !== null && o.end !== null && it.start! < o.end && o.start < it.end!);
    if (!joins && cur.length) { beats.push(cur); cur = []; }
    cur.push(it);
  }
  if (cur.length) beats.push(cur);

  const blocks: ReplayBlock[] = [];
  const edges: ReplayEdge[] = [];
  const ghosts: Record<number, ReplayBlock[]> = {};
  const byBeat: ReplayBlock[][] = beats.map((b, n) => b.map((it) => { it.block.beat = n; return it.block; }));

  beats.forEach((b, n) => {
    for (const it of b) {
      if (!it.outcome || it.outcome === "PENDING") continue;
      const approved = it.outcome === "APPROVED";
      const ghost: ReplayBlock = {
        id: `ghost:${it.block.id}`, lane: "You", beat: n + 1, time: null, label: "Not taken", tone: "ghost", metric: null, facts: [],
        title: approved ? "Stops here" : "Would have posted",
        detail: approved ? "Nothing is posted" : it.stagedResource ? humanize(it.stagedResource) : "your data",
      };
      (ghosts[n + 1] ??= []).push(ghost);
    }
  });
  const total = Math.max(beats.length, ...Object.keys(ghosts).map((k) => Number(k) + 1));
  const gateOutcome = new Map<string, Outcome>();
  beats.forEach((b) => b.forEach((it) => { if (it.outcome) gateOutcome.set(it.block.id, it.outcome); }));

  for (let n = 0; n < total; n++) {
    const real = byBeat[n] ?? [];
    const here = [...real, ...(ghosts[n] ?? [])];
    blocks.push(...here);
    if (n === 0) continue;
    for (const from of byBeat[n - 1] ?? []) {
      const oc = gateOutcome.get(from.id);
      const decided = oc && oc !== "PENDING";
      const word = (o: Outcome): EdgeLabel => (o === "APPROVED" ? "Approved" : o === "EXPIRED" ? "No answer" : "Rejected");
      for (const to of real) edges.push({ from: from.id, to: to.id, label: decided ? word(oc) : null, ghost: false });
      const gh = (ghosts[n] ?? []).find((g) => g.id === `ghost:${from.id}`);
      if (gh && decided) edges.push({ from: from.id, to: gh.id, label: oc === "APPROVED" ? "Rejected" : "Approved", ghost: true });
    }
  }

  const present = new Set(blocks.map((b) => b.lane));
  return { lanes: LANES.filter((l) => present.has(l)), blocks, edges, beats: total };
}
