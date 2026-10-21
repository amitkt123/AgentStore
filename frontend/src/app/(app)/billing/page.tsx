import { getBillingSummary, getWallet } from "@/lib/api/money";
import { ApiError } from "@/lib/api/http";
import { getSession } from "@/lib/session";
import { BillingView } from "@/features/billing/BillingView";
import { parseMonth } from "@/lib/month";
import { ErrorState } from "@/ui/ErrorState";

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
    const [wallet, summary] = await Promise.all([getWallet(session.orgId), getBillingSummary(session.orgId, month)]);
    return (
      <>
        <h1>Billing</h1>
        <BillingView wallet={wallet} summary={summary} />
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.message} />;
    throw e;
  }
}
