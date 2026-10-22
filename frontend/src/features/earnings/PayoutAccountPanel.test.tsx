import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import type { PayoutAccount } from "@/lib/api/types";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));
const savePayoutAccount = vi.fn();
vi.mock("@/app/(app)/developer/earnings/actions", () => ({ savePayoutAccount: (...a: unknown[]) => savePayoutAccount(...a) }));

import { PayoutAccountPanel } from "./PayoutAccountPanel";

const base: PayoutAccount = {
  id: "a1", type: "BANK", accountLast4: "1234", ifsc: "HDFC0001234", vpa: null, holderName: "Asha Rao",
  validatedName: "ASHA RAO", nameMatch: true, status: "VERIFIED", failureReason: null,
  createdAt: "2026-10-01T00:00:00Z", verifiedAt: "2026-10-01T00:00:00Z",
};

beforeEach(() => { vi.clearAllMocks(); });

it("shows a verified bank account with the last 4 digits only", () => {
  const { container } = render(<PayoutAccountPanel account={base} attemptsLeft={3} />);
  expect(screen.getByText("Bank ••1234 · HDFC0001234")).toBeInTheDocument();
  expect(screen.getByText("Name on account: ASHA RAO")).toBeInTheDocument();
  expect(container.textContent!.replace("HDFC0001234", "")).not.toMatch(/\d{5,}/);
});

it("shows a verified UPI id", () => {
  render(<PayoutAccountPanel account={{ ...base, type: "VPA", accountLast4: null, ifsc: null, vpa: "asha@okhdfc" }} attemptsLeft={3} />);
  expect(screen.getByText("asha@okhdfc")).toBeInTheDocument();
});

it("says a pending account is being checked", () => {
  render(<PayoutAccountPanel account={{ ...base, status: "PENDING", validatedName: null, nameMatch: null }} attemptsLeft={2} />);
  expect(screen.getByText("Checking your account…")).toBeInTheDocument();
});

it("explains a name mismatch", () => {
  render(<PayoutAccountPanel account={{ ...base, status: "FAILED", failureReason: "NAME_MISMATCH", validatedName: "RAVI KUMAR", nameMatch: false }} attemptsLeft={2} />);
  expect(screen.getByText(
    "The bank says this account belongs to RAVI KUMAR, which does not match Asha Rao. Karya will review it, or add a different account.",
  )).toBeInTheDocument();
});

it("shows the reason and offers another account for other failures", async () => {
  const user = userEvent.setup();
  render(<PayoutAccountPanel account={{ ...base, status: "FAILED", failureReason: "Account closed", validatedName: null, nameMatch: null }} attemptsLeft={2} />);
  expect(screen.getByText("Account closed")).toBeInTheDocument();
  expect(screen.queryByLabelText("Account number")).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Try another account" }));
  expect(screen.getByLabelText("Account number")).toBeInTheDocument();
});

it("offers the form when there is no account, with tries left", () => {
  render(<PayoutAccountPanel account={null} attemptsLeft={2} />);
  expect(screen.getByText("Add payout account")).toBeInTheDocument();
  expect(screen.getByText("2 tries left today")).toBeInTheDocument();
});

it("validates IFSC and account number before sending", async () => {
  const user = userEvent.setup();
  render(<PayoutAccountPanel account={null} attemptsLeft={3} />);
  await user.type(screen.getByLabelText("Name on account"), "Asha Rao");
  await user.type(screen.getByLabelText("Account number"), "12");
  await user.type(screen.getByLabelText("IFSC"), "BAD");
  await user.click(screen.getByRole("button", { name: "Verify account" }));
  expect(screen.getByText("Enter a valid IFSC, like HDFC0001234")).toBeInTheDocument();
  expect(screen.getByText("Enter 9 to 18 digits")).toBeInTheDocument();
  expect(savePayoutAccount).not.toHaveBeenCalled();
});

it("sends a valid bank account and refreshes", async () => {
  savePayoutAccount.mockResolvedValue({ account: base });
  const user = userEvent.setup();
  render(<PayoutAccountPanel account={null} attemptsLeft={3} />);
  await user.type(screen.getByLabelText("Name on account"), "Asha Rao");
  await user.type(screen.getByLabelText("Account number"), "123456789012");
  await user.type(screen.getByLabelText("IFSC"), "hdfc0001234");
  await user.click(screen.getByRole("button", { name: "Verify account" }));
  expect(savePayoutAccount).toHaveBeenCalledWith({ type: "BANK", accountNumber: "123456789012", ifsc: "HDFC0001234", holderName: "Asha Rao" });
  await vi.waitFor(() => expect(refresh).toHaveBeenCalled());
});

it("validates a UPI id and sends it", async () => {
  savePayoutAccount.mockResolvedValue({ account: base });
  const user = userEvent.setup();
  render(<PayoutAccountPanel account={null} attemptsLeft={3} />);
  await user.click(screen.getByRole("button", { name: "UPI" }));
  await user.type(screen.getByLabelText("Name on account"), "Asha Rao");
  await user.type(screen.getByLabelText("UPI ID"), "nope");
  await user.click(screen.getByRole("button", { name: "Verify account" }));
  expect(screen.getByText("Enter a valid UPI ID, like name@bank")).toBeInTheDocument();
  await user.clear(screen.getByLabelText("UPI ID"));
  await user.type(screen.getByLabelText("UPI ID"), "asha@okhdfc");
  await user.click(screen.getByRole("button", { name: "Verify account" }));
  expect(savePayoutAccount).toHaveBeenCalledWith({ type: "VPA", vpa: "asha@okhdfc", holderName: "Asha Rao" });
});

it("shows the server's message when saving fails, and blocks at zero tries", async () => {
  savePayoutAccount.mockResolvedValue({ error: "Too many tries today" });
  const user = userEvent.setup();
  const { rerender } = render(<PayoutAccountPanel account={null} attemptsLeft={1} />);
  expect(screen.getByText("1 try left today")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "UPI" }));
  await user.type(screen.getByLabelText("Name on account"), "Asha Rao");
  await user.type(screen.getByLabelText("UPI ID"), "asha@okhdfc");
  await user.click(screen.getByRole("button", { name: "Verify account" }));
  expect(await screen.findByText("Too many tries today")).toBeInTheDocument();
  rerender(<PayoutAccountPanel account={null} attemptsLeft={0} />);
  expect(screen.getByRole("button", { name: "Verify account" })).toBeDisabled();
});
