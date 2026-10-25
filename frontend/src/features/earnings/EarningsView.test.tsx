import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import { EarningsView } from "./EarningsView";
import type { Earnings, PayoutAccount, PayoutsSummary, Withdrawal } from "@/lib/api/types";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));
const { withdraw } = vi.hoisted(() => ({ withdraw: vi.fn() }));
vi.mock("@/app/(app)/developer/earnings/actions", () => ({
  savePayoutAccount: vi.fn(), withdraw, simulatePayoutAction: vi.fn(),
}));

const account: PayoutAccount = {
  id: "a1", type: "BANK", accountLast4: "1234", ifsc: "HDFC0001234", vpa: null, holderName: "Asha Rao",
  validatedName: "ASHA RAO", nameMatch: true, status: "VERIFIED", failureReason: null,
  createdAt: "2026-10-01T00:00:00Z", verifiedAt: "2026-10-01T00:00:00Z",
};
const payouts: PayoutsSummary = {
  account, available: 6000.5, minimum: 100, dailyLimit: 200000, withdrawnToday: 0, canWithdraw: true,
  blockedReason: null, validationAttemptsLeft: 3, provider: "razorpayx", verifiedName: "Asha Rao",
};
const history: Withdrawal[] = [];
const view = (e: Earnings, p: Partial<PayoutsSummary> = {}) => (
  <EarningsView earnings={e} payouts={{ ...payouts, ...p }} withdrawals={history} />
);

const earnings: Earnings = {
  month: "2026-10", buyersPaid: 10000, platformFee: 1500, feePercent: 15, aiUsage: 500, tax: 0, earned: 8000, available: 6000.5,
  byAgent: [{ agentId: "a1", agent: "Invoice Reader", runs: 4, buyersPaid: 10000, fee: 1500, aiUsage: 500, earned: 8000, perRun: 2000 }],
};

it("renders the headline figures formatted", () => {
  render(view(earnings));
  expect(screen.getAllByText("Buyers paid").length).toBeGreaterThan(0);
  expect(screen.getByText("Karya fee (15%)")).toBeInTheDocument();
  expect(screen.getAllByText("AI usage").length).toBeGreaterThan(0);
  expect(screen.getByText("You earned")).toBeInTheDocument();
  expect(screen.getAllByText("₹10,000.00").length).toBeGreaterThan(0);
  expect(screen.getByText("Your balance with Karya")).toBeInTheDocument();
  expect(screen.getByText("₹6,000.50")).toBeInTheDocument();
});

it("hides Tax when zero and shows it otherwise", () => {
  const { rerender } = render(view(earnings));
  expect(screen.queryByText("Tax")).not.toBeInTheDocument();
  rerender(view({ ...earnings, tax: 270 }));
  expect(screen.getByText("Tax")).toBeInTheDocument();
  expect(screen.getByText("₹270.00")).toBeInTheDocument();
});

it("explains a negative available balance", () => {
  render(view({ ...earnings, available: -250 }, { canWithdraw: false, blockedReason: "INSUFFICIENT_EARNINGS" }));
  expect(screen.getByText("You owe ₹250.00; it comes out of future earnings")).toBeInTheDocument();
});

it("lists per-agent rows", () => {
  render(view(earnings));
  expect(screen.getByText("Invoice Reader")).toBeInTheDocument();
  expect(screen.getByText("₹2,000.00")).toBeInTheDocument();
});

it("links the CSV download and month navigation", () => {
  render(view(earnings));
  expect(screen.getByRole("link", { name: "Download statement (CSV)" })).toHaveAttribute("href", "/api/developer/statements/2026-10");
  expect(screen.getByRole("link", { name: "Previous month" })).toHaveAttribute("href", "/developer/earnings?month=2026-09");
  expect(screen.getByRole("link", { name: "Next month" })).toHaveAttribute("href", "/developer/earnings?month=2026-11");
});

it("shows the Withdraw button only when withdrawing is allowed", () => {
  const { rerender } = render(view(earnings));
  expect(screen.getByRole("button", { name: "Withdraw" })).toBeInTheDocument();
  rerender(view(earnings, { canWithdraw: false, blockedReason: "DAILY_LIMIT_REACHED" }));
  expect(screen.queryByRole("button", { name: "Withdraw" })).not.toBeInTheDocument();
});

it("has no Withdraw button for a negative balance and keeps the You owe line", () => {
  render(view({ ...earnings, available: -250 }, { canWithdraw: false, blockedReason: "INSUFFICIENT_EARNINGS" }));
  expect(screen.getByText("You owe ₹250.00; it comes out of future earnings")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Withdraw" })).not.toBeInTheDocument();
  expect(screen.queryByText(/once you have/)).not.toBeInTheDocument();
});

it.each([
  ["NO_VERIFIED_PAYOUT_ACCOUNT", "Add and verify a payout account first"],
  ["INSUFFICIENT_EARNINGS", "You can withdraw once you have ₹100"],
  ["DAILY_LIMIT_REACHED", "You've reached today's ₹2,00,000 limit"],
  ["MONEY_FROZEN", "Withdrawals are paused on your account. Contact support."],
  ["DEVELOPER_NOT_VERIFIED", "Get verified to withdraw"],
])("explains %s", (reason, copy) => {
  render(view(earnings, { canWithdraw: false, blockedReason: reason }));
  expect(screen.getByText(copy)).toBeInTheDocument();
});

it("renders the payout account panel and the history", () => {
  render(<EarningsView earnings={earnings} payouts={payouts} withdrawals={[{
    id: "w1", amount: 1000, mode: "IMPS", status: "PROCESSED", utr: "UTR9", accountLabel: "Bank ••1234",
    failureReason: null, createdAt: "2026-10-03T10:00:00Z", processedAt: null,
  }]} />);
  expect(screen.getByText("Bank ••1234 · HDFC0001234")).toBeInTheDocument();
  expect(screen.getByText("UTR UTR9")).toBeInTheDocument();
});

it("keeps the Withdrawal requested notice when the balance drops to zero and the form goes away", async () => {
  withdraw.mockResolvedValue({ withdrawal: {} });
  const user = userEvent.setup();
  const { rerender } = render(view(earnings));
  await user.click(screen.getByRole("button", { name: "Withdraw" }));
  await user.click(screen.getByRole("button", { name: "Confirm" }));
  rerender(view({ ...earnings, available: 0 }, { available: 0, canWithdraw: false, blockedReason: "INSUFFICIENT_EARNINGS" }));
  expect(screen.queryByRole("button", { name: "Withdraw" })).not.toBeInTheDocument();
  expect(screen.getByText("Withdrawal requested.")).toBeInTheDocument();
});

it("does not promise withdrawals before payouts exist", () => {
  render(<EarningsView earnings={earnings} />);
  expect(screen.getByRole("heading", { name: "Your balance with Karya" })).toBeInTheDocument();
  expect(screen.getByText("Withdrawals open once payouts are live.")).toBeInTheDocument();
  expect(screen.queryByText(/available to withdraw/i)).toBeNull();
});
