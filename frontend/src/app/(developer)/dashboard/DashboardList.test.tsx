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
});
