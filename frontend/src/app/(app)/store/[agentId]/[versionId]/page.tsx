import { ApiError } from "@/lib/api/http";
import { getVersion } from "@/lib/api/catalog";
import { listMyConnections } from "@/lib/api/connections";
import { getSession } from "@/lib/session";
import { canCreateConnections } from "@/lib/roles";
import { StoreFlow } from "@/features/flow/StoreFlow";
import { ErrorState } from "@/ui/ErrorState";

export default async function Page({ params }: { params: { agentId: string; versionId: string } }) {
  const session = (await getSession())!;
  try {
    const [detail, connections] = await Promise.all([getVersion(params.agentId, params.versionId), listMyConnections().catch(() => [])]);
    const existing = connections.find((c) => c.agentId === params.agentId && c.status === "ACTIVE");
    return <StoreFlow agentId={params.agentId} versionId={params.versionId} detail={detail} canConnect={canCreateConnections(session.role)} connectionId={existing?.id ?? null} />;
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.status === 404 ? "We can't find that agent version." : e.message} />;
    throw e;
  }
}
