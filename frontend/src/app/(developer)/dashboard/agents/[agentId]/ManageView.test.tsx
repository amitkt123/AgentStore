import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ManageView } from "./ManageView";
import type { MyAgentsResponse } from "@/lib/types";

const agent: MyAgentsResponse = {
  agentId: "a1",
  agentName: "GSTR-2B Reconciler",
  category: "GST & TDS",
  versions: [
    { id: "v1", versionNumber: "4.2", status: "LIVE" },
    { id: "v2", versionNumber: "4.1", status: "REJECTED" },
  ],
};

describe("ManageView", () => {
  it("lists every version with its status", () => {
    render(<ManageView agent={agent} />);
    expect(screen.getByText("v4.2")).toBeInTheDocument();
    expect(screen.getByText("v4.1")).toBeInTheDocument();
  });

  it("links to submitting a new version for this agent", () => {
    render(<ManageView agent={agent} />);
    expect(screen.getByRole("link", { name: /Submit new version/i })).toHaveAttribute(
      "href",
      "/dashboard/agents/a1/versions/new",
    );
  });
});
