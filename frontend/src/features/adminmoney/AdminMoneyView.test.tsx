import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

const save = vi.hoisted(() => vi.fn());
const adjust = vi.hoisted(() => vi.fn());
vi.mock("@/app/(app)/admin/money/actions", () => ({
  saveSettingsAction: (...a: unknown[]) => save(...a),
  adjustWalletAction: (...a: unknown[]) => adjust(...a),
}));
import { AdminMoneyView } from "./AdminMoneyView";
import type { OrgMoney, PlatformAccounts } from "@/lib/api/types";

const ORG = "11111111-1111-4111-8111-111111111111";
const accounts: PlatformAccounts = {
  accounts: [{ kind: "PLATFORM_REVENUE", balance: 1500 }, { kind: "EXTERNAL", balance: -1500.5 }],
  totalsCheck: true,
};
const org = (over: Partial<OrgMoney> = {}): OrgMoney => ({
  orgId: ORG, name: "Acme Buyers", type: "BUYER", feePercent: null, effectiveFeePercent: 15,
  billingMode: null, effectiveBillingMode: "PREPAID", creditLimit: null, walletBalance: 250, payableBalance: 0, ...over,
});

beforeEach(() => { save.mockReset(); adjust.mockReset(); });

it("renders platform balances formatted", () => {
  render(<AdminMoneyView accounts={accounts} org={null} />);
  expect(screen.getByText("PLATFORM_REVENUE")).toBeInTheDocument();
  expect(screen.getByText("₹1,500.00")).toBeInTheDocument();
  expect(screen.getByText("-₹1,500.50")).toBeInTheDocument();
  expect(screen.queryByText("Ledger does not balance")).not.toBeInTheDocument();
});

it("warns when the ledger does not balance", () => {
  render(<AdminMoneyView accounts={{ ...accounts, totalsCheck: false }} org={null} />);
  expect(screen.getByRole("alert")).toHaveTextContent("Ledger does not balance");
});

