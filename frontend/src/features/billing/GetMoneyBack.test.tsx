import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import type { Refund, Refundable } from "@/lib/api/types";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));
const requestRefund = vi.fn();
const previewRefund = vi.fn();
vi.mock("@/app/(app)/billing/actions", () => ({
  requestRefund: (...a: unknown[]) => requestRefund(...a),
  previewRefund: (...a: unknown[]) => previewRefund(...a),
}));

import { GetMoneyBack } from "./GetMoneyBack";

const refundable: Refundable = {
  refundable: 800,
  parts: [
    { paymentOrderId: "p1", method: "UPI", paidAt: "2026-09-03T10:00:00Z", amount: 500 },
    { paymentOrderId: "p2", method: "CARD", paidAt: "2026-09-10T10:00:00Z", amount: 300 },
  ],
};
const recent: Refund[] = [{ id: "r1", amount: 200, status: "DONE", parts: [], createdAt: "2026-10-01T00:00:00Z" }];

beforeEach(() => { vi.clearAllMocks(); });

it("previews where the money goes back to", () => {
  render(<GetMoneyBack refundable={refundable} refunds={recent} />);
  expect(screen.getByText("You can get back ₹800")).toBeInTheDocument();
  expect(screen.getByText("₹500 back to your UPI payment of 3 Sept 2026")).toBeInTheDocument();
  expect(screen.getByText("₹300 back to your Card payment of 10 Sept 2026")).toBeInTheDocument();
  expect(screen.getByText("Refunds reach the original payment in 5–7 working days.")).toBeInTheDocument();
  expect(screen.getByLabelText("Amount (₹)")).toHaveValue(800);
  expect(screen.getByText("Done")).toBeInTheDocument();
});

it("explains the zero state", () => {
  render(<GetMoneyBack refundable={{ refundable: 0, parts: [] }} refunds={[]} />);
  expect(screen.getByText("Money added by bank adjustment or still held by running runs can't be returned here.")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Get money back" })).not.toBeInTheDocument();
});

it("shows the server's message when the amount is over the cap", async () => {
  previewRefund.mockResolvedValue({ error: "You can get back at most ₹800" });
  const user = userEvent.setup();
  render(<GetMoneyBack refundable={refundable} refunds={[]} />);
  const input = screen.getByLabelText("Amount (₹)");
  await user.clear(input);
  await user.type(input, "900");
  expect(await screen.findByText("You can get back at most ₹800")).toBeInTheDocument();
});

it("requests the refund and refreshes", async () => {
  requestRefund.mockResolvedValue({ refund: recent[0] });
  const user = userEvent.setup();
  render(<GetMoneyBack refundable={refundable} refunds={[]} />);
  await user.click(screen.getByRole("button", { name: "Get money back" }));
  expect(requestRefund).toHaveBeenCalledWith(800);
  expect(await screen.findByText("Refund requested.")).toBeInTheDocument();
  expect(refresh).toHaveBeenCalled();
});
