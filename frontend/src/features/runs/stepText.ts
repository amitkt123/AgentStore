import type { RunStep } from "@/lib/api/types";

const TEXT: Record<string, string> = {
  GATE_REQUESTED: "Asked for your approval", GATE_DECIDED: "Approval answered", GATE: "Approval step",
  COMPLETE: "Run finished", FAILED: "The agent reported a failure", TIMEOUT: "Run timed out",
  COST_CAP_REACHED: "Reached the spend limit", SCOPE_VIOLATION: "Tried something it did not declare",
  NETWORK_BLOCKED: "A website request was blocked", LLM_CALL: "Asked the AI model",
  DATA_READ: "Read your data", READ: "Read your data",
  DATA_WRITE: "Prepared a write", WRITE: "Prepared a write", WRITE_STAGED: "Prepared a write",
  COST: "Charge recorded", USAGE: "Charge recorded", CANCELLED: "Run cancelled", AGENT_LOG: "Agent note",
};

export const stepText = (s: RunStep): string => TEXT[s.kind] ?? s.kind.toLowerCase().replace(/_/g, " ");
