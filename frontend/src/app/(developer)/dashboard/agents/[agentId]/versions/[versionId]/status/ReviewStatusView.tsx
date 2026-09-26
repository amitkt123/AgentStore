import { BuildPanel } from "@/components/BuildPanel";
import { StatusPill } from "@/components/StatusPill";
import type { AgentVersionDetailResponse, VersionStatus } from "@/lib/types";

const STATUS_EXPLANATION: Record<VersionStatus, string> = {
  DRAFT: "Not yet submitted for review.",
  IN_REVIEW: "Passed the automated manifest check. Waiting on a human reviewer.",
  LIVE: "Passed every check and is now visible to buyers in the catalog.",
  REJECTED: "A reviewer rejected this version. Submit a new version to try again.",
};

export function ReviewStatusView({ version }: { version: AgentVersionDetailResponse }) {
  return (
    <div>
      <h1 className="font-serif text-2xl mb-1">
        {version.agentName} — v{version.versionNumber}
      </h1>
      <div className="mb-4">
        <StatusPill status={version.status} />
      </div>
      <p className="text-ink2">{STATUS_EXPLANATION[version.status]}</p>
      {version.build ? <BuildPanel build={version.build} /> : null}
    </div>
  );
}
