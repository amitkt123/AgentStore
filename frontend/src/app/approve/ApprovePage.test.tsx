import { render, screen } from "@testing-library/react";
import { vi } from "vitest";

vi.mock("@/app/(app)/runs/actions", () => ({ decideGateAction: vi.fn() }));
vi.mock("@/lib/api/runs", () => ({ getRunGates: vi.fn() }));
vi.mock("@/lib/session", () => ({ getSession: vi.fn().mockResolvedValue({ userId: "u", orgId: "o", role: "BUYER_APPROVER", exp: 9e9 }) }));
import { getRunGates } from "@/lib/api/runs";
import Page from "./[token]/[gateId]/page";

const gate = { gateId: "g1", action: "post", summary: "Post 142 entries?", payload: {}, status: "PENDING", expiresAt: "2999-01-01T00:00:00Z", estimatedUnits: null, costIfApproved: null, preview: null, decidedVia: null, decidedByUserId: null, decidedAt: null, note: null };

it("renders the requested gate for an approver", async () => {
  vi.mocked(getRunGates).mockResolvedValue([gate] as never);
  render(await Page({ params: { token: "R-1", gateId: "g1" } }));
  expect(screen.getByText("Post 142 entries?")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
});

it("says so when the gate is not part of the run", async () => {
  vi.mocked(getRunGates).mockResolvedValue([gate] as never);
  render(await Page({ params: { token: "R-1", gateId: "nope" } }));
  expect(screen.getByText(/can't find that request/i)).toBeInTheDocument();
});
