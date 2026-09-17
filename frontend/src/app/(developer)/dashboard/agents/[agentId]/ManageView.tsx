import Link from "next/link";
import { StatusPill } from "@/components/StatusPill";
import type { MyAgentsResponse } from "@/lib/types";

export function ManageView({ agent }: { agent: MyAgentsResponse }) {
  return (
    <div>
      <div className="flex justify-between items-center mb-6">
        <h1 className="font-serif text-2xl">{agent.agentName}</h1>
        <Link href={`/dashboard/agents/${agent.agentId}/versions/new`} className="bg-green text-white rounded-lg px-4 py-2 font-medium text-sm">
          Submit new version
        </Link>
      </div>
      <h2 className="font-semibold text-sm mb-3">Version history</h2>
      <div className="border-t border-line">
        {agent.versions.map((v) => (
          <div key={v.id} className="grid grid-cols-[100px_1fr_auto] gap-4 items-center py-3 border-b border-line">
            <span className="font-mono text-sm">v{v.versionNumber}</span>
            <span />
            <StatusPill status={v.status} />
          </div>
        ))}
      </div>
    </div>
  );
}
