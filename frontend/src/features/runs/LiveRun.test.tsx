import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
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

it("shows the run as a replay diagram instead of step cards", () => {
  const s = snap();
  vi.mocked(useRunPolling).mockReturnValue({ data: s, error: null, forbidden: false });
  render(<LiveRun runRef="R-2291" initial={s} manifest={null} canDecide />);
  expect(screen.getByRole("region", { name: "Run replay" })).toBeInTheDocument();
  expect(screen.queryByRole("heading", { name: "Timeline" })).toBeNull();
  expect(screen.queryByRole("list", { name: "Flow" })).toBeNull();
});

it("shows a clicked step's full detail in the facts panel, and Close returns to spend", async () => {
  const note = "Reading the bank statement for August and matching every line against the ledger accounts one by one";
  const s = snap({ status: "RUNNING", steps: [{ id: "s1", kind: "AGENT_LOG", source: "AGENT", detail: JSON.stringify({ message: note }), createdAt: "2026-09-29T10:05:00Z" }] });
  vi.mocked(useRunPolling).mockReturnValue({ data: s, error: null, forbidden: false });
  render(<LiveRun runRef="R-2291" initial={s} manifest={null} canDecide />);
  const facts = screen.getByRole("complementary", { name: "Run facts" });
  expect(within(facts).getByText("Spend")).toBeInTheDocument();
  await userEvent.click(within(screen.getByRole("region", { name: "Run replay" })).getByRole("button", { name: /Agent note/ }));
  expect(within(facts).getByText(note)).toBeInTheDocument();
  expect(within(facts).queryByText("Spend")).toBeNull();
  await userEvent.click(within(facts).getByRole("button", { name: "Close" }));
  expect(within(facts).getByText("Spend")).toBeInTheDocument();
});

it("returns focus to the selected block when the facts panel is closed", async () => {
  const s = snap({ status: "RUNNING", steps: [{ id: "s1", kind: "AGENT_LOG", source: "AGENT", detail: '{"message":"Reading"}', createdAt: "2026-09-29T10:05:00Z" }] });
  vi.mocked(useRunPolling).mockReturnValue({ data: s, error: null, forbidden: false });
  render(<LiveRun runRef="R-2291" initial={s} manifest={null} canDecide />);
  const blockBtn = within(screen.getByRole("region", { name: "Run replay" })).getByRole("button", { name: /Agent note/ });
  await userEvent.click(blockBtn);
  await userEvent.click(within(screen.getByRole("complementary", { name: "Run facts" })).getByRole("button", { name: "Close" }));
  expect(blockBtn).toHaveFocus();
});

it("still offers the approval while a gate waits", () => {
  const s = snap({ steps: [{ id: "g", kind: "GATE_REQUESTED", detail: '{"action":"post","summary":"Post 142 entries?"}', createdAt: "2026-09-29T10:05:00Z" }] }, [gate]);
  vi.mocked(useRunPolling).mockReturnValue({ data: s, error: null, forbidden: false });
  render(<LiveRun runRef="R-2291" initial={s} manifest={null} canDecide />);
  expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
  expect(within(screen.getByRole("region", { name: "Run replay" })).getAllByText(/Waiting for you/).length).toBeGreaterThan(0);
});

it("shows spend as a meter", () => {
  const s = snap();
  vi.mocked(useRunPolling).mockReturnValue({ data: s, error: null, forbidden: false });
  render(<LiveRun runRef="R-2291" initial={s} manifest={null} canDecide />);
  const meter = screen.getByRole("meter", { name: "Spend" });
  expect(meter).toHaveAttribute("aria-valuenow", "96");
  expect(meter).toHaveAttribute("aria-valuemax", "500");
});

it("puts the approve buttons next to a clicked pending approval in the flow", async () => {
  const s = snap({ steps: [{ id: "q", kind: "GATE_REQUESTED", detail: '{"gateId":"g1","action":"post","summary":"Post 142 entries?"}', createdAt: "2026-09-29T10:05:00Z" }] }, [gate]);
  vi.mocked(useRunPolling).mockReturnValue({ data: s, error: null, forbidden: false });
  render(<LiveRun runRef="R-2291" initial={s} manifest={null} canDecide />);
  await userEvent.click(within(screen.getByRole("region", { name: "Run replay" })).getByRole("button", { name: /Post 142 entries/ }));
  const facts = screen.getByRole("complementary", { name: "Run facts" });
  expect(within(facts).getByRole("button", { name: "Approve" })).toBeInTheDocument();
});

it("says the run waits for an approver, not for you, when the viewer cannot decide", () => {
  const s = snap({ steps: [{ id: "q", kind: "GATE_REQUESTED", detail: '{"gateId":"g1","action":"post","summary":"Post 142 entries?"}', createdAt: "2026-09-29T10:05:00Z" }] }, [gate]);
  vi.mocked(useRunPolling).mockReturnValue({ data: s, error: null, forbidden: false });
  render(<LiveRun runRef="R-2291" initial={s} manifest={null} canDecide={false} />);
  expect(screen.queryByText("Waiting for you")).toBeNull();
  expect(screen.getAllByText("Waiting for an approver").length).toBeGreaterThan(0);
});

it("keeps one set of approve buttons when the clicked approval's card opens in the facts panel", async () => {
  const s = snap({ steps: [{ id: "q", kind: "GATE_REQUESTED", detail: '{"gateId":"g1","action":"post","summary":"Post 142 entries?"}', createdAt: "2026-09-29T10:05:00Z" }] }, [gate]);
  vi.mocked(useRunPolling).mockReturnValue({ data: s, error: null, forbidden: false });
  render(<LiveRun runRef="R-2291" initial={s} manifest={null} canDecide />);
  await userEvent.click(within(screen.getByRole("region", { name: "Run replay" })).getByRole("button", { name: /Post 142 entries/ }));
  expect(screen.getAllByRole("button", { name: "Approve" })).toHaveLength(1);
  expect(within(screen.getByRole("complementary", { name: "Run facts" })).getAllByRole("heading", { name: "Post 142 entries?" })).toHaveLength(1);
});

it("shows no approve buttons in the facts panel for a decided approval", async () => {
  const s = snap({ steps: [{ id: "q", kind: "GATE_REQUESTED", detail: '{"gateId":"g1","action":"post","summary":"Post 142 entries?"}', createdAt: "2026-09-29T10:05:00Z" }] }, [{ ...gate, status: "APPROVED", decidedAt: "2026-09-29T10:06:00Z", decidedByUserId: "u1", decidedVia: "WEB" }]);
  vi.mocked(useRunPolling).mockReturnValue({ data: s, error: null, forbidden: false });
  render(<LiveRun runRef="R-2291" initial={s} manifest={null} canDecide />);
  await userEvent.click(within(screen.getByRole("region", { name: "Run replay" })).getByRole("button", { name: /Post 142 entries/ }));
  expect(within(screen.getByRole("complementary", { name: "Run facts" })).queryByRole("button", { name: "Approve" })).toBeNull();
});
