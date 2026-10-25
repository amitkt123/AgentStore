import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import type { Withdrawal } from "@/lib/api/types";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));
const simulatePayoutAction = vi.fn();
vi.mock("@/app/(app)/developer/earnings/actions", () => ({ simulatePayoutAction: (...a: unknown[]) => simulatePayoutAction(...a) }));

import { WithdrawalHistory } from "./WithdrawalHistory";

const w = (over: Partial<Withdrawal>): Withdrawal => ({
  id: "w1", amount: 1000, mode: "IMPS", status: "PROCESSED", utr: "UTR123456", accountLabel: "Bank ••1234",
  failureReason: null, createdAt: "2026-10-03T10:00:00Z", processedAt: "2026-10-03T10:05:00Z", ...over,
});
const rows = [
  w({ id: "a", status: "PROCESSED" }),
  w({ id: "b", status: "PROCESSING", utr: null, amount: 250 }),
  w({ id: "c", status: "REQUESTED", utr: null, amount: 300 }),
  w({ id: "d", status: "REVERSED", utr: null, amount: 400 }),
  w({ id: "e", status: "FAILED", utr: null, amount: 500 }),
];

beforeEach(() => { vi.clearAllMocks(); });

it("shows status labels, amounts, account and the UTR when sent", () => {
  render(<WithdrawalHistory withdrawals={rows} simulate={false} />);
  expect(screen.getAllByText("Sending")).toHaveLength(2);
  expect(screen.getByText("Sent")).toBeInTheDocument();
  expect(screen.getByText("Returned")).toBeInTheDocument();
  expect(screen.getByText("Failed")).toBeInTheDocument();
  expect(screen.getByText("UTR UTR123456")).toBeInTheDocument();
  expect(screen.getByText("₹250.00")).toBeInTheDocument();
  expect(screen.getAllByText("Bank ••1234").length).toBe(5);
});

it("renders nothing when there are no withdrawals", () => {
  const { container } = render(<WithdrawalHistory withdrawals={[]} simulate={false} />);
  expect(container).toBeEmptyDOMElement();
});

it("hides Simulate unless the provider is fake, and only offers it on PROCESSING rows", () => {
  const { rerender } = render(<WithdrawalHistory withdrawals={rows} simulate={false} />);
  expect(screen.queryByRole("button", { name: /simulate/i })).not.toBeInTheDocument();
  rerender(<WithdrawalHistory withdrawals={rows} simulate />);
  expect(screen.getAllByRole("button", { name: "Simulate" })).toHaveLength(1);
});

it("simulates an outcome and refreshes", async () => {
  simulatePayoutAction.mockResolvedValue({ ok: true });
  const user = userEvent.setup();
  render(<WithdrawalHistory withdrawals={rows} simulate />);
  await user.click(screen.getByRole("button", { name: "Simulate" }));
  await user.click(screen.getByRole("menuitem", { name: "Processed" }));
  expect(simulatePayoutAction).toHaveBeenCalledWith("b", "PROCESSED");
  await vi.waitFor(() => expect(refresh).toHaveBeenCalled());
});

it("shows a simulate error", async () => {
  simulatePayoutAction.mockResolvedValue({ error: "Not simulatable" });
  const user = userEvent.setup();
  render(<WithdrawalHistory withdrawals={rows} simulate />);
  await user.click(screen.getByRole("button", { name: "Simulate" }));
  await user.click(screen.getByRole("menuitem", { name: "Failed" }));
  expect(await screen.findByText("Not simulatable")).toBeInTheDocument();
});

it("shows a generic message when simulating throws", async () => {
  simulatePayoutAction.mockRejectedValue(new Error("network"));
  const user = userEvent.setup();
  render(<WithdrawalHistory withdrawals={rows} simulate />);
  await user.click(screen.getByRole("button", { name: "Simulate" }));
  await user.click(screen.getByRole("menuitem", { name: "Failed" }));
  expect(await screen.findByText("Something went wrong. Try again.")).toBeInTheDocument();
  expect(screen.getByRole("menuitem", { name: "Failed" })).toBeEnabled();
});
