import { formatMoney, humanize } from "@/lib/format";
import type { BlockState, Manifest } from "@/lib/api/types";

export type BlockKind = "Reads" | "Drafts" | "Writes" | "Asks you" | "Thinks" | "Charges";

export interface BlockView {
  id: string; kind: BlockKind; title: string; body: string; state: BlockState; metric: string | null; undeclared: boolean;
}

export function blockKind(id: string): BlockKind {
  if (id.startsWith("read:")) return "Reads";
  if (id.startsWith("draft:")) return "Drafts";
  if (id.startsWith("write:")) return "Writes";
  if (id.startsWith("gate:")) return "Asks you";
  if (id === "llm") return "Thinks";
  return "Charges";
}

const REACHED: Record<BlockKind, string> = { Reads: "Read", Drafts: "Drafted", Writes: "Wrote", "Asks you": "Asked you", Thinks: "Used AI", Charges: "Charged" };

/** The furthest block a run got to, as a short past-tense phrase for the runs list. */
export function reachedText(id: string | null): string {
  if (!id) return "–";
  const kind = blockKind(id);
  const name = id.includes(":") ? humanize(id.slice(id.indexOf(":") + 1)) : "";
  return kind === "Reads" || kind === "Drafts" || kind === "Writes" ? `${REACHED[kind]} ${name}` : REACHED[kind];
}

export function blocksFromManifest(m: Manifest): { id: string; state: "IDLE" }[] {
  const ids: string[] = [];
  for (const s of m.scopes) {
    ids.push(s.type === "READ" ? `read:${s.resource}` : `${s.requiresApproval ? "draft" : "write"}:${s.resource}`);
  }
  m.approvalGates.forEach((g) => ids.push(`gate:${g.trigger}`));
  if (m.llm) ids.push("llm");
  ids.push("charge");
  return ids.map((id) => ({ id, state: "IDLE" as const }));
}

type In = { id: string; state: BlockState; metrics?: Record<string, unknown> | null; flag?: string | null };

function metricText(kind: BlockKind, metrics: Record<string, unknown> | null | undefined): string | null {
  if (!metrics) return null;
  if (kind === "Thinks" && typeof metrics.calls === "number") return `${metrics.calls} calls, ${metrics.tokens ?? 0} tokens`;
  if (typeof metrics.count === "number") return kind === "Reads" || kind === "Drafts" || kind === "Writes" ? `${metrics.count} rows` : `${metrics.count}`;
  return null;
}

export function toBlockViews(blocks: In[], m: Manifest | null): BlockView[] {
  return blocks.map((b) => {
    const kind = blockKind(b.id);
    const name = b.id.includes(":") ? b.id.slice(b.id.indexOf(":") + 1) : "";
    const undeclared = b.flag === "UNDECLARED";
    let title = kind === "Thinks" ? "Match with AI" : kind === "Charges" ? "Charges" : humanize(name);
    let body = "";
    if (undeclared) body = "This agent did not say it would do this.";
    else if (kind === "Reads" || kind === "Drafts" || kind === "Writes") body = m?.scopes.find((s) => s.resource === name)?.description ?? "";
    else if (kind === "Asks you") { title = "Your approval"; body = m?.approvalGates.find((g) => g.trigger === name)?.description ?? "Before anything is written"; }
    else if (kind === "Thinks") body = "Uses an AI model through Karya.";
    else if (m) {
      const c = m.costEnvelope;
      body = `${formatMoney(c.unitPrice, c.currency)} per ${humanize(c.unitLabel).toLowerCase()}. Never more than ${formatMoney(c.maxPerRun, c.currency)} a run.`;
    }
    return { id: b.id, kind, title, body, state: b.state, metric: metricText(kind, b.metrics), undeclared };
  });
}
