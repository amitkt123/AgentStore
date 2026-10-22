import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));
const withdraw = vi.fn();
vi.mock("@/app/(app)/developer/earnings/actions", () => ({ withdraw: (...a: unknown[]) => withdraw(...a) }));

import { WithdrawForm } from "./WithdrawForm";

const props = { available: 6000.5, dailyLimitLeft: 5000, accountLabel: "Bank ••1234" };

beforeEach(() => { vi.clearAllMocks(); });

it("prefills the most that can be withdrawn today", () => {
  render(<WithdrawForm {...props} />);
  expect(screen.getByLabelText("Amount (₹)")).toHaveValue(5000);
});

it("disables Withdraw when the amount is over what is available or under zero", async () => {
  const user = userEvent.setup();
  render(<WithdrawForm {...props} available={800} />);
  const input = screen.getByLabelText("Amount (₹)");
  await user.clear(input);
  await user.type(input, "900");
  expect(screen.getByRole("button", { name: "Withdraw" })).toBeDisabled();
  expect(screen.getByText("You can withdraw up to ₹800.00 now")).toBeInTheDocument();
  await user.clear(input);
  expect(screen.getByRole("button", { name: "Withdraw" })).toBeDisabled();
});

it("asks for confirmation naming the account before sending", async () => {
  withdraw.mockResolvedValue({ withdrawal: {} });
  const user = userEvent.setup();
  render(<WithdrawForm {...props} />);
  await user.click(screen.getByRole("button", { name: "Withdraw" }));
  expect(screen.getByText("Send ₹5,000.00 to Bank ••1234?")).toBeInTheDocument();
  expect(withdraw).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "Confirm" }));
  expect(withdraw).toHaveBeenCalledWith(5000, expect.stringMatching(/^[0-9a-f-]{36}$/));
  expect(await screen.findByText("Withdrawal requested.")).toBeInTheDocument();
  expect(refresh).toHaveBeenCalled();
});

it("can go back from the confirmation", async () => {
  const user = userEvent.setup();
  render(<WithdrawForm {...props} />);
  await user.click(screen.getByRole("button", { name: "Withdraw" }));
  await user.click(screen.getByRole("button", { name: "Back" }));
  expect(screen.getByRole("button", { name: "Withdraw" })).toBeInTheDocument();
});

it("reuses the request id on a repeated submit and uses a new one for a new amount", async () => {
  withdraw.mockResolvedValue({ error: "The payout provider is unavailable" });
  const user = userEvent.setup();
  render(<WithdrawForm {...props} />);
  await user.click(screen.getByRole("button", { name: "Withdraw" }));
  await user.click(screen.getByRole("button", { name: "Confirm" }));
  expect(await screen.findByText("The payout provider is unavailable")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Confirm" }));
  const [first, second] = withdraw.mock.calls.map((c) => c[1]);
  expect(second).toBe(first);

  await user.click(screen.getByRole("button", { name: "Back" }));
  const input = screen.getByLabelText("Amount (₹)");
  await user.clear(input);
  await user.type(input, "300");
  await user.click(screen.getByRole("button", { name: "Withdraw" }));
  await user.click(screen.getByRole("button", { name: "Confirm" }));
  expect(withdraw).toHaveBeenLastCalledWith(300, expect.any(String));
  expect(withdraw.mock.calls[2][1]).not.toBe(first);
});
