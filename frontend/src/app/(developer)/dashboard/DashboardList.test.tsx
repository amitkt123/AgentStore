import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DashboardList } from "./DashboardList";
import type { MyAgentsResponse } from "@/lib/types";

const agents: MyAgentsResponse[] = [
  {
    agentId: "a1",
    agentName: "GSTR-2B Reconciler",
    category: "GST & TDS",
    versions: [
      { id: "v1", versionNumber: "4.2", status: "LIVE" },
      { id: "v2", versionNumber: "5.0", status: "IN_REVIEW" },
    ],
  },
];

describe("DashboardList", () => {
  it("links an in-review version to its status page", () => {
    render(<DashboardList agents={agents} />);
    expect(screen.getByRole("link", { name: /5\.0/i })).toHaveAttribute("href", "/dashboard/agents/a1/versions/v2/status");
  });

  it("links a live version to the manage page", () => {
    render(<DashboardList agents={agents} />);
    expect(screen.getByRole("link", { name: /4\.2/i })).toHaveAttribute("href", "/dashboard/agents/a1");
  });

  it("shows a message when there are no agents yet", () => {
    render(<DashboardList agents={[]} />);
    expect(screen.getByText(/haven't submitted any agents yet/i)).toBeInTheDocument();
  });

  it("links a draft to its version page, where the next step (push, then submit) is shown", () => {
    const withDraft: MyAgentsResponse[] = [
      { agentId: "a2", agentName: "New Agent", category: "GST & TDS", versions: [{ id: "v9", versionNumber: "0.1.0", status: "DRAFT" }] },
    ];
    render(<DashboardList agents={withDraft} />);
    expect(screen.getByRole("link", { name: /0\.1\.0/i })).toHaveAttribute("href", "/dashboard/agents/a2/versions/v9/status");
    expect(screen.getByText("Finish submission")).toBeInTheDocument();
  });
});
