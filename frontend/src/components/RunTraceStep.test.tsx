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
});
