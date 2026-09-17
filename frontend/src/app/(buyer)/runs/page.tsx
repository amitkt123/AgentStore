import { backend } from "@/lib/backend";
import { getSession } from "@/lib/session";
import type { ConnectionSummaryResponse, RunSummaryResponse, WalletResponse } from "@/lib/types";
import { RunsList } from "./RunsList";
import { StartRun } from "./StartRun";

export default async function RunsPage({ searchParams }: { searchParams: Promise<{ error?: string }> }) {
  const { error } = await searchParams;
  const session = await getSession();
  if (!session) return null;

  const [runs, connections, wallet] = await Promise.all([
    backend.get<RunSummaryResponse[]>("/api/v1/runs/mine"),
    backend.get<ConnectionSummaryResponse[]>("/api/v1/connections/mine"),
    backend.get<WalletResponse>(`/api/v1/orgs/${session.orgId}/wallet`),
  ]);
  return (
    <div>
      <h1 className="font-serif text-2xl mb-6">Runs</h1>
      {error === "insufficient-balance" && (
        <div className="mb-4 border border-red bg-redSoft text-red rounded-lg p-3 text-sm">
          Could not start that run — your organization's wallet balance is too low for this connection's spend cap.
        </div>
      )}
      <StartRun connections={connections} walletBalance={wallet.balance} />
      <RunsList runs={runs} />
    </div>
  );
}
