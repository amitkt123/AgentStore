import { backend } from "@/lib/backend";
import type { AgentVersionDetailResponse } from "@/lib/types";
import { AgentDetailView } from "./AgentDetailView";

export default async function AgentDetailPage({
  params,
}: {
  params: Promise<{ agentId: string; versionId: string }>;
}) {
  const { agentId, versionId } = await params;
  const version = await backend.get<AgentVersionDetailResponse>(`/api/v1/agents/${agentId}/versions/${versionId}`);
  return <AgentDetailView version={version} />;
}
