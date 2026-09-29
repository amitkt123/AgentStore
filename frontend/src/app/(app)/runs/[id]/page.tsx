import { ApiError } from "@/lib/api/http";
import { getRunSnapshot } from "@/lib/api/runs";
import { getSession } from "@/lib/session";
import { canDecideGates } from "@/lib/roles";
import { LiveRun } from "@/features/runs/LiveRun";
import { ErrorState } from "@/ui/ErrorState";
import type { Manifest } from "@/lib/api/types";

export default async function Page({ params }: { params: { id: string } }) {
  const session = (await getSession())!;
  let snapshot;
  try {
    snapshot = await getRunSnapshot(params.id);
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.status === 404 ? "We can't find that run." : e.message} />;
    throw e;
  }
  // The flow already names the blocks; the manifest only adds titles and descriptions, so a failure here is not fatal.
  const manifest: Manifest | null = null;
  return <LiveRun runRef={params.id} initial={snapshot} manifest={manifest} canDecide={canDecideGates(session.role)} />;
}
