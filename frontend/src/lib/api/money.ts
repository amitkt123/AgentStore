import { api, json } from "./http";
import type {
  AdjustmentBody, BillingSummary, Earnings, OrgMoney, OrgMoneyPatch, PlatformAccounts, Wallet,
} from "./types";

const withMonth = (path: string, month?: string) => (month ? `${path}?month=${encodeURIComponent(month)}` : path);

export const getWallet = (orgId: string) => api<Wallet>(`/api/v1/orgs/${orgId}/wallet`);
export const getBillingSummary = (orgId: string, month?: string) =>
  api<BillingSummary>(withMonth(`/api/v1/orgs/${orgId}/billing/summary`, month));
export const getEarnings = (month?: string) => api<Earnings>(withMonth("/api/v1/developer/earnings", month));
export const statementUrl = (month: string) => `/api/v1/developer/statements/${month}/download`;
export const getOrgMoney = (orgId: string) => api<OrgMoney>(`/api/v1/admin/orgs/${orgId}/money`);
export const patchOrgMoney = (orgId: string, body: OrgMoneyPatch) =>
  api<OrgMoney>(`/api/v1/admin/orgs/${orgId}/money`, { method: "PATCH", body: json(body) });
export const getPlatformAccounts = () => api<PlatformAccounts>("/api/v1/admin/ledger/accounts");
export const postAdjustment = (orgId: string, body: AdjustmentBody) =>
  api<Wallet>(`/api/v1/admin/orgs/${orgId}/wallet/adjustments`, { method: "POST", body: json(body) });
