import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

const approve = vi.hoisted(() => vi.fn());
const load = vi.hoisted(() => vi.fn());
vi.mock("@/app/(app)/admin/payouts/actions", () => ({
  approveAccountAction: (...a: unknown[]) => approve(...a),
  loadWithdrawalsAction: (...a: unknown[]) => load(...a),
}));
import { AdminPayoutsView } from "./AdminPayoutsView";
import type { AdminPayoutAccount, AdminWithdrawal } from "@/lib/api/types";

const account = (over: Partial<AdminPayoutAccount> = {}): AdminPayoutAccount => ({
  id: "a1", type: "BANK", accountLast4: "1234", ifsc: "HDFC0001", vpa: null, holderName: "A Dev", validatedName: "Amit K Tiwari",
  nameMatch: false, status: "FAILED", failureReason: "NAME_MISMATCH", createdAt: "2026-10-20T10:00:00Z", verifiedAt: null,
  orgId: "o1", orgName: "Dev Org", legalName: "Amit Tiwari", ...over,
});
const withdrawal = (over: Partial<AdminWithdrawal> = {}): AdminWithdrawal => ({
  id: "w1", amount: 2500, mode: "IMPS", status: "PROCESSING", utr: "UTR123", accountLabel: "Bank ••1234", failureReason: null,
  createdAt: "2026-10-21T10:00:00Z", processedAt: null, orgId: "o1", orgName: "Dev Org", ...over,
});

beforeEach(() => { approve.mockReset(); load.mockReset(); });

it("lists mismatched accounts with the legal name against the bank name", () => {
  render(<AdminPayoutsView accounts={[account()]} withdrawals={[]} />);
  const section = screen.getByRole("region", { name: "Accounts to review" });
  expect(within(section).getByText("Dev Org")).toBeInTheDocument();
  expect(within(section).getByText("Amit Tiwari")).toBeInTheDocument();
  expect(within(section).getByText("Amit K Tiwari")).toBeInTheDocument();
  expect(within(section).getByText(/••1234/)).toBeInTheDocument();
});

it("shows an empty state with nothing to review", () => {
  render(<AdminPayoutsView accounts={[]} withdrawals={[]} />);
  expect(screen.getByText("No accounts need review.")).toBeInTheDocument();
});

it("needs a note to approve, then removes the account", async () => {
  approve.mockResolvedValue({ account: account({ status: "VERIFIED" }) });
  render(<AdminPayoutsView accounts={[account()]} withdrawals={[]} />);
  await userEvent.click(screen.getByRole("button", { name: "Approve" }));
  expect(approve).not.toHaveBeenCalled();
  expect(await screen.findByText("A note is required")).toBeInTheDocument();
  await userEvent.type(screen.getByLabelText("Note"), " Checked PAN card ");
  await userEvent.click(screen.getByRole("button", { name: "Approve" }));
  expect(approve).toHaveBeenCalledWith("a1", "Checked PAN card");
  expect(await screen.findByText("No accounts need review.")).toBeInTheDocument();
});

it("shows an approve error inline and keeps the account", async () => {
  approve.mockResolvedValue({ error: "Only an active payout account that failed on a name mismatch can be approved" });
  render(<AdminPayoutsView accounts={[account()]} withdrawals={[]} />);
  await userEvent.type(screen.getByLabelText("Note"), "ok");
  await userEvent.click(screen.getByRole("button", { name: "Approve" }));
  expect(await screen.findByText(/can be approved/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
});

it("lists withdrawals with org, amount, account, status, UTR and date", () => {
  render(<AdminPayoutsView accounts={[]} withdrawals={[withdrawal()]} />);
  const table = within(screen.getByRole("region", { name: "Withdrawals" })).getByRole("table");
  for (const text of ["Dev Org", "₹2,500.00", "Bank ••1234", "Processing", "UTR123", "21 Oct 2026"]) {
    expect(within(table).getByText(text)).toBeInTheDocument();
  }
});

it("defaults the filter to open withdrawals and re-queries when it changes", async () => {
  load.mockResolvedValue({ withdrawals: [withdrawal({ id: "w2", status: "PROCESSED", utr: "UTR999" })] });
  render(<AdminPayoutsView accounts={[]} withdrawals={[withdrawal()]} />);
  const filter = screen.getByLabelText("Status");
  expect(filter).toHaveValue("OPEN");
  await userEvent.selectOptions(filter, "PROCESSED");
  expect(load).toHaveBeenCalledWith("PROCESSED");
  expect(await screen.findByText("UTR999")).toBeInTheDocument();
  expect(screen.queryByText("UTR123")).not.toBeInTheDocument();
});

it("shows a filter error and keeps the list", async () => {
  load.mockResolvedValue({ error: "boom" });
  render(<AdminPayoutsView accounts={[]} withdrawals={[withdrawal()]} />);
  await userEvent.selectOptions(screen.getByLabelText("Status"), "FAILED");
  expect(await screen.findByText("boom")).toBeInTheDocument();
  expect(screen.getByText("UTR123")).toBeInTheDocument();
});

it("shows a generic message when an action throws", async () => {
  load.mockRejectedValue(new Error("network"));
  render(<AdminPayoutsView accounts={[]} withdrawals={[]} />);
  await userEvent.selectOptions(screen.getByLabelText("Status"), "ALL");
  expect(await screen.findByText("Something went wrong. Try again.")).toBeInTheDocument();
});
