import { backend, BackendError } from "@/lib/backend";
import { getSession } from "@/lib/session";
import type { ConnectionSummaryResponse, RunSummaryResponse, WalletResponse } from "@/lib/types";
import { RunsList } from "./RunsList";
import { StartRun } from "./StartRun";

export default async function RunsPage({ searchParams }: { searchParams: Promise<{ error?: string }> }) {
  const { error } = await searchParams;
  const session = await getSession();
  if (!session) return null;

  // Only BUYER_ADMIN can read the org wallet (matches WalletController's
  // role check); other roles, and orgs with no wallet row yet, fall back
  // to an unknown balance rather than crashing the page.
  const walletPromise: Promise<WalletResponse | null> =
    session.role === "BUYER_ADMIN"
      ? backend.get<WalletResponse>(`/api/v1/orgs/${session.orgId}/wallet`).catch((e) => {
          if (e instanceof BackendError) return null;
          throw e;
        })
      : Promise.resolve(null);

  const [runs, connections, wallet] = await Promise.all([
    backend.get<RunSummaryResponse[]>("/api/v1/runs/mine"),
    backend.get<ConnectionSummaryResponse[]>("/api/v1/connections/mine"),
    walletPromise,
  ]);
  return (
    <div>
      <h1 className="font-serif text-2xl mb-6">Runs</h1>
      {error === "insufficient-balance" && (
        <div className="mb-4 border border-red bg-redSoft text-red rounded-lg p-3 text-sm">
          Could not start that run — your organization's wallet balance is too low for this connection's spend cap.
        </div>
      )}
      <StartRun connections={connections} walletBalance={wallet?.balance ?? null} />
      <RunsList runs={runs} />
    </div>
  );
}
