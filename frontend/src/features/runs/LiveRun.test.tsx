import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

vi.mock("./useRunPolling", () => ({ useRunPolling: vi.fn() }));
vi.mock("@/app/(app)/runs/actions", () => ({ decideGateAction: vi.fn() }));
import { useRunPolling } from "./useRunPolling";
import { LiveRun } from "./LiveRun";
import type { RunSnapshot } from "@/lib/api/types";
import { bankManifest } from "@/features/flow/fixtures";

const snap = (over: Partial<RunSnapshot["detail"]> = {}, gates: RunSnapshot["gates"] = []): RunSnapshot => ({
  detail: { id: "i", displayId: "R-2291", status: "AWAITING_APPROVAL", terminalReason: null, costCap: 500, totalCharged: 96, steps: [{ kind: "GATE_REQUESTED", detail: "{}", createdAt: "2026-09-29T10:05:00Z" }], ...over },
  flow: { blocks: [{ block: "gate:post", state: "WAITING", metrics: null, gateId: "g1", flag: null }, { block: "charge", state: "IDLE", metrics: null, gateId: null, flag: null }], reachedBlock: "gate:post", spend: { charged: 96, cap: 500 }, deadlineAt: "2999-01-01T00:00:00Z", blockedAttempts: 3 },
  gates,
});
const gate = { gateId: "g1", action: "post", summary: "Post 142 entries?", payload: {}, status: "PENDING" as const, expiresAt: "2999-01-01T00:00:00Z", estimatedUnits: null, costIfApproved: null, preview: null, decidedVia: null, decidedByUserId: null, decidedAt: null, note: null };

it("shows status, spend, blocked attempts, timeline and the pending gate", () => {
  const s = snap({}, [gate]);
  vi.mocked(useRunPolling).mockReturnValue({ data: s, error: null, forbidden: false });
  render(<LiveRun runRef="R-2291" initial={s} manifest={null} canDecide />);
  expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("R-2291");
  expect(screen.getAllByText("Needs you").length).toBeGreaterThan(0);
  expect(screen.getByText(/₹96 of ₹500/)).toBeInTheDocument();
  expect(screen.getByText(/blocked attempts/i).parentElement).toHaveTextContent("3");
  expect(screen.getByText("Asked for your approval")).toBeInTheDocument();
  expect(screen.getByText("Post 142 entries?")).toBeInTheDocument();
});

