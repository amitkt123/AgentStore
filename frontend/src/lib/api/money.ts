import { api, json } from "./http";
import type {
  AdjustmentBody, BankTransferDetails, Bill, BillingSummary, Checkout, Earnings, OrgMoney, OrgMoneyPatch, PaymentMethod,
  PaymentOptions, PaymentOrder, PlatformAccounts, Refund, Refundable, SimulateOutcome, Wallet,
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
