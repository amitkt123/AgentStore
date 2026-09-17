import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { RunsList } from "./RunsList";
import type { RunSummaryResponse } from "@/lib/types";

const runs: RunSummaryResponse[] = [
  { id: "r1", agentName: "GSTR-2B Reconciler", status: "AWAITING_APPROVAL", totalCharged: 16352, startedAt: "2026-09-16T09:41:00Z" },
];

describe("RunsList", () => {
  it("links each run to its console page", () => {
    render(<RunsList runs={runs} />);
    expect(screen.getByRole("link", { name: /GSTR-2B Reconciler/i })).toHaveAttribute("href", "/runs/r1");
  });

  it("shows a message when there are no runs yet", () => {
    render(<RunsList runs={[]} />);
    expect(screen.getByText(/No runs yet/i)).toBeInTheDocument();
  });
});