it("explains a terminal run and shows no gate buttons", () => {
  const s = snap({ status: "FAILED", terminalReason: "GATE_REJECTED" }, [{ ...gate, status: "REJECTED" }]);
  vi.mocked(useRunPolling).mockReturnValue({ data: s, error: null, forbidden: false });
  render(<LiveRun runRef="R-2291" initial={s} manifest={null} canDecide />);
  expect(screen.getByText(/you rejected the approval/i)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
});

it("folds a decided approval below the flow on a finished run", () => {
  const s = snap({ status: "COMPLETED", terminalReason: null }, [{ ...gate, status: "APPROVED", decidedAt: "2026-09-29T10:06:00Z", decidedByUserId: "u1", decidedVia: "WEB" }]);
  vi.mocked(useRunPolling).mockReturnValue({ data: s, error: null, forbidden: false });
  const { container } = render(<LiveRun runRef="R-2291" initial={s} manifest={null} canDecide />);
  const folded = container.querySelector("details");
  expect(folded).not.toBeNull();
  expect(folded!.querySelector("summary")).toHaveTextContent("Approved · Post 142 entries?");
  expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
});

it("shows what the agent logged next to the step", () => {
  const s = snap({ steps: [{ kind: "AGENT_LOG", source: "AGENT", detail: '{"message":"Reading the bank statement"}', createdAt: "2026-09-29T10:05:00Z" }] });
  vi.mocked(useRunPolling).mockReturnValue({ data: s, error: null, forbidden: false });
  render(<LiveRun runRef="R-2291" initial={s} manifest={null} canDecide />);
  expect(screen.getByText("Reading the bank statement")).toBeInTheDocument();
});

it("keeps a just-decided approval open while the run is still going", () => {
  const s = snap({ status: "RUNNING" }, [{ ...gate, status: "APPROVED", decidedAt: "2026-09-29T10:06:00Z", decidedByUserId: "u1", decidedVia: "WEB" }]);
  vi.mocked(useRunPolling).mockReturnValue({ data: s, error: null, forbidden: false });
  const { container } = render(<LiveRun runRef="R-2291" initial={s} manifest={null} canDecide viewerUserId="u1" />);
  expect(container.querySelector("details")).toHaveAttribute("open");
  expect(screen.getByText(/you approved this/i)).toBeVisible();
});

it("filters the timeline to a clicked node and shows all again", async () => {
  const s = snap({ steps: [{ kind: "GATE_REQUESTED", detail: "{}", createdAt: "2026-09-29T10:05:00Z" }, { kind: "LLM_CALL", detail: "{}", createdAt: "2026-09-29T10:04:00Z" }] });
  vi.mocked(useRunPolling).mockReturnValue({ data: s, error: null, forbidden: false });
  render(<LiveRun runRef="R-2291" initial={s} manifest={null} canDecide />);
  await userEvent.click(screen.getByRole("button", { name: /Your approval/ }));
  expect(screen.getByText("Asked for your approval")).toBeInTheDocument();
  expect(screen.queryByText("Asked the AI model")).toBeNull();
  await userEvent.click(screen.getByRole("button", { name: "Show all steps" }));
  expect(screen.getByText("Asked the AI model")).toBeInTheDocument();
});

it("keeps the selection and moves the active edge when a poll changes state", async () => {
  const s = snap();
  vi.mocked(useRunPolling).mockReturnValue({ data: s, error: null, forbidden: false });
  const { container, rerender } = render(<LiveRun runRef="R-2291" initial={s} manifest={null} canDecide />);
  await userEvent.click(screen.getByRole("button", { name: /Your approval/ }));
  const next = snap({ status: "RUNNING" });
  next.flow = { ...next.flow, blocks: [{ ...next.flow.blocks[0], state: "DONE" }, next.flow.blocks[1]], reachedBlock: "charge" };
  vi.mocked(useRunPolling).mockReturnValue({ data: next, error: null, forbidden: false });
  rerender(<LiveRun runRef="R-2291" initial={s} manifest={null} canDecide />);
  expect(screen.getByRole("button", { name: /Your approval/ })).toHaveAttribute("aria-pressed", "true");
  expect(container.querySelector('path[data-active="true"]')).toHaveAttribute("data-to", "charge");
});

it("stops the pulse and marks the reject end on a rejected run", () => {
  const s = snap({ status: "FAILED", terminalReason: "GATE_REJECTED" }, [{ ...gate, action: "post_entries", status: "REJECTED", decidedAt: "2026-09-29T10:06:00Z" }]);
  s.flow = { ...s.flow, blocks: [
    { block: "read:bank_statement", state: "DONE", metrics: null, gateId: null, flag: null },
    { block: "draft:ledger", state: "DONE", metrics: null, gateId: null, flag: null },
    { block: "gate:post_entries", state: "DONE", metrics: null, gateId: "g1", flag: null },
    { block: "charge", state: "SKIPPED", metrics: null, gateId: null, flag: null },
  ] };
  vi.mocked(useRunPolling).mockReturnValue({ data: s, error: null, forbidden: false });
  const { container } = render(<LiveRun runRef="R-2291" initial={s} manifest={bankManifest} canDecide />);
  expect(container.querySelectorAll("circle")).toHaveLength(0);
  expect(container.querySelector("[data-end]")).toHaveTextContent(/you rejected the approval/i);
  expect(container.querySelector('[data-node-id="post:ledger"]')).toHaveTextContent("Skipped");
});

it("shows spend as a meter", () => {
  const s = snap();
  vi.mocked(useRunPolling).mockReturnValue({ data: s, error: null, forbidden: false });
  render(<LiveRun runRef="R-2291" initial={s} manifest={null} canDecide />);
  const meter = screen.getByRole("meter", { name: "Spend" });
  expect(meter).toHaveAttribute("aria-valuenow", "96");
  expect(meter).toHaveAttribute("aria-valuemax", "500");
});
