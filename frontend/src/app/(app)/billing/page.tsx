import {
  getBillingSummary, getPaymentOptions, getRefundable, getWallet, listBills, listRefunds, listTopUps,
} from "@/lib/api/money";
import { ApiError } from "@/lib/api/http";
import { getSession } from "@/lib/session";
import { BillingView } from "@/features/billing/BillingView";
import { parseMonth } from "@/lib/month";
import { ErrorState } from "@/ui/ErrorState";

// The payment panels are optional: an API error hides that panel instead of failing the page.
async function optional<T>(call: () => Promise<T>): Promise<T | null> {
  try { return await call(); } catch (e) {
    if (e instanceof ApiError) return null;
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
    const [wallet, summary, options, topUps, refundable, refunds, bills] = await Promise.all([
      getWallet(orgId),
      getBillingSummary(orgId, month),
      optional(() => getPaymentOptions(orgId)),
      optional(() => listTopUps(orgId)),
      optional(() => getRefundable(orgId)),
      optional(() => listRefunds(orgId)),
      optional(() => listBills(orgId)),
    ]);
    const payments = { options, topUps, refundable, refunds, bills };
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
