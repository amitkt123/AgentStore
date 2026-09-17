import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ReviewItemDetailView } from "./ReviewItemDetailView";
import type { ReviewItemDetailResponse } from "@/lib/types";

vi.mock("./actions", () => ({
  approveReviewItemAction: vi.fn().mockResolvedValue(undefined),
  rejectReviewItemAction: vi.fn().mockResolvedValue(undefined),
}));

const item: ReviewItemDetailResponse = {
  id: "ri1",
  agentVersionId: "v1",
  agentName: "Collections",
  versionNumber: "5.0",
  status: "PENDING",
  manifest: {
    name: "Collections",
    version: "5.0",
    scopes: [],
    networkAllowlist: [],
    costEnvelope: { currency: "INR", maxPerRun: 200, pricingUnit: "OUTCOME", unitPrice: 5, unitLabel: "invoice_cycle" },
    approvalGates: [],
    dataClasses: [],
  },
  notes: null,
  createdAt: "2026-09-14T10:02:00Z",
  decidedAt: null,
};

describe("ReviewItemDetailView", () => {
  it("submits notes with the approval decision", async () => {
    const { approveReviewItemAction } = await import("./actions");
    render(<ReviewItemDetailView item={item} />);
    await userEvent.type(screen.getByRole("textbox"), "looks safe");
    await userEvent.click(screen.getByRole("button", { name: "Approve" }));
    expect(approveReviewItemAction).toHaveBeenCalledWith("ri1", "looks safe");
  });

  it("submits notes with the rejection decision", async () => {
    const { rejectReviewItemAction } = await import("./actions");
    render(<ReviewItemDetailView item={item} />);
    await userEvent.type(screen.getByRole("textbox"), "scope too broad");
    await userEvent.click(screen.getByRole("button", { name: "Reject" }));
    expect(rejectReviewItemAction).toHaveBeenCalledWith("ri1", "scope too broad");
  });
});
