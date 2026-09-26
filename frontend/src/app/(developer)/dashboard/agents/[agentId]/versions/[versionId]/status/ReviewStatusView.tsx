import { BuildPanel } from "@/components/BuildPanel";
import { PushInstructions } from "@/components/PushInstructions";
import { StatusPill } from "@/components/StatusPill";
import { SubmitVersionButton } from "@/components/SubmitVersionButton";
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
      {version.status === "DRAFT" && !version.build ? (
        <PushInstructions agentId={version.agentId} versionNumber={version.versionNumber} />
      ) : null}
      {version.status === "DRAFT" && version.build?.status === "SUCCEEDED" ? (
        <SubmitVersionButton agentId={version.agentId} versionId={version.id} />
      ) : null}
    </div>
  );
}
