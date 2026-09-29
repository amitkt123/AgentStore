import type { Tone } from "@/ui/Pill";
import type { RunStatus, TerminalReason } from "@/lib/api/types";

const MAP: Record<RunStatus, { tone: Tone; text: string }> = {
  PENDING: { tone: "info", text: "Starting" },
  PENDING_BUILD: { tone: "info", text: "Preparing" },
  RUNNING: { tone: "info", text: "Running" },
  AWAITING_APPROVAL: { tone: "warn", text: "Needs you" },
  COMPLETED: { tone: "ok", text: "Finished" },
  FAILED: { tone: "bad", text: "Failed" },
  CANCELLED: { tone: "neutral", text: "Cancelled" },
  TIMED_OUT: { tone: "bad", text: "Timed out" },
  EXPIRED: { tone: "neutral", text: "Expired" },
};

export const statusPill = (s: RunStatus) => MAP[s];
export const isTerminal = (s: RunStatus) => ["COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT", "EXPIRED"].includes(s);

const REASON: Record<TerminalReason, string> = {
  FINISHED: "The run finished.",
  GATE_REJECTED: "You rejected the approval, so nothing was written and you were not charged for the write.",
  GATE_EXPIRED: "Nobody answered the approval in time, so the run stopped.",
  COST_CAP_REACHED: "The run stopped at your spend limit.",
  TIMED_OUT: "The run took too long and was stopped.",
  SCOPE_VIOLATIONS: "The agent tried to go beyond what it declared and was stopped.",
  AGENT_FAILED: "The agent reported a failure.",
  CANCELLED: "The run was cancelled.",
  SANDBOX_LOST: "Karya lost the sandbox this run was in.",
};
export const terminalText = (r: TerminalReason | null) => (r ? REASON[r] : null);
