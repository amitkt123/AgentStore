import { ApiError } from "@/lib/api/http";
import { listRuns } from "@/lib/api/runs";
import { RunsView } from "@/features/runs/RunsView";
import { EmptyState } from "@/ui/EmptyState";
import { ErrorState } from "@/ui/ErrorState";

export default async function Page() {
  let initial;
  try {
    initial = await listRuns({});
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      return <EmptyState title="You don't have access to runs" body="Runs are visible to the buyers who own the workspace." />;
    }
    return <ErrorState message={e instanceof Error ? e.message : "Couldn't load runs."} />;
  }
  return (
    <>
      <h1>Runs</h1>
      <p>Every run your agents made, with the block each one reached.</p>
      <RunsView initial={initial} />
    </>
  );
}
