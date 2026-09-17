import { backend } from "@/lib/backend";
import type { AgentVersionDetailResponse } from "@/lib/types";
import { ReviewStatusView } from "./ReviewStatusView";

export default async function ReviewStatusPage({
  params,
}: {
  params: Promise<{ agentId: string; versionId: string }>;
}) {
  const { agentId, versionId } = await params;
  const version = await backend.get<AgentVersionDetailResponse>(`/api/v1/agents/${agentId}/versions/${versionId}`);
  return <ReviewStatusView version={version} />;
}
