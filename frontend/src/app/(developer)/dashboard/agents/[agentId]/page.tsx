import { backend } from "@/lib/backend";
import type { MyAgentsResponse } from "@/lib/types";
import { ManageView } from "./ManageView";

export default async function ManagePage({ params }: { params: Promise<{ agentId: string }> }) {
  const { agentId } = await params;
  const agents = await backend.get<MyAgentsResponse[]>("/api/v1/agents/mine");
  const agent = agents.find((a) => a.agentId === agentId);
  if (!agent) return <p className="text-red">Agent not found.</p>;
  return <ManageView agent={agent} />;
}