it("shows a lookup message and no forms when the org id is rejected", () => {
  render(<AdminMoneyView accounts={accounts} org={null} orgQuery="nope" lookupError="Org id must be a UUID" />);
  expect(screen.getByText("Org id must be a UUID")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Save settings" })).not.toBeInTheDocument();
});

it("shows the org settings", () => {
  render(<AdminMoneyView accounts={accounts} org={org()} orgQuery={ORG} />);
  expect(screen.getByText("Acme Buyers")).toBeInTheDocument();
  expect(screen.getByLabelText("Fee %")).toHaveValue(null);
  expect(screen.getByLabelText("Billing mode")).toHaveValue("PREPAID");
});

it("posts the entered values through the action", async () => {
  save.mockResolvedValue({ org: org({ feePercent: 12.5, effectiveFeePercent: 12.5, billingMode: "POSTPAID", effectiveBillingMode: "POSTPAID", creditLimit: 5000 }) });
  render(<AdminMoneyView accounts={accounts} org={org()} orgQuery={ORG} />);
  await userEvent.type(screen.getByLabelText("Fee %"), "12.5");
  await userEvent.selectOptions(screen.getByLabelText("Billing mode"), "POSTPAID");
  await userEvent.type(screen.getByLabelText("Credit limit"), "5000");
  await userEvent.click(screen.getByRole("button", { name: "Save settings" }));
  expect(save).toHaveBeenCalledWith(ORG, { feePercent: 12.5, billingMode: "POSTPAID", creditLimit: 5000 });
  expect(await screen.findByText("Saved")).toBeInTheDocument();
});

it("shows a 409 message next to the settings form", async () => {
  save.mockResolvedValue({ error: "Org has an outstanding balance" });
  render(<AdminMoneyView accounts={accounts} org={org()} orgQuery={ORG} />);
  await userEvent.selectOptions(screen.getByLabelText("Billing mode"), "POSTPAID");
  await userEvent.click(screen.getByRole("button", { name: "Save settings" }));
  expect(await screen.findByText("Org has an outstanding balance")).toBeInTheDocument();
});

it("offers wallet adjustment for buyer orgs only and submits it", async () => {
  adjust.mockResolvedValue({ org: org({ walletBalance: 350 }) });
  const { unmount } = render(<AdminMoneyView accounts={accounts} org={org()} orgQuery={ORG} />);
  await userEvent.type(screen.getByLabelText("Amount"), "100");
  await userEvent.type(screen.getByLabelText("Note"), "goodwill");
  await userEvent.click(screen.getByRole("button", { name: "Adjust wallet" }));
  expect(adjust).toHaveBeenCalledWith(ORG, 100, "goodwill", expect.stringMatching(/^[0-9a-f-]{36}$/));
  expect(await screen.findByText("Wallet adjusted")).toBeInTheDocument();
  unmount();
  render(<AdminMoneyView accounts={accounts} org={org({ type: "DEVELOPER" })} orgQuery={ORG} />);
  expect(screen.queryByRole("button", { name: "Adjust wallet" })).not.toBeInTheDocument();
});

it("shows an adjustment error inline", async () => {
  adjust.mockResolvedValue({ error: "Debit exceeds the available balance of 250.00" });
  render(<AdminMoneyView accounts={accounts} org={org()} orgQuery={ORG} />);
  await userEvent.type(screen.getByLabelText("Amount"), "-900");
  await userEvent.type(screen.getByLabelText("Note"), "fix");
  await userEvent.click(screen.getByRole("button", { name: "Adjust wallet" }));
  expect(await screen.findByText("Debit exceeds the available balance of 250.00")).toBeInTheDocument();
});

const typeAdjust = async (amount: string, note: string) => {
  const a = screen.getByLabelText("Amount"), n = screen.getByLabelText("Note");
  await userEvent.clear(a); await userEvent.type(a, amount);
  await userEvent.clear(n); await userEvent.type(n, note);
};

it("reuses the requestId when retrying after an error, and uses a new one after success", async () => {
  adjust.mockResolvedValueOnce({ error: "boom" }).mockResolvedValueOnce({ org: org({ walletBalance: 350 }) }).mockResolvedValueOnce({ org: org() });
  render(<AdminMoneyView accounts={accounts} org={org()} orgQuery={ORG} />);
  await typeAdjust("100", "a");
  await userEvent.click(screen.getByRole("button", { name: "Adjust wallet" }));
  await screen.findByText("boom");
  await userEvent.click(screen.getByRole("button", { name: "Adjust wallet" }));
  await screen.findByText("Wallet adjusted");
  await typeAdjust("100", "a");
  await userEvent.click(screen.getByRole("button", { name: "Adjust wallet" }));
  await screen.findByText("Wallet adjusted");
  const ids = adjust.mock.calls.map((c) => c[3]);
  expect(ids[0]).toBe(ids[1]);
  expect(ids[2]).not.toBe(ids[1]);
});

it("uses a new requestId after the amount or note changes", async () => {
  adjust.mockResolvedValue({ error: "boom" });
  render(<AdminMoneyView accounts={accounts} org={org()} orgQuery={ORG} />);
  await typeAdjust("100", "a");
  await userEvent.click(screen.getByRole("button", { name: "Adjust wallet" }));
  await screen.findByText("boom");
  await typeAdjust("200", "a");
  await userEvent.click(screen.getByRole("button", { name: "Adjust wallet" }));
  await userEvent.click(screen.getByRole("button", { name: "Adjust wallet" }));
  expect(adjust.mock.calls[1][3]).not.toBe(adjust.mock.calls[0][3]);
  expect(adjust.mock.calls[2][3]).toBe(adjust.mock.calls[1][3]);
});

it("shows a generic error when an action throws", async () => {
  save.mockRejectedValue(new Error("network"));
  render(<AdminMoneyView accounts={accounts} org={org()} orgQuery={ORG} />);
  await userEvent.selectOptions(screen.getByLabelText("Billing mode"), "POSTPAID");
  await userEvent.click(screen.getByRole("button", { name: "Save settings" }));
  expect(await screen.findByText("Something went wrong; try again")).toBeInTheDocument();
});

it("tells the admin to refresh when the post succeeded but the refetch did not", async () => {
  adjust.mockResolvedValue({ org: null, notice: "Adjustment saved; refresh to see the new balance" });
  render(<AdminMoneyView accounts={accounts} org={org()} orgQuery={ORG} />);
  await typeAdjust("100", "a");
  await userEvent.click(screen.getByRole("button", { name: "Adjust wallet" }));
  expect(await screen.findByText("Adjustment saved; refresh to see the new balance")).toBeInTheDocument();
});
