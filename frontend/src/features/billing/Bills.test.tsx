import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import type { Bill } from "@/lib/api/types";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));
const startBillPayment = vi.fn();
vi.mock("@/app/(app)/billing/actions", () => ({
  startBillPayment: (...a: unknown[]) => startBillPayment(...a),
  simulate: vi.fn(),
}));
vi.mock("./razorpayCheckout", () => ({ openRazorpayCheckout: vi.fn() }));

import { Bills } from "./Bills";

const bill = (id: string, status: Bill["status"]): Bill => ({
  id, period: "2026-09", amount: 1200, status, dueAt: "2026-10-10T00:00:00Z", paidAt: status === "PAID" ? "2026-10-02T00:00:00Z" : null,
});

beforeEach(() => { vi.clearAllMocks(); });

it("offers Pay only on unpaid bills", () => {
  render(<Bills bills={[bill("b1", "OPEN"), bill("b2", "PAID"), bill("b3", "OVERDUE")]} owed={3600} />);
  expect(screen.getAllByRole("button", { name: "Pay" })).toHaveLength(2);
});

it("warns when a bill is overdue", () => {
  render(<Bills bills={[bill("b3", "OVERDUE")]} owed={1200} />);
  expect(screen.getByText("A bill is overdue. New runs are paused until it's paid.")).toBeInTheDocument();
});

it("has no banner when nothing is overdue", () => {
  render(<Bills bills={[bill("b1", "OPEN")]} owed={1200} />);
  expect(screen.queryByText(/A bill is overdue/)).not.toBeInTheDocument();
});

it("pays with a method that is not bank transfer", async () => {
  startBillPayment.mockResolvedValue({ error: "Nope" });
  const user = userEvent.setup();
  render(<Bills bills={[bill("b1", "OPEN")]} owed={1200} />);
  await user.click(screen.getByRole("button", { name: "Pay" }));
  expect(screen.queryByRole("radio", { name: "Bank transfer" })).not.toBeInTheDocument();
  await user.click(screen.getByRole("radio", { name: "Card" }));
  await user.click(screen.getByRole("button", { name: "Pay ₹1,200.00" }));
  expect(startBillPayment).toHaveBeenCalledWith("b1", "CARD");
  expect(await screen.findByText("Nope")).toBeInTheDocument();
});
