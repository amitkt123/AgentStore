import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import type { Checkout, PaymentOptions } from "@/lib/api/types";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));
const startTopUp = vi.fn();
const simulate = vi.fn();
const loadBankDetails = vi.fn();
vi.mock("@/app/(app)/billing/actions", () => ({
  startTopUp: (...a: unknown[]) => startTopUp(...a),
  simulate: (...a: unknown[]) => simulate(...a),
  loadBankDetails: (...a: unknown[]) => loadBankDetails(...a),
}));
const openRazorpayCheckout = vi.fn();
vi.mock("./razorpayCheckout", () => ({ openRazorpayCheckout: (...a: unknown[]) => openRazorpayCheckout(...a) }));

import { AddMoney } from "./AddMoney";

const options: PaymentOptions = {
  provider: "fake", methods: ["UPI", "CARD", "NETBANKING", "BANK_TRANSFER"], topUpMin: 100, topUpDailyLimit: 500000, topUpRemainingToday: 500000,
};
const order = { id: "po-1", purpose: "TOPUP", method: "UPI", amount: 500, status: "CREATED", refundedAmount: 0, createdAt: "2026-10-01T00:00:00Z", paidAt: null } as const;
const fakeCheckout: Checkout = { order, provider: "fake", providerOrderId: "fake_1", checkout: {} };
const rzpCheckout: Checkout = { order, provider: "razorpay", providerOrderId: "order_1", checkout: { key: "rzp_test", amount: 50000, order_id: "order_1" } };

beforeEach(() => { vi.clearAllMocks(); });

async function open(opts = options) {
  const user = userEvent.setup();
  render(<AddMoney options={opts} balance={1000} />);
  await user.click(screen.getByRole("button", { name: "Add money" }));
  return user;
}

it("offers only the enabled methods", async () => {
  await open({ ...options, methods: ["UPI"] });
  expect(screen.getByRole("radio", { name: "UPI" })).toBeInTheDocument();
  expect(screen.queryByRole("radio", { name: "Card" })).not.toBeInTheDocument();
  expect(screen.queryByRole("radio", { name: "Bank transfer" })).not.toBeInTheDocument();
});

it("rejects an amount under the minimum without calling the server", async () => {
  const user = await open();
  await user.type(screen.getByLabelText("Amount (₹)"), "50");
  await user.click(screen.getByRole("button", { name: "Continue" }));
  expect(screen.getByText("Minimum is ₹100")).toBeInTheDocument();
  expect(startTopUp).not.toHaveBeenCalled();
});

it("shows the server's message when the top-up is refused", async () => {
  startTopUp.mockResolvedValue({ error: "Daily limit reached" });
  const user = await open();
  await user.type(screen.getByLabelText("Amount (₹)"), "500");
  await user.click(screen.getByRole("button", { name: "Continue" }));
  expect(await screen.findByText("Daily limit reached")).toBeInTheDocument();
});

it("shows test-mode simulate buttons for the fake provider and confirms a simulated payment", async () => {
  startTopUp.mockResolvedValue({ checkout: fakeCheckout });
  simulate.mockResolvedValue({ outcome: "PAID" });
  const user = await open();
  await user.type(screen.getByLabelText("Amount (₹)"), "500");
  await user.click(screen.getByRole("button", { name: "Continue" }));
  expect(startTopUp).toHaveBeenCalledWith(500, "UPI");
  expect(await screen.findByText("Test mode: no real money moves")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Simulate failure" })).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Simulate payment" }));
  expect(simulate).toHaveBeenCalledWith("po-1", "PAID");
  expect(await screen.findByText("Payment received. Your balance updates within a minute.")).toBeInTheDocument();
});

it("opens Razorpay Checkout with the options map unchanged", async () => {
  startTopUp.mockResolvedValue({ checkout: rzpCheckout });
  openRazorpayCheckout.mockResolvedValue("paid");
  const user = await open();
  await user.type(screen.getByLabelText("Amount (₹)"), "500");
  await user.click(screen.getByRole("button", { name: "Continue" }));
  await waitFor(() => expect(openRazorpayCheckout).toHaveBeenCalledWith(rzpCheckout.checkout));
  expect(await screen.findByText("Payment received. Your balance updates within a minute.")).toBeInTheDocument();
});

it("shows bank transfer details instead of an amount form", async () => {
  loadBankDetails.mockResolvedValue({ details: { accountNumber: "VA123456", ifsc: "RAZR0000001", beneficiaryName: "Karya Pay" } });
  const user = await open();
  await user.click(screen.getByRole("radio", { name: "Bank transfer" }));
  expect(await screen.findByText("VA123456")).toBeInTheDocument();
  expect(screen.getByText("RAZR0000001")).toBeInTheDocument();
  expect(screen.getByText("Karya Pay")).toBeInTheDocument();
  expect(screen.getByText("Money sent here is added to your wallet automatically, usually within a few minutes.")).toBeInTheDocument();
  expect(screen.queryByLabelText("Amount (₹)")).not.toBeInTheDocument();
  expect(startTopUp).not.toHaveBeenCalled();
});
