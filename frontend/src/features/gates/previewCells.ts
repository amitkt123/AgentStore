import { formatWhen } from "@/lib/format";
import type { GateDetail } from "@/lib/api/types";

const NUMERIC = /^-?\d+(\.\d+)?$/;

/** One preview cell as shown on an approval: numbers grouped the Indian way and right-aligned, blanks for missing values. */
export function cell(value: unknown): { text: string; numeric: boolean } {
  if (value === null || value === undefined) return { text: "", numeric: false };
  const n = typeof value === "number" ? value : typeof value === "string" && NUMERIC.test(value) ? Number(value) : null;
  if (n === null || !Number.isFinite(n)) return { text: String(value), numeric: false };
  return { text: n.toLocaleString("en-IN", { maximumFractionDigits: 2 }), numeric: true };
}

/** Who decided a gate, from the viewer's side: "you" only when the viewer is the one who decided. */
export function decisionText(g: GateDetail, viewerUserId?: string): string {
  const verb = g.status === "APPROVED" ? "approved" : "rejected";
  const when = g.decidedAt ? ` at ${formatWhen(g.decidedAt)}` : "";
  if (viewerUserId && g.decidedByUserId === viewerUserId) return `You ${verb} this${when}.`;
  const via = g.decidedVia === "EMAIL_LINK" ? " by email link" : "";
  return `${verb[0].toUpperCase()}${verb.slice(1)}${when}${via}.`;
}
