import { ApiError } from "@/lib/api/http";
import { getVersion } from "@/lib/api/catalog";
import { listDatasets } from "@/lib/api/datasets";
import { getSession } from "@/lib/session";
import { canCreateConnections } from "@/lib/roles";
import { ConnectForm } from "@/features/sockets/ConnectForm";
import { ErrorState } from "@/ui/ErrorState";

export default async function Page({ params }: { params: { agentId: string; versionId: string } }) {
  const session = (await getSession())!;
  try {
    const [version, datasets] = await Promise.all([getVersion(params.agentId, params.versionId), listDatasets()]);
    return (
      <>
        <h1>Plug your files into {version.agentName}</h1>
        <p>Each socket takes one file. Karya checks the columns before the agent ever sees it.</p>
        <ConnectForm
          agentId={params.agentId}
          versionId={params.versionId}
          agentName={version.agentName}
          manifest={version.manifest}
          datasets={datasets}
          canConnect={canCreateConnections(session.role)}
        />
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.status === 404 ? "We can't find that agent version." : e.message} />;
    throw e;
  }
}
