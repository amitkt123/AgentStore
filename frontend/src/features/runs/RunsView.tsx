"use client";
import { useState } from "react";
import styles from "./runs.module.css";
import { RunsTable } from "./RunsTable";
import { RunTimeline } from "./RunTimeline";
import { useRunPolling } from "./useRunPolling";
import { EmptyState } from "@/ui/EmptyState";
import type { RunGroup, RunListResponse } from "@/lib/api/types";

const CHIPS: { key: RunGroup | "all"; label: string }[] = [
  { key: "all", label: "All" }, { key: "needs_you", label: "Needs you" }, { key: "running", label: "Running" },
  { key: "finished", label: "Finished" }, { key: "stopped", label: "Stopped" },
];

export function RunsView({ initial }: { initial: RunListResponse }) {
  const [group, setGroup] = useState<RunGroup | "all">("all");
  const url = `/api/runs${group === "all" ? "" : `?group=${group}`}`;
  const { data, error, forbidden } = useRunPolling<RunListResponse>(url, { intervalMs: 3000, initial });

  if (forbidden) {
    return <EmptyState title="You don't have access to runs" body="Runs are visible to the buyers who own the workspace. Ask a workspace admin if you need access." />;
  }
  const total = data.counts.needs_you + data.counts.running + data.counts.finished + data.counts.stopped;
  return (
    <div className={styles.wrap}>
      <div className={styles.chips} role="group" aria-label="Filter runs">
        {CHIPS.map((c) => (
          <button key={c.key} type="button" aria-pressed={group === c.key} className={styles.chip} onClick={() => setGroup(c.key)}>
            {c.label} {c.key === "all" ? total : data.counts[c.key]}
          </button>
        ))}
      </div>
      {error && <p role="status" className={styles.note}>{error}</p>}
      <RunTimeline items={data.items} nowMs={Date.now()} />
      <RunsTable rows={data.items} />
    </div>
  );
}
