import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock("@/app/(app)/connect/actions", () => ({ startRunAction: vi.fn() }));
import { StoreFlow } from "./StoreFlow";
import type { VersionDetail } from "@/lib/api/types";

const detail: VersionDetail = {
  id: "v1", agentId: "a1", agentName: "Bank Reconciliation", versionNumber: "1.3.0", status: "LIVE",
  manifest: {
    name: "Bank Reconciliation", version: "1.3.0", networkAllowlist: [], dataClasses: ["FINANCIAL", "BASIC_PERSONAL"], llm: { mode: "PLATFORM", models: ["m"], maxTokensPerRun: 1000, provider: null }, resources: null,
    costEnvelope: { currency: "INR", maxPerRun: 500, pricingUnit: "METERED", unitPrice: 2, unitLabel: "match" },
    scopes: [{ type: "READ", resource: "bank_statement", description: "Your statement lines, CSV", requiresApproval: false }],
    approvalGates: [{ trigger: "post", description: "Before anything is posted", timeoutMinutes: 30, preview: null }],
  },
};

it("shows the declared blocks and what the buyer agrees to", () => {
  render(<StoreFlow agentId="a1" versionId="v1" detail={detail} canConnect />);
  expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Bank Reconciliation");
  expect(screen.getAllByText("Your statement lines, CSV").length).toBeGreaterThan(0);
  expect(screen.getByText("₹2 per match")).toBeInTheDocument();
  expect(screen.getByText("₹500")).toBeInTheDocument();
  expect(screen.getByText("None")).toBeInTheDocument();
  expect(screen.getByText("Financial, Basic personal")).toBeInTheDocument();
});

it("links to the connect flow for a workspace admin", () => {
  render(<StoreFlow agentId="a1" versionId="v1" detail={detail} canConnect />);
  expect(screen.getByRole("link", { name: "Add to workspace" })).toHaveAttribute("href", "/connect/a1/v1");
});

it("does not offer add to workspace to someone who cannot", () => {
  render(<StoreFlow agentId="a1" versionId="v1" detail={detail} canConnect={false} />);
  expect(screen.queryByRole("link", { name: "Add to workspace" })).toBeNull();
  expect(screen.getByText(/ask a workspace admin/i)).toBeInTheDocument();
});

it("shows the selected block in the side panel", async () => {
  render(<StoreFlow agentId="a1" versionId="v1" detail={detail} canConnect />);
  await userEvent.click(screen.getByRole("button", { name: /Your approval/ }));
  expect(screen.getByRole("region", { name: "Selected block" })).toHaveTextContent("Before anything is posted");
});

it("opens on the first block instead of an empty panel", () => {
  render(<StoreFlow agentId="a1" versionId="v1" detail={detail} canConnect />);
  expect(screen.getByRole("region", { name: "Selected block" })).toHaveTextContent("Your statement lines, CSV");
  expect(screen.queryByText(/pick a block/i)).toBeNull();
});

it("does not show run state on a store page", () => {
  render(<StoreFlow agentId="a1" versionId="v1" detail={detail} canConnect />);
  expect(screen.queryByText("Not started")).toBeNull();
});

it("labels unenforced declarations as declared by the developer", () => {
  render(<StoreFlow agentId="a1" versionId="v1" detail={detail} canConnect />);
  expect(screen.getAllByText(/declared by the developer/i)).toHaveLength(2);
});

it("offers Run now instead of a second add when the agent is already added", () => {
  render(<StoreFlow agentId="a1" versionId="v1" detail={detail} canConnect connectionId="c1" />);
  expect(screen.getByRole("button", { name: "Run now" })).toBeInTheDocument();
  expect(screen.queryByRole("link", { name: "Add to workspace" })).toBeNull();
  expect(screen.getByText(/already in your workspace/i)).toBeInTheDocument();
});
