import {
  getBillingSummary, getPaymentOptions, getRefundable, getWallet, listBills, listRefunds, listTopUps,
} from "@/lib/api/money";
import { ApiError } from "@/lib/api/http";
import { getSession } from "@/lib/session";
import { BillingView, type PaymentPanels } from "@/features/billing/BillingView";
import { parseMonth } from "@/lib/month";
import { ErrorState } from "@/ui/ErrorState";

// The payment panels are optional: an API error drops that panel, names it in `failed`, and the page still renders.
async function optional<T>(call: () => Promise<T>, failed: string[], name: string): Promise<T | null> {
  try { return await call(); } catch (e) {
    if (e instanceof ApiError) { failed.push(name); return null; }
    throw e;
  }
}

export default async function Page({ searchParams }: { searchParams: { month?: string } }) {
  const session = (await getSession())!;
  if (session.role === "BUYER_APPROVER") {
    return (
      <>
        <h1>Billing</h1>
        <p>Only workspace admins can see billing.</p>
      </>
    );
  }
  try {
    const month = parseMonth(searchParams.month);
    const orgId = session.orgId;
    const failed: string[] = [];
    const [wallet, summary, options, topUps, refundable, refunds, bills] = await Promise.all([
      getWallet(orgId),
      getBillingSummary(orgId, month),
      optional(() => getPaymentOptions(orgId), failed, "options"),
      optional(() => listTopUps(orgId), failed, "topUps"),
      optional(() => getRefundable(orgId), failed, "refundable"),
      optional(() => listRefunds(orgId), failed, "refunds"),
      optional(() => listBills(orgId), failed, "bills"),
    ]);
    // A prepaid org never has bills, so a bills failure there is noise.
    const shown = failed.filter((k) => !(k === "bills" && wallet.mode === "PREPAID")) as NonNullable<PaymentPanels["failed"]>;
    const payments: PaymentPanels = { options, topUps, refundable, refunds, bills, failed: shown };
    return (
      <>
        <h1>Billing</h1>
        <BillingView wallet={wallet} summary={summary} payments={payments} />
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.message} />;
    throw e;
  }
}
