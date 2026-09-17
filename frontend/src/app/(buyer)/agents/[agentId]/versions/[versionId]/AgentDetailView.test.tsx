import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AgentDetailView } from "./AgentDetailView";
import type { AgentVersionDetailResponse } from "@/lib/types";

const version: AgentVersionDetailResponse = {
  id: "v1",
  agentId: "a1",
  agentName: "GSTR-2B Reconciler",
  versionNumber: "4.2",
  status: "LIVE",
  manifest: {
    name: "GSTR-2B Reconciler",
    version: "4.2",
    scopes: [{ type: "READ", resource: "purchase_register", description: "Tally purchase register" }],
    networkAllowlist: ["gst.gov.in"],
    costEnvelope: { currency: "INR", maxPerRun: 25000, pricingUnit: "OUTCOME", unitPrice: 18, unitLabel: "matched_invoice" },
    approvalGates: [],
    dataClasses: [],
  },
};

describe("AgentDetailView", () => {
  it("links to the connect flow for this exact agent and version", () => {
    render(<AgentDetailView version={version} />);
    expect(screen.getByRole("link", { name: /connect this agent/i })).toHaveAttribute("href", "/connect/a1/v1");
  });

  it("shows the agent name and version number as a heading", () => {
    render(<AgentDetailView version={version} />);
    expect(screen.getByRole("heading", { name: "GSTR-2B Reconciler" })).toBeInTheDocument();
    expect(screen.getByText(/4\.2/)).toBeInTheDocument();
  });
});
