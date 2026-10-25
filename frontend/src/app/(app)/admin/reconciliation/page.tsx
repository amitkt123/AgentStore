import { listReconciliationIssues } from "@/lib/api/money";
import { ApiError } from "@/lib/api/http";
import { ReconciliationView } from "@/features/adminreconciliation/ReconciliationView";
import { ErrorState } from "@/ui/ErrorState";

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
const yesterdayUtc = () => new Date(Date.now() - 24 * 3600 * 1000).toISOString().slice(0, 10);

export default async function Page({ searchParams }: { searchParams: { date?: string } }) {
  const date = searchParams.date && DATE_RE.test(searchParams.date) ? searchParams.date : yesterdayUtc();
  try {
    const issues = await listReconciliationIssues(date);
    return (
      <>
        <h1>Reconciliation</h1>
        <ReconciliationView date={date} issues={issues} />
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.message} />;
    throw e;
  }
}
