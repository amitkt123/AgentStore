import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ReviewStatusView } from "./ReviewStatusView";
import type { AgentVersionDetailResponse } from "@/lib/types";

const baseVersion: AgentVersionDetailResponse = {
  id: "v1",
  agentId: "a1",
  agentName: "Collections",
  versionNumber: "5.0",
  status: "IN_REVIEW",
  manifest: {
    name: "Collections",
    version: "5.0",
    scopes: [],
    networkAllowlist: [],
    costEnvelope: { currency: "INR", maxPerRun: 0, pricingUnit: "OUTCOME", unitPrice: 0, unitLabel: "" },
    approvalGates: [],
    dataClasses: [],
  },
};

describe("ReviewStatusView", () => {
  it("explains an in-review version is waiting on a human reviewer", () => {
    render(<ReviewStatusView version={baseVersion} />);
    expect(screen.getByText(/Waiting on a human reviewer/)).toBeInTheDocument();
  });

  it("explains a rejected version", () => {
    render(<ReviewStatusView version={{ ...baseVersion, status: "REJECTED" }} />);
    expect(screen.getByText(/A reviewer rejected this version/)).toBeInTheDocument();
  });

  it("shows the build panel when the version has a build", () => {
    render(
      <ReviewStatusView
        version={{
          ...baseVersion,
          status: "DRAFT",
          build: { buildId: "b1", status: "QUEUED", failureCode: null, failureReason: null, logUrl: null },
        }}
      />,
    );
    expect(screen.getByRole("region", { name: "Build" })).toBeInTheDocument();
    expect(screen.getByText(/Waiting for the build runner/)).toBeInTheDocument();
  });

  it("shows no build panel for versions without a build", () => {
    render(<ReviewStatusView version={baseVersion} />);
    expect(screen.queryByRole("region", { name: "Build" })).not.toBeInTheDocument();
  });
});
