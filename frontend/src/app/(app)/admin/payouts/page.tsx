import { fetchWithdrawals, listAdminPayoutAccounts } from "@/lib/api/money";
import { ApiError } from "@/lib/api/http";
import { AdminPayoutsView } from "@/features/adminpayouts/AdminPayoutsView";
import { ErrorState } from "@/ui/ErrorState";

export default async function Page() {
  try {
    const [accounts, withdrawals] = await Promise.all([listAdminPayoutAccounts("FAILED"), fetchWithdrawals("OPEN")]);
    // Only a name mismatch can be approved; other failures are the developer's to fix.
    return (
      <>
        <h1>Payouts</h1>
        <AdminPayoutsView accounts={accounts.filter((a) => a.failureReason === "NAME_MISMATCH")} withdrawals={withdrawals} />
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.message} />;
    throw e;
  }
}
