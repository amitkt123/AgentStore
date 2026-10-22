import { render, screen } from "@testing-library/react";
import { vi } from "vitest";

vi.mock("./useRunPolling", () => ({ useRunPolling: vi.fn() }));
vi.mock("@/app/(app)/runs/actions", () => ({ decideGateAction: vi.fn() }));
import { useRunPolling } from "./useRunPolling";
import { LiveRun } from "./LiveRun";
import type { RunSnapshot } from "@/lib/api/types";

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
  expect(folded!.querySelector("summary")).toHaveTextContent("Post 142 entries?");
  expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
});

it("shows what the agent logged next to the step", () => {
  const s = snap({ steps: [{ kind: "AGENT_LOG", source: "AGENT", detail: '{"message":"Reading the bank statement"}', createdAt: "2026-09-29T10:05:00Z" }] });
  vi.mocked(useRunPolling).mockReturnValue({ data: s, error: null, forbidden: false });
  render(<LiveRun runRef="R-2291" initial={s} manifest={null} canDecide />);
  expect(screen.getByText("Reading the bank statement")).toBeInTheDocument();
});
