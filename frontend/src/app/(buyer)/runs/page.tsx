import { backend } from "@/lib/backend";
import type { ConnectionSummaryResponse, RunSummaryResponse } from "@/lib/types";
import { RunsList } from "./RunsList";
import { StartRun } from "./StartRun";

export default async function RunsPage() {
  const [runs, connections] = await Promise.all([
    backend.get<RunSummaryResponse[]>("/api/v1/runs/mine"),
    backend.get<ConnectionSummaryResponse[]>("/api/v1/connections/mine"),
  ]);
  return (
    <div>
      <h1 className="font-serif text-2xl mb-6">Runs</h1>
      <StartRun connections={connections} />
      <RunsList runs={runs} />
    </div>
  );
}
