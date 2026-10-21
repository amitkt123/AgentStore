import { vi } from "vitest";
vi.mock("next/headers", () => ({ cookies: () => ({ get: () => ({ value: "tok" }) }) }));
vi.mock("next/navigation", () => ({ redirect: vi.fn() }));
import {
  getBillingSummary, getEarnings, getOrgMoney, getPlatformAccounts, getWallet, patchOrgMoney, postAdjustment, statementUrl,
} from "./money";

beforeEach(() => { process.env.BACKEND_URL = "http://be"; });

const stub = (body: unknown = {}) => {
  const f = vi.fn().mockImplementation(async () => new Response(JSON.stringify(body), { status: 200 }));
  vi.stubGlobal("fetch", f);
  return f;
};

it("reads the wallet", async () => {
  const f = stub({ mode: "PREPAID" });
  expect(await getWallet("o1")).toEqual({ mode: "PREPAID" });
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/orgs/o1/wallet");
});

it("reads the billing summary with and without a month", async () => {
  const f = stub();
  await getBillingSummary("o1");
  await getBillingSummary("o1", "2026-09");
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/orgs/o1/billing/summary");
  expect(f.mock.calls[1][0]).toBe("http://be/api/v1/orgs/o1/billing/summary?month=2026-09");
});

it("reads earnings and builds the statement path", async () => {
  const f = stub();
  await getEarnings("2026-09");
  await getEarnings();
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/developer/earnings?month=2026-09");
  expect(f.mock.calls[1][0]).toBe("http://be/api/v1/developer/earnings");
  expect(statementUrl("2026-09")).toBe("/api/developer/statements/2026-09");
});

it("reads and patches org money settings", async () => {
  const f = stub();
  await getOrgMoney("o1");
  await patchOrgMoney("o1", { feePercent: 12.5, billingMode: "POSTPAID" });
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/admin/orgs/o1/money");
  expect(f.mock.calls[1][0]).toBe("http://be/api/v1/admin/orgs/o1/money");
  expect(f.mock.calls[1][1].method).toBe("PATCH");
  expect(JSON.parse(f.mock.calls[1][1].body)).toEqual({ feePercent: 12.5, billingMode: "POSTPAID" });
});

it("reads platform accounts", async () => {
  const body = { accounts: [], totalsCheck: true, balancesMatchPostings: true, walletsMatchLedger: false };
  const f = stub(body);
  expect(await getPlatformAccounts()).toEqual(body);
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/admin/ledger/accounts");
});

it("posts a wallet adjustment", async () => {
  const f = stub();
  await postAdjustment("o1", { requestId: "r1", amount: 500, note: "top up" });
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/admin/orgs/o1/wallet/adjustments");
  expect(f.mock.calls[0][1].method).toBe("POST");
  expect(JSON.parse(f.mock.calls[0][1].body)).toEqual({ requestId: "r1", amount: 500, note: "top up" });
});
