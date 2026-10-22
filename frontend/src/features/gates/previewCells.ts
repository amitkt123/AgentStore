import { formatWhen } from "@/lib/format";
import type { GateDetail } from "@/lib/api/types";

const DECIMAL = /^-?\d+\.\d+$/;
const WHOLE = /^-?\d+$/;
const MONEY_COLUMN = /amount|debit|credit|total|price|cost|balance|fee|tax/i;
const MAX_DIGITS = 15;

/**
 * One preview cell as shown on an approval. Amounts are grouped the Indian way and right-aligned; identifiers
 * (account numbers, invoice numbers, years) keep their digits as written. Blanks for missing values.
 */
export function cell(value: unknown, column?: string): { text: string; numeric: boolean } {
  if (value === null || value === undefined) return { text: "", numeric: false };
  const money = column === undefined || MONEY_COLUMN.test(column);
  if (typeof value === "number") {
    if (!Number.isFinite(value)) return { text: String(value), numeric: false };
    const grouped = money || !Number.isInteger(value);
    return { text: grouped ? value.toLocaleString("en-IN", { maximumFractionDigits: 2 }) : String(value), numeric: true };
  }
  if (typeof value === "string" && value.replace(/\D/g, "").length <= MAX_DIGITS
      && (DECIMAL.test(value) || (WHOLE.test(value) && column !== undefined && MONEY_COLUMN.test(column)))) {
    return { text: Number(value).toLocaleString("en-IN", { maximumFractionDigits: 2 }), numeric: true };
  }
  return { text: String(value), numeric: false };
}

/** Who decided a gate, from the viewer's side: "you" only when the viewer is the one who decided. */
export function decisionText(g: GateDetail, viewerUserId?: string): string {
  const verb = g.status === "APPROVED" ? "approved" : "rejected";
  const when = g.decidedAt ? ` at ${formatWhen(g.decidedAt)}` : "";
  if (viewerUserId && g.decidedByUserId === viewerUserId) return `You ${verb} this${when}.`;
  const via = g.decidedVia === "EMAIL_LINK" ? " by email link" : "";
  return `${verb[0].toUpperCase()}${verb.slice(1)}${when}${via}.`;
}
