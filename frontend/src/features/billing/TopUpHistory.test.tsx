import { render, screen } from "@testing-library/react";
import { TopUpHistory } from "./TopUpHistory";
import type { PaymentOrder } from "@/lib/api/types";

const o = (over: Partial<PaymentOrder>): PaymentOrder => ({
  id: "p1", purpose: "TOPUP", method: "UPI", amount: 500, status: "PAID", refundedAmount: 0, createdAt: "2026-09-03T10:00:00Z", paidAt: "2026-09-03T10:01:00Z", ...over,
});

it("lists date, method, amount and status, and notes refunds", () => {
  render(<TopUpHistory topUps={[o({}), o({ id: "p2", method: "BANK_TRANSFER", amount: 900, refundedAmount: 150 })]} />);
  expect(screen.getByText("₹500.00")).toBeInTheDocument();
  expect(screen.getByText("Bank transfer")).toBeInTheDocument();
  expect(screen.getAllByText("Paid")).toHaveLength(2);
  expect(screen.getByText("Refunded ₹150.00")).toBeInTheDocument();
  expect(screen.getAllByText(/Refunded/)).toHaveLength(1);
});

it("says so when there are no top-ups", () => {
  render(<TopUpHistory topUps={[]} />);
  expect(screen.getByText("No top-ups yet.")).toBeInTheDocument();
});
