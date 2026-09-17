import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { RunConsole } from "./RunConsole";
import type { RunDetailResponse } from "@/lib/types";

vi.mock("./actions", () => ({
  approveRunAction: vi.fn().mockResolvedValue(undefined),
  rejectRunAction: vi.fn().mockResolvedValue(undefined),
}));

const awaitingRun: RunDetailResponse = {
  id: "r1",
  status: "AWAITING_APPROVAL",
  costCap: 18000,
  totalCharged: 16352,
  steps: [{ kind: "GATE", detail: '{"summary":"Crosses your threshold"}', createdAt: "2026-09-16T09:58:12Z" }],
};

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient();
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

describe("RunConsole", () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => awaitingRun });
  });

  it("shows approve/reject controls to a buyer approver when a run is awaiting approval", () => {
    renderWithClient(<RunConsole runId="r1" initial={awaitingRun} role="BUYER_APPROVER" />);
    expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reject" })).toBeInTheDocument();
  });

  it("hides approve/reject controls from a non-approver role and shows an explanatory line instead", () => {
    renderWithClient(<RunConsole runId="r1" initial={awaitingRun} role="BUYER_ADMIN" />);
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reject" })).not.toBeInTheDocument();
    expect(screen.getByText("Waiting on a buyer approver to review this run.")).toBeInTheDocument();
  });

  it("calls approveRunAction when Approve is clicked", async () => {
    const { approveRunAction } = await import("./actions");
    renderWithClient(<RunConsole runId="r1" initial={awaitingRun} role="BUYER_APPROVER" />);
    await userEvent.click(screen.getByRole("button", { name: "Approve" }));
    expect(approveRunAction).toHaveBeenCalledWith("r1");
  });

  it("labels a cost-cap failure as a deliberate stop, not a generic error", () => {
    const failed: RunDetailResponse = { ...awaitingRun, status: "FAILED", totalCharged: 18000, costCap: 18000 };
    renderWithClient(<RunConsole runId="r1" initial={failed} role="BUYER_APPROVER" />);
    expect(screen.getByText("Stopped — spend limit reached")).toBeInTheDocument();
  });

  it("labels a non-cost-cap failure generically", () => {
    const failed: RunDetailResponse = { ...awaitingRun, status: "FAILED", totalCharged: 5000, costCap: 18000 };
    renderWithClient(<RunConsole runId="r1" initial={failed} role="BUYER_APPROVER" />);
    expect(screen.getByText("Failed")).toBeInTheDocument();
    expect(screen.queryByText("Stopped — spend limit reached")).not.toBeInTheDocument();
  });

  it("re-enables the buttons and shows an error if approveRunAction rejects", async () => {
    const { approveRunAction } = await import("./actions");
    vi.mocked(approveRunAction).mockRejectedValueOnce(new Error("boom"));
    renderWithClient(<RunConsole runId="r1" initial={awaitingRun} role="BUYER_APPROVER" />);

    const approveButton = screen.getByRole("button", { name: "Approve" });
    await userEvent.click(approveButton);

    expect(approveButton).not.toBeDisabled();
    expect(screen.getByText("Could not record your decision. Please try again.")).toBeInTheDocument();
  });
});
