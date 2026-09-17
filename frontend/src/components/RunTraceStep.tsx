import type { RunStepResponse } from "@/lib/types";

function parseDetail(raw: string): { summary?: string } {
  try {
    return JSON.parse(raw) as { summary?: string };
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
};

export function RunTraceStep({ step }: { step: RunStepResponse }) {
  const detail = parseDetail(step.detail);
  const isGate = step.kind === "GATE";
  return (
    <div className={`relative pl-6 pb-5 border-l-2 ${isGate ? "border-amber" : "border-line"}`}>
      <div className="text-xs text-muted">{new Date(step.createdAt).toLocaleTimeString()}</div>
      <div className="font-medium">{KIND_LABEL[step.kind]}</div>
      <div className="text-sm text-ink2">{detail.summary ?? step.detail}</div>
    </div>
  );
}
