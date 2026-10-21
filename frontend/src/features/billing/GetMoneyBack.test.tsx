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

it("requests the refund, refreshes, then clears the amount so it cannot be sent twice", async () => {
  requestRefund.mockResolvedValue({ refund: recent[0] });
  const user = userEvent.setup();
  render(<GetMoneyBack refundable={refundable} refunds={[]} />);
  await user.click(screen.getByRole("button", { name: "Get money back" }));
  expect(requestRefund).toHaveBeenCalledWith(800, expect.stringMatching(/^[0-9a-f-]{36}$/));
  expect(await screen.findByText("Refund requested.")).toBeInTheDocument();
  expect(refresh).toHaveBeenCalled();
  expect(screen.getByLabelText("Amount (₹)")).toHaveValue(null);
  expect(screen.getByRole("button", { name: "Get money back" })).toBeDisabled();
});

it("retries a failed request with the same request id, and uses a new one for a new amount", async () => {
  requestRefund.mockResolvedValueOnce({ error: "The payment provider is unavailable" })
    .mockResolvedValueOnce({ error: "The payment provider is unavailable" })
    .mockResolvedValue({ refund: recent[0] });
  previewRefund.mockResolvedValue({ refundable: { refundable: 800, parts: [refundable.parts[0]] } });
  const user = userEvent.setup();
  render(<GetMoneyBack refundable={refundable} refunds={[]} />);
  const button = screen.getByRole("button", { name: "Get money back" });
  await user.click(button);
  expect(await screen.findByText("The payment provider is unavailable")).toBeInTheDocument();
  await user.click(button);
  const [first, second] = requestRefund.mock.calls.map((c) => c[1]);
  expect(second).toBe(first);

  const input = screen.getByLabelText("Amount (₹)");
  await user.clear(input);
  await user.type(input, "500");
  await screen.findByText("₹500 back to your UPI payment of 3 Sept 2026");
  await user.click(screen.getByRole("button", { name: "Get money back" }));
  expect(requestRefund).toHaveBeenLastCalledWith(500, expect.any(String));
  expect(requestRefund.mock.calls[2][1]).not.toBe(first);
});

it("keeps confirm disabled while the preview for a new amount is pending", async () => {
  let resolve!: (v: unknown) => void;
  previewRefund.mockReturnValue(new Promise((r) => { resolve = r; }));
  const user = userEvent.setup();
  render(<GetMoneyBack refundable={refundable} refunds={[]} />);
  const input = screen.getByLabelText("Amount (₹)");
  await user.clear(input);
  await user.type(input, "500");
  expect(screen.getByRole("button", { name: "Get money back" })).toBeDisabled();
  await vi.waitFor(() => expect(previewRefund).toHaveBeenCalledWith(500));
  expect(screen.getByRole("button", { name: "Get money back" })).toBeDisabled();
  resolve({ refundable: { refundable: 800, parts: [refundable.parts[0]] } });
  await vi.waitFor(() => expect(screen.getByRole("button", { name: "Get money back" })).toBeEnabled());
});
