import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { RunTraceStep } from "./RunTraceStep";
import type { RunStepResponse } from "@/lib/types";

describe("RunTraceStep", () => {
  it("renders a human label for each step kind", () => {
    const step: RunStepResponse = { kind: "GATE", detail: '{"summary":"Needs approval above threshold"}', createdAt: "2026-09-16T09:58:12Z" };
    render(<RunTraceStep step={step} />);
    expect(screen.getByText("Needs your decision")).toBeInTheDocument();
    expect(screen.getByText("Needs approval above threshold")).toBeInTheDocument();
  });

  it("falls back to the raw detail string when it is not JSON", () => {
    const step: RunStepResponse = { kind: "READ", detail: "not json", createdAt: "2026-09-16T09:41:02Z" };
    render(<RunTraceStep step={step} />);
    expect(screen.getByText("not json")).toBeInTheDocument();
  });

  it("labels platform-recorded stops", () => {
    render(<RunTraceStep step={{ kind: "COST_CAP_REACHED", detail: '{"summary":"Spend limit reached; run stopped"}', createdAt: "2026-09-23T10:00:00Z" }} />);
    expect(screen.getByText("Spend limit reached")).toBeInTheDocument();
    expect(screen.getByText("Spend limit reached; run stopped")).toBeInTheDocument();
  });

  it("labels the steps the runtime API records", () => {
    const cases: [RunStepResponse["kind"], string][] = [
      ["DATA_READ", "Read"],
      ["DATA_WRITE", "Wrote"],
      ["WRITE_STAGED", "Drafted, waiting for approval"],
      ["GATE_REQUESTED", "Needs your decision"],
      ["GATE_DECIDED", "Decision recorded"],
      ["LLM_CALL", "Asked the AI model"],
      ["USAGE", "Reported usage"],
      ["SCOPE_VIOLATION", "Blocked: outside its permissions"],
    ];
    for (const [kind, label] of cases) {
      const { unmount } = render(<RunTraceStep step={{ kind, detail: "{}", createdAt: "2026-09-26T10:00:00Z" }} />);
      expect(screen.getByText(label)).toBeInTheDocument();
      unmount();
    }
  });

  it("marks what the agent said itself as reported by the agent", () => {
    render(<RunTraceStep step={{ kind: "AGENT_LOG", source: "AGENT", detail: '{"message":"matched 40 lines"}', createdAt: "2026-09-26T10:00:00Z" }} />);
    expect(screen.getByText("Reported by the agent")).toBeInTheDocument();
    expect(screen.getByText("matched 40 lines")).toBeInTheDocument();
  });

  it("highlights a newly requested gate like the legacy one", () => {
    const { container } = render(<RunTraceStep step={{ kind: "GATE_REQUESTED", detail: '{"summary":"Post 2 entries"}', createdAt: "2026-09-26T10:00:00Z" }} />);
    expect(container.firstChild).toHaveClass("border-amber");
  });
});
