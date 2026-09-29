import { ApiError } from "@/lib/api/http";
import { getVersion } from "@/lib/api/catalog";
import { getSession } from "@/lib/session";
import { canCreateConnections } from "@/lib/roles";
import { StoreFlow } from "@/features/flow/StoreFlow";
import { ErrorState } from "@/ui/ErrorState";

export default async function Page({ params }: { params: { agentId: string; versionId: string } }) {
  const session = (await getSession())!;
  try {
    const detail = await getVersion(params.agentId, params.versionId);
    return <StoreFlow agentId={params.agentId} versionId={params.versionId} detail={detail} canConnect={canCreateConnections(session.role)} />;
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.status === 404 ? "We can't find that agent version." : e.message} />;
    throw e;
  }
}
