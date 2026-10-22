import { api, json } from "./http";
import type {
  AdjustmentBody, AdminPayoutAccount, AdminWithdrawal, ReconciliationIssue, WithdrawalStatus, BankTransferDetails, Bill, BillingSummary, Checkout, Earnings, OrgMoney, OrgMoneyPatch, PaymentMethod,
  PaymentOptions, PaymentOrder, PayoutAccount, PayoutAccountBody, PayoutSimulateOutcome, PayoutsSummary, PlatformAccounts, Refund, Refundable, SimulateOutcome, Wallet, Withdrawal,
} from "./types";

const withMonth = (path: string, month?: string) => (month ? `${path}?month=${encodeURIComponent(month)}` : path);

export const getWallet = (orgId: string) => api<Wallet>(`/api/v1/orgs/${orgId}/wallet`);
export const getBillingSummary = (orgId: string, month?: string) =>
  api<BillingSummary>(withMonth(`/api/v1/orgs/${orgId}/billing/summary`, month));
export const getEarnings = (month?: string) => api<Earnings>(withMonth("/api/v1/developer/earnings", month));
export const statementUrl = (month: string) => `/api/developer/statements/${month}`;
export const getOrgMoney = (orgId: string) => api<OrgMoney>(`/api/v1/admin/orgs/${orgId}/money`);
export const patchOrgMoney = (orgId: string, body: OrgMoneyPatch) =>
  api<OrgMoney>(`/api/v1/admin/orgs/${orgId}/money`, { method: "PATCH", body: json(body) });
export const getPlatformAccounts = () => api<PlatformAccounts>("/api/v1/admin/ledger/accounts");
export const postAdjustment = (orgId: string, body: AdjustmentBody) =>
  api<Wallet>(`/api/v1/admin/orgs/${orgId}/wallet/adjustments`, { method: "POST", body: json(body) });

const wallet = (orgId: string) => `/api/v1/orgs/${orgId}/wallet`;
export const getPaymentOptions = (orgId: string) => api<PaymentOptions>(`${wallet(orgId)}/payment-options`);
export const listTopUps = (orgId: string) => api<PaymentOrder[]>(`${wallet(orgId)}/topups`);
export const createTopUp = (orgId: string, body: { amount: number; method: PaymentMethod }) =>
  api<Checkout>(`${wallet(orgId)}/topups`, { method: "POST", body: json(body) });
export const getBankTransferDetails = (orgId: string) => api<BankTransferDetails>(`${wallet(orgId)}/bank-transfer-details`);
export const getRefundable = (orgId: string, amount?: number) =>
  api<Refundable>(`${wallet(orgId)}/refundable${amount === undefined ? "" : `?amount=${encodeURIComponent(amount)}`}`);
export const createRefund = (orgId: string, body: { requestId: string; amount: number }) =>
  api<Refund>(`${wallet(orgId)}/refunds`, { method: "POST", body: json(body) });
export const listRefunds = (orgId: string) => api<Refund[]>(`${wallet(orgId)}/refunds`);
export const listBills = (orgId: string) => api<Bill[]>(`/api/v1/orgs/${orgId}/bills`);
export const payBill = (orgId: string, billId: string, method: PaymentMethod) =>
  api<Checkout>(`/api/v1/orgs/${orgId}/bills/${billId}/pay`, { method: "POST", body: json({ method }) });
export const simulatePayment = (orderId: string, outcome: SimulateOutcome) =>
  api<{ outcome: string; order: PaymentOrder }>("/api/v1/dev/payments/simulate", { method: "POST", body: json({ orderId, outcome }) });

export const getPayoutsSummary = () => api<PayoutsSummary>("/api/v1/developer/payouts");
export const addPayoutAccount = (body: PayoutAccountBody) =>
  api<PayoutAccount>("/api/v1/developer/payout-account", { method: "POST", body: json(body) });
// 201 for a new withdrawal, 200 when the requestId was already used: both return the withdrawal.
export const createWithdrawal = (body: { requestId: string; amount: number }) =>
  api<Withdrawal>("/api/v1/developer/withdrawals", { method: "POST", body: json(body) });
export const listWithdrawals = () => api<Withdrawal[]>("/api/v1/developer/withdrawals?limit=50");
export const simulatePayout = (withdrawalId: string, outcome: PayoutSimulateOutcome) =>
  api<unknown>("/api/v1/dev/payouts/simulate", { method: "POST", body: json({ withdrawalId, outcome }) });

export const freezeOrg = (orgId: string, reason: string) =>
  api<OrgMoney>(`/api/v1/admin/orgs/${orgId}/money/freeze`, { method: "POST", body: json({ reason }) });
export const unfreezeOrg = (orgId: string) => api<OrgMoney>(`/api/v1/admin/orgs/${orgId}/money/unfreeze`, { method: "POST" });
export const listAdminWithdrawals = (status?: WithdrawalStatus) =>
  api<AdminWithdrawal[]>(`/api/v1/admin/withdrawals?limit=100${status ? `&status=${status}` : ""}`);
export const listAdminPayoutAccounts = (status: "FAILED" | "PENDING" | "VERIFIED" = "FAILED") =>
  api<AdminPayoutAccount[]>(`/api/v1/admin/payout-accounts?status=${status}&limit=100`);
export const approvePayoutAccount = (id: string, note: string) =>
  api<AdminPayoutAccount>(`/api/v1/admin/payout-accounts/${id}/approve`, { method: "POST", body: json({ note }) });

const recon = "/api/v1/admin/payments/reconciliation";
export const listReconciliationIssues = (date: string) => api<ReconciliationIssue[]>(`${recon}?date=${encodeURIComponent(date)}`);
export const runReconciliation = (date: string) =>
  api<Record<string, number>>(`${recon}/run?date=${encodeURIComponent(date)}`, { method: "POST" });
export const resolveReconciliationIssue = (id: string, note: string) =>
  api<{ id: string; resolved: boolean }>(`${recon}/${id}/resolve`, { method: "POST", body: json({ note }) });

export type WithdrawalFilter = "OPEN" | "ALL" | WithdrawalStatus;
// The API takes one status at a time: OPEN is REQUESTED and PROCESSING merged, newest first.
export async function fetchWithdrawals(filter: WithdrawalFilter): Promise<AdminWithdrawal[]> {
  if (filter === "ALL") return listAdminWithdrawals();
  if (filter !== "OPEN") return listAdminWithdrawals(filter);
  const lists = await Promise.all((["REQUESTED", "PROCESSING"] as const).map((s) => listAdminWithdrawals(s)));
  return lists.flat().sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}
