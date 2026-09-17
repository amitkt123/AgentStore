import { backend } from "@/lib/backend";
import type { AgentVersionDetailResponse } from "@/lib/types";
import { ConnectWizard } from "./ConnectWizard";

export default async function ConnectPage({
  params,
}: {
  params: Promise<{ agentId: string; versionId: string }>;
}) {
  const { agentId, versionId } = await params;
  const version = await backend.get<AgentVersionDetailResponse>(`/api/v1/agents/${agentId}/versions/${versionId}`);
  return <ConnectWizard agentId={agentId} manifest={version.manifest} />;
}
