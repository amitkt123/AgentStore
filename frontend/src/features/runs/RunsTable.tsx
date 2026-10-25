import Link from "next/link";
import { Pill } from "@/ui/Pill";
import { ResponsiveTable } from "@/ui/ResponsiveTable";
import { EmptyState } from "@/ui/EmptyState";
import { statusPill } from "./status";
import { reachedText } from "@/features/flow/blocks";
import { formatMoney, formatWhen } from "@/lib/format";
import type { RunListItem } from "@/lib/api/types";


export function RunsTable({ rows }: { rows: RunListItem[] }) {
  return (
    <ResponsiveTable
      rows={rows}
      rowKey={(r) => r.id}
      empty={<EmptyState title="No runs match this filter." body="Try another filter, or start a run from the agent store." />}
      columns={[
        { key: "id", header: "Run", render: (r) => <Link href={`/runs/${r.displayId}`}>{r.displayId}</Link> },
        { key: "agent", header: "Agent", render: (r) => `${r.agent} ${r.version}` },
        { key: "reached", header: "Reached", render: (r) => reachedText(r.reachedBlock) },
        { key: "status", header: "Status", render: (r) => { const s = statusPill(r.status); return <Pill tone={s.tone}>{s.text}</Pill>; } },
        { key: "started", header: "Started", render: (r) => <span className="mono">{formatWhen(r.startedAt)}</span> },
        { key: "cost", header: "Cost", render: (r) => <span className="mono">{formatMoney(r.charged)}</span> },
      ]}
    />
  );
}
