import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

const loadIssues = vi.hoisted(() => vi.fn());
const run = vi.hoisted(() => vi.fn());
const resolve = vi.hoisted(() => vi.fn());
vi.mock("@/app/(app)/admin/reconciliation/actions", () => ({
  loadIssuesAction: (...a: unknown[]) => loadIssues(...a),
  runReconciliationAction: (...a: unknown[]) => run(...a),
  resolveIssueAction: (...a: unknown[]) => resolve(...a),
}));
import { ReconciliationView } from "./ReconciliationView";
import type { ReconciliationIssue } from "@/lib/api/types";

const issue = (over: Partial<ReconciliationIssue> = {}): ReconciliationIssue => ({
  id: "i1", day: "2026-10-21", kind: "AMOUNT_MISMATCH", providerRef: "pay_123", detail: { expected: "100.00", actual: "90.00" },
  createdAt: "2026-10-22T01:00:00Z", resolvedAt: null, ...over,
});

beforeEach(() => { loadIssues.mockReset(); run.mockReset(); resolve.mockReset(); });

it("lists issues with kind, ref, detail as key: value, and created", () => {
  render(<ReconciliationView date="2026-10-21" issues={[issue()]} />);
  expect(screen.getByLabelText("Date")).toHaveValue("2026-10-21");
  expect(screen.getByText("AMOUNT_MISMATCH")).toBeInTheDocument();
  expect(screen.getByText("pay_123")).toBeInTheDocument();
  expect(screen.getByText("expected: 100.00")).toBeInTheDocument();
  expect(screen.getByText("actual: 90.00")).toBeInTheDocument();
  expect(screen.getByText("22 Oct 2026")).toBeInTheDocument();
});

it("shows an empty state", () => {
  render(<ReconciliationView date="2026-10-21" issues={[]} />);
  expect(screen.getByText("No issues for this day.")).toBeInTheDocument();
});

it("reloads when the date changes", async () => {
  loadIssues.mockResolvedValue({ issues: [issue({ id: "i2", providerRef: "pay_999" })] });
  render(<ReconciliationView date="2026-10-21" issues={[issue()]} />);
  const date = screen.getByLabelText("Date");
  await userEvent.clear(date);
  await userEvent.type(date, "2026-10-20");
  expect(loadIssues).toHaveBeenLastCalledWith("2026-10-20");
  expect(await screen.findByText("pay_999")).toBeInTheDocument();
});

it("runs reconciliation for the chosen day and shows the result", async () => {
  run.mockResolvedValue({ issues: [issue({ id: "i3", providerRef: "pay_new" })] });
  render(<ReconciliationView date="2026-10-21" issues={[]} />);
  await userEvent.click(screen.getByRole("button", { name: "Run now" }));
  expect(run).toHaveBeenCalledWith("2026-10-21");
  expect(await screen.findByText("pay_new")).toBeInTheDocument();
});

it("needs a note to resolve an issue", async () => {
  resolve.mockResolvedValue({ issues: [issue({ resolvedAt: "2026-10-22T09:00:00Z" })] });
  render(<ReconciliationView date="2026-10-21" issues={[issue()]} />);
  await userEvent.click(screen.getByRole("button", { name: "Resolve" }));
  expect(resolve).not.toHaveBeenCalled();
  expect(await screen.findByText("A note is required")).toBeInTheDocument();
  await userEvent.type(screen.getByLabelText("Note"), " refunded manually ");
  await userEvent.click(screen.getByRole("button", { name: "Resolve" }));
  expect(resolve).toHaveBeenCalledWith("i1", "refunded manually", "2026-10-21");
  expect(await screen.findByText("Resolved 22 Oct 2026")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Resolve" })).not.toBeInTheDocument();
});

it("shows an error inline", async () => {
  run.mockResolvedValue({ error: "Provider unavailable" });
  render(<ReconciliationView date="2026-10-21" issues={[]} />);
  await userEvent.click(screen.getByRole("button", { name: "Run now" }));
  expect(await screen.findByText("Provider unavailable")).toBeInTheDocument();
});
