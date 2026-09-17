import { backend } from "@/lib/backend";
import { SubmissionWizard } from "@/components/SubmissionWizard";
import type { MyAgentsResponse } from "@/lib/types";

export default async function NewVersionPage({ params }: { params: Promise<{ agentId: string }> }) {
  const { agentId } = await params;
  const agents = await backend.get<MyAgentsResponse[]>("/api/v1/agents/mine");
  const agent = agents.find((a) => a.agentId === agentId);
  if (!agent) return <p className="text-red">Agent not found.</p>;
  return <SubmissionWizard existingAgent={{ id: agent.agentId, name: agent.agentName, category: agent.category }} />;
}
