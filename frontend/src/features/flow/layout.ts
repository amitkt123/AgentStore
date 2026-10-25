import { humanize } from "@/lib/format";
import type { BlockState } from "@/lib/api/types";
import type { BlockView } from "./blocks";

export type Stage = "Reads" | "Thinks" | "Drafts" | "Asks you" | "Writes" | "Charges";
export const STAGES: Stage[] = ["Reads", "Thinks", "Drafts", "Asks you", "Writes", "Charges"];
export type GateOutcome = "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED" | null;

export interface FlowNode extends BlockView { stage: Stage; derived: boolean }
export interface EndNode { id: string; gateId: string; label: "Stops here, nothing is posted"; reached: boolean }
export interface FlowEdge { from: string; to: string; kind: "main" | "approved" | "rejected" | "undeclared" }
export interface FlowGraph {
  columns: { stage: Stage; nodes: FlowNode[] }[];
  offPath: FlowNode[];
  ends: EndNode[];
  edges: FlowEdge[];
  activeTo: string | null;
}

/** gateOutcomes is keyed by gate node id (`gate:<action>`); succeeded means the run ended well. */
type Opts = { gateOutcomes?: Record<string, GateOutcome>; reachedBlock?: string | null; terminal?: boolean; succeeded?: boolean };

const stopped = (o: GateOutcome | undefined) => o === "REJECTED" || o === "EXPIRED";

/** A post is done only once every gate is approved and the run got past the write; any stopped gate skips it. */
function postState(gateIds: string[], { gateOutcomes = {}, reachedBlock = null, terminal = false, succeeded = false }: Opts): BlockState {
  const outcomes = gateIds.map((id) => gateOutcomes[id]);
  if (outcomes.some(stopped)) return "SKIPPED";
  if (outcomes.every((o) => o === "APPROVED") && (reachedBlock === "charge" || (terminal && succeeded))) return "DONE";
  return terminal ? "SKIPPED" : "IDLE";
}

/**
 * Groups blocks into the order an agent works: read, think, draft, ask, write, charge. This is a display
 * convention; the backend lists scopes, then gates. Each draft gets a derived "Posts …" node after the approval.
 */
export function layoutFlow(blocks: BlockView[], opts: Opts = {}): FlowGraph {
  const byStage = new Map<Stage, FlowNode[]>(STAGES.map((s) => [s, []]));
  const offPath: FlowNode[] = [];
  for (const b of blocks) {
    const node: FlowNode = { ...b, stage: b.kind, derived: false };
    (b.undeclared ? offPath : byStage.get(b.kind)!).push(node);
  }
  const gates = byStage.get("Asks you")!;
  if (gates.length > 0) {
    for (const d of byStage.get("Drafts")!) {
      const r = d.id.slice(d.id.indexOf(":") + 1);
      byStage.get("Writes")!.push({
        id: `post:${r}`, kind: "Writes", stage: "Writes", title: `Posts ${humanize(r).toLowerCase()}`, body: "Only after your approval.",
        state: postState(gates.map((g) => g.id), opts), metric: null, undeclared: false, derived: true,
      });
    }
  }
  const columns = STAGES.filter((s) => byStage.get(s)!.length > 0).map((stage) => ({ stage, nodes: byStage.get(stage)! }));

  const edges: FlowEdge[] = [];
  columns.forEach((col, i) => {
    const next = columns[i + 1];
    if (!next) return;
    for (const a of col.nodes) for (const b of next.nodes) edges.push({ from: a.id, to: b.id, kind: col.stage === "Asks you" ? "approved" : "main" });
  });
  const ends: EndNode[] = gates.map((g) => ({ id: `end:${g.id}`, gateId: g.id, label: "Stops here, nothing is posted", reached: stopped(opts.gateOutcomes?.[g.id]) }));
  ends.forEach((e) => edges.push({ from: e.gateId, to: e.id, kind: "rejected" }));
  for (const n of offPath) {
    const before = columns.filter((c) => STAGES.indexOf(c.stage) < STAGES.indexOf(n.stage)).pop();
    if (before) edges.push({ from: before.nodes[before.nodes.length - 1].id, to: n.id, kind: "undeclared" });
  }

  let activeTo: string | null = null;
  if (!opts.terminal) {
    const all = columns.flatMap((c) => c.nodes);
    activeTo = all.find((n) => n.state === "WAITING")?.id ?? all.find((n) => n.id === opts.reachedBlock)?.id ?? null;
  }
  return { columns, offPath, ends, edges, activeTo };
}
