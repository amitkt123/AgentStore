import type { FlowNode, Stage } from "@/features/flow/layout";
import type { RunStep, RunStepKind } from "@/lib/api/types";

// Steps carry no block id, so they are matched to a node by kind (and by resource when the detail names one).
const STAGES_OF: Partial<Record<RunStepKind, Stage[]>> = {
  READ: ["Reads"], DATA_READ: ["Reads"],
  WRITE: ["Drafts", "Writes"], WRITE_STAGED: ["Drafts", "Writes"], DATA_WRITE: ["Drafts", "Writes"],
  GATE: ["Asks you"], GATE_REQUESTED: ["Asks you"], GATE_DECIDED: ["Asks you"],
  LLM_CALL: ["Thinks"],
  COST: ["Charges"], USAGE: ["Charges"], COST_CAP_REACHED: ["Charges"],
};

function resourceOf(detail: string): string | null {
  try {
    const v: unknown = JSON.parse(detail);
    return v && typeof v === "object" && typeof (v as { resource?: unknown }).resource === "string" ? (v as { resource: string }).resource : null;
  } catch {
    return null;
  }
}

export function stepsForNode(steps: RunStep[], node: FlowNode): RunStep[] {
  const resource = node.id.includes(":") ? node.id.slice(node.id.indexOf(":") + 1) : null;
  return steps.filter((s) => {
    if (!STAGES_OF[s.kind]?.includes(node.stage)) return false;
    const named = resourceOf(s.detail);
    return named === null || resource === null || named === resource;
  });
}
