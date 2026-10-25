import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

const decide = vi.hoisted(() => vi.fn().mockResolvedValue({}));
vi.mock("@/app/(app)/runs/actions", () => ({ decideGateAction: (...a: unknown[]) => decide(...a) }));

import { GateCard, gateIsExpired } from "./GateCard";
import type { GateDetail } from "@/lib/api/types";

const gate: GateDetail = {
  gateId: "g1", action: "post_entries", summary: "Post 142 journal entries for August?", payload: {},
  status: "PENDING", expiresAt: "2999-01-01T00:00:00Z", estimatedUnits: 142, costIfApproved: 284,
  preview: { columns: ["party", "amount"], rows: [{ party: "NEFT ACME 0814", amount: 48200 }], totalRows: 142, total: 3841260 },
  decidedVia: null, decidedByUserId: null, decidedAt: null, note: null, viewerCanDecide: true,
};

beforeEach(() => decide.mockClear());

it("shows the summary, preview rows and cost", () => {
  render(<GateCard runRef="R-1" gate={gate} canDecide />);
  expect(screen.getByText("Post 142 journal entries for August?")).toBeInTheDocument();
  expect(screen.getByText("NEFT ACME 0814")).toBeInTheDocument();
  expect(screen.getByText(/₹284/)).toBeInTheDocument();
  expect(screen.getByText(/142 rows/)).toBeInTheDocument();
});

it("approves through the server action", async () => {
  render(<GateCard runRef="R-1" gate={gate} canDecide />);
  await userEvent.click(screen.getByRole("button", { name: "Approve" }));
  expect(decide).toHaveBeenCalledWith("R-1", "g1", "APPROVE", undefined);
});

it("does not show Approve or Reject to someone who cannot decide", () => {
  render(<GateCard runRef="R-1" gate={gate} canDecide={false} />);
  expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
  expect(screen.queryByRole("button", { name: "Reject" })).toBeNull();
  expect(screen.getByText(/an approver has to decide/i)).toBeInTheDocument();
});

it("does not show Approve or Reject to a buyer the gate's approver list leaves out", () => {
  render(<GateCard runRef="R-1" gate={{ ...gate, viewerCanDecide: false }} canDecide />);
  expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
  expect(screen.getByText(/an approver has to decide/i)).toBeInTheDocument();
});

it("disables the buttons once the gate has expired", () => {
  render(<GateCard runRef="R-1" gate={{ ...gate, expiresAt: "2000-01-01T00:00:00Z" }} canDecide />);
  expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
  expect(screen.getByText(/this request has expired/i)).toBeInTheDocument();
});

it("shows the outcome of a decided gate and no buttons", () => {
  render(<GateCard runRef="R-1" gate={{ ...gate, status: "APPROVED", decidedAt: "2026-09-29T10:00:00Z", decidedByUserId: "me" }} canDecide viewerUserId="me" />);
  expect(screen.getByText(/you approved this/i)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
});

it("shows the backend's error when the decision fails", async () => {
  decide.mockResolvedValueOnce({ error: "Gate already decided" });
  render(<GateCard runRef="R-1" gate={gate} canDecide />);
  await userEvent.click(screen.getByRole("button", { name: "Reject" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Gate already decided");
});

it("expiry helper", () => {
  expect(gateIsExpired({ ...gate, expiresAt: "2000-01-01T00:00:00Z" }, Date.now())).toBe(true);
  expect(gateIsExpired(gate, Date.now())).toBe(false);
  expect(gateIsExpired({ ...gate, status: "EXPIRED" }, Date.now())).toBe(true);
});

it("humanizes headers and right-aligns formatted amounts", () => {
  render(<GateCard runRef="R-1" gate={gate} canDecide />);
  expect(screen.getByRole("columnheader", { name: "Amount" })).toBeInTheDocument();
  const amount = screen.getByText("48,200");
  expect(amount.className).toMatch(/num/);
});

it("says who decided instead of always 'you'", () => {
  render(<GateCard runRef="R-1" gate={{ ...gate, status: "APPROVED", decidedByUserId: "someone-else", decidedVia: "WEB", decidedAt: "2026-09-29T08:47:00Z" }} canDecide={false} viewerUserId="me" />);
  expect(screen.queryByText(/you approved/i)).toBeNull();
  expect(screen.getByText(/^Approved/)).toBeInTheDocument();
});
