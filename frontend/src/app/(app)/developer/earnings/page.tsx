import { getEarnings } from "@/lib/api/money";
import { ApiError } from "@/lib/api/http";
import { parseMonth } from "@/lib/month";
import { EarningsView } from "@/features/earnings/EarningsView";
import { ErrorState } from "@/ui/ErrorState";

export default async function Page({ searchParams }: { searchParams: { month?: string } }) {
  try {
    const earnings = await getEarnings(parseMonth(searchParams.month));
    return (
      <>
        <h1>Earnings</h1>
        <EarningsView earnings={earnings} />
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.message} />;
    throw e;
  }
}
