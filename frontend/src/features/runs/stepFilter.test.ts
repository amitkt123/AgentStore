import { stepsForNode } from "./stepFilter";
import type { FlowNode } from "@/features/flow/layout";
import type { RunStep } from "@/lib/api/types";

const step = (kind: string, detail = "{}"): RunStep => ({ kind: kind as RunStep["kind"], detail, createdAt: "2026-09-29T10:05:00Z" });
const node = (id: string, stage: FlowNode["stage"]): FlowNode => ({ id, kind: stage, stage, title: "", body: "", state: "DONE", metric: null, undeclared: false, derived: false });

it("matches steps to a node by kind", () => {
  const steps = [step("DATA_READ"), step("LLM_CALL"), step("GATE_REQUESTED"), step("AGENT_LOG")];
  expect(stepsForNode(steps, node("read:bank_statement", "Reads")).map((s) => s.kind)).toEqual(["DATA_READ"]);
  expect(stepsForNode(steps, node("gate:post_entries", "Asks you")).map((s) => s.kind)).toEqual(["GATE_REQUESTED"]);
  expect(stepsForNode(steps, node("llm", "Thinks")).map((s) => s.kind)).toEqual(["LLM_CALL"]);
});

it("narrows by resource when the detail names one", () => {
  const named = step("DATA_READ", '{"resource":"bank_statement"}');
  const plain = step("DATA_READ", "plain");
  expect(stepsForNode([named, plain], node("read:bank_statement", "Reads"))).toEqual([named, plain]);
  expect(stepsForNode([named, plain], node("read:other", "Reads"))).toEqual([plain]);
});

it("matches a derived post node by its resource", () => {
  const w = step("WRITE_STAGED", '{"resource":"ledger"}');
  expect(stepsForNode([w], node("post:ledger", "Writes"))).toEqual([w]);
});
