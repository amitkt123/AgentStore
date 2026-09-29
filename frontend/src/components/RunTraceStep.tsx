import type { RunStepResponse } from "@/lib/types";

function parseDetail(raw: string): { summary?: string; message?: string } {
  try {
    return JSON.parse(raw) as { summary?: string; message?: string };
  } catch {
    return {};
  }
}

const KIND_LABEL: Record<RunStepResponse["kind"], string> = {
  READ: "Read",
  WRITE: "Wrote",
  GATE: "Needs your decision",
  COST: "Charged",
  COMPLETE: "Completed",
  FAILED: "Failed",
  TIMEOUT: "Stopped by platform",
  COST_CAP_REACHED: "Spend limit reached",
  DATA_READ: "Read",
  DATA_WRITE: "Wrote",
  WRITE_STAGED: "Drafted, waiting for approval",
  GATE_REQUESTED: "Needs your decision",
  GATE_DECIDED: "Decision recorded",
  LLM_CALL: "Asked the AI model",
  USAGE: "Reported usage",
  SCOPE_VIOLATION: "Blocked: outside its permissions",
  AGENT_LOG: "Reported by the agent",
  CANCELLED: "Cancelled",
  NETWORK_BLOCKED: "Blocked an outside connection",
};

export function RunTraceStep({ step }: { step: RunStepResponse }) {
  const detail = parseDetail(step.detail);
  const isGate = step.kind === "GATE" || step.kind === "GATE_REQUESTED";
  return (
    <div className={`relative pl-6 pb-5 border-l-2 ${isGate ? "border-amber" : "border-line"}`}>
      <div className="text-xs text-muted">{new Date(step.createdAt).toLocaleTimeString()}</div>
      <div className="font-medium">{KIND_LABEL[step.kind]}</div>
      <div className="text-sm text-ink2">{detail.summary ?? detail.message ?? step.detail}</div>
    </div>
  );
}
