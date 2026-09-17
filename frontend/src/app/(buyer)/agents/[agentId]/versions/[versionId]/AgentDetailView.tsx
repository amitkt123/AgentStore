import Link from "next/link";
import { PermissionStatementDoc } from "@/components/PermissionStatementDoc";
import type { AgentVersionDetailResponse } from "@/lib/types";

export function AgentDetailView({ version }: { version: AgentVersionDetailResponse }) {
  return (
    <div className="grid grid-cols-[1fr_380px] gap-8 items-start">
      <div>
        <h1 className="font-serif text-2xl mb-2">{version.agentName}</h1>
        <p className="text-ink2 mb-6">Version {version.versionNumber}</p>
      </div>
      <div>
        <PermissionStatementDoc manifest={version.manifest} mode="listing" />
        <Link
          href={`/connect/${version.agentId}/${version.id}`}
          className="block mt-4 text-center bg-green text-white rounded-lg py-2 font-medium"
        >
          Connect this agent
        </Link>
      </div>
    </div>
  );
}
