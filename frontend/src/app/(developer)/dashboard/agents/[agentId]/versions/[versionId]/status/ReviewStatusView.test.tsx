import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ReviewStatusView } from "./ReviewStatusView";
import type { AgentVersionDetailResponse, BuildSummary } from "@/lib/types";

vi.mock("@/components/submissionActions", () => ({ submitVersionAction: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));

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

const build = (status: BuildSummary["status"]): BuildSummary =>
  ({ buildId: "b1", status, failureCode: null, failureReason: null, logUrl: null });

const draft = (b?: BuildSummary): AgentVersionDetailResponse => ({ ...baseVersion, status: "DRAFT", build: b });

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
    render(<ReviewStatusView version={draft(build("QUEUED"))} />);
    expect(screen.getByRole("region", { name: "Build" })).toBeInTheDocument();
    expect(screen.getByText(/Waiting for the build runner/)).toBeInTheDocument();
  });

  it("shows no build panel for versions without a build", () => {
    render(<ReviewStatusView version={baseVersion} />);
    expect(screen.queryByRole("region", { name: "Build" })).not.toBeInTheDocument();
  });

  it("tells a draft with no build how to push its code", () => {
    render(<ReviewStatusView version={draft()} />);
    const panel = screen.getByRole("region", { name: "Push your code" });
    expect(panel).toHaveTextContent("agentstore push --wait");
    expect(panel).toHaveTextContent("a1");
    expect(panel).toHaveTextContent("5.0");
    expect(screen.queryByRole("button", { name: "Submit for review" })).not.toBeInTheDocument();
  });

  it("offers Submit for review once the draft's build has succeeded", () => {
    render(<ReviewStatusView version={draft(build("SUCCEEDED"))} />);
    expect(screen.getByRole("button", { name: "Submit for review" })).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Push your code" })).not.toBeInTheDocument();
  });

  it.each(["QUEUED", "BUILDING", "FAILED"] as const)("does not offer Submit while the build is %s", (status) => {
    render(<ReviewStatusView version={draft(build(status))} />);
    expect(screen.queryByRole("button", { name: "Submit for review" })).not.toBeInTheDocument();
  });

  it("offers neither push instructions nor Submit for a version already in review", () => {
    render(<ReviewStatusView version={{ ...baseVersion, build: build("SUCCEEDED") }} />);
    expect(screen.queryByRole("button", { name: "Submit for review" })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Push your code" })).not.toBeInTheDocument();
  });
});
