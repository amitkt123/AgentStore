import { getOrgMoney, getPlatformAccounts } from "@/lib/api/money";
import { ApiError } from "@/lib/api/http";
import { AdminMoneyView } from "@/features/adminmoney/AdminMoneyView";
import { ErrorState } from "@/ui/ErrorState";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default async function Page({ searchParams }: { searchParams: { org?: string } }) {
  const orgQuery = searchParams.org?.trim() || undefined;
  try {
    const accounts = await getPlatformAccounts();
    let org = null;
    let lookupError: string | undefined;
    if (orgQuery) {
      if (!UUID_RE.test(orgQuery)) {
        lookupError = "Org id must be a UUID";
      } else {
        try { org = await getOrgMoney(orgQuery); } catch (e) {
          if (e instanceof ApiError && e.status === 404) lookupError = "No org with that id";
          else throw e;
        }
      }
    }
    return (
      <>
        <h1>Money</h1>
        <AdminMoneyView key={org?.orgId ?? "none"} accounts={accounts} org={org} orgQuery={orgQuery} lookupError={lookupError} />
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.message} />;
    throw e;
  }
}
