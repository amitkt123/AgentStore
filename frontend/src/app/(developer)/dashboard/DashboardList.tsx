import Link from "next/link";
import { StatusPill } from "@/components/StatusPill";
import type { AgentVersionResponse, MyAgentsResponse } from "@/lib/types";

function linkFor(agentId: string, version: AgentVersionResponse): string {
  if (version.status === "IN_REVIEW") return `/dashboard/agents/${agentId}/versions/${version.id}/status`;
  if (version.status === "DRAFT") return `/dashboard/agents/${agentId}/versions/new`;
  return `/dashboard/agents/${agentId}`;
}

export function DashboardList({ agents }: { agents: MyAgentsResponse[] }) {
  if (agents.length === 0) return <p className="text-muted py-8">You haven't submitted any agents yet.</p>;
  return (
    <div className="border-t border-line">
      {agents.map((agent) =>
        agent.versions.map((version) => (
          <Link
            key={version.id}
            href={linkFor(agent.agentId, version)}
            className="grid grid-cols-[1.6fr_1fr_auto] gap-4 items-center py-4 border-b border-line hover:bg-paper2"
          >
            <div>
              <div className="font-semibold">{agent.agentName}</div>
              <div className="text-sm text-ink2">v{version.versionNumber} · {agent.category}</div>
            </div>
            <StatusPill status={version.status} />
            <span className="text-sm font-medium">
              {version.status === "IN_REVIEW" ? "View status" : version.status === "DRAFT" ? "Continue" : "Manage"}
            </span>
          </Link>
        )),
      )}
    </div>
  );
}
