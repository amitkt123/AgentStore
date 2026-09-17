import Link from "next/link";
import { StatusPill } from "@/components/StatusPill";
import { formatInr } from "@/lib/format";
import type { RunSummaryResponse } from "@/lib/types";

export function RunsList({ runs }: { runs: RunSummaryResponse[] }) {
  if (runs.length === 0) return <p className="text-muted py-8">No runs yet.</p>;
  return (
    <div className="border-t border-line">
      {runs.map((run) => (
        <Link key={run.id} href={`/runs/${run.id}`} className="grid grid-cols-[1fr_auto_auto] gap-4 items-center py-4 border-b border-line hover:bg-paper2">
          <span className="font-medium">{run.agentName}</span>
          <StatusPill status={run.status} />
          <span className="text-sm">{formatInr(run.totalCharged)}</span>
        </Link>
      ))}
    </div>
  );
}
