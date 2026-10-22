"use client";
import styles from "./LiveRun.module.css";
import { useRunPolling } from "./useRunPolling";
import { isTerminal, statusPill, terminalText } from "./status";
import { stepText } from "./stepText";
import { FlowBlocks } from "@/features/flow/FlowBlocks";
import { toBlockViews } from "@/features/flow/blocks";
import { GateCard } from "@/features/gates/GateCard";
import { Pill } from "@/ui/Pill";
import { ErrorState } from "@/ui/ErrorState";
import { formatMoney, formatTime, formatWhen } from "@/lib/format";
import type { Manifest, RunSnapshot } from "@/lib/api/types";

export function LiveRun({ runRef, initial, manifest, canDecide }: { runRef: string; initial: RunSnapshot; manifest: Manifest | null; canDecide: boolean }) {
  const { data, error, forbidden } = useRunPolling<RunSnapshot>(`/api/runs/${encodeURIComponent(runRef)}`, {
    intervalMs: 3000,
    initial,
    isDone: (s) => isTerminal(s.detail.status),
  });
  if (forbidden) return <ErrorState message="You don't have access to this run." />;

  const { detail, flow, gates } = data;
  const pill = statusPill(detail.status);
  const why = terminalText(detail.terminalReason);
  const pending = gates.filter((g) => g.status === "PENDING");
  const views = toBlockViews(flow.blocks.map((b) => ({ id: b.block, state: b.state, metrics: b.metrics, flag: b.flag })), manifest);

  return (
    <div className={styles.wrap}>
      <header className={styles.head}>
        <h1>Run <span className="mono">{detail.displayId}</span></h1>
        <Pill tone={pill.tone}>{pill.text}</Pill>
      </header>
      {error && <p role="status" className={styles.note}>{error}</p>}
      {why && isTerminal(detail.status) && <p className={styles.why}>{why}</p>}
      <FlowBlocks blocks={views} />
      {pending.map((g) => <GateCard key={g.gateId} runRef={runRef} gate={g} canDecide={canDecide} />)}
      {gates.filter((g) => g.status !== "PENDING").map((g) => <GateCard key={g.gateId} runRef={runRef} gate={g} canDecide={canDecide} />)}
      <div className={styles.cols}>
        <section aria-labelledby="tl">
          <h2 id="tl">Timeline</h2>
          <p className={styles.sub}>Recorded by Karya as it happens, not reported by the agent.</p>
          <ol className={styles.steps}>
            {detail.steps.map((s, i) => (
              <li key={i}><span className="mono">{formatWhen(s.createdAt)}</span> {stepText(s)}</li>
            ))}
          </ol>
        </section>
        <aside aria-label="Run facts" className={styles.facts}>
          <dl>
            <div><dt>Spend</dt><dd>{formatMoney(flow.spend.charged)} of {formatMoney(flow.spend.cap)}</dd></div>
            <div><dt>Blocked attempts</dt><dd>{flow.blockedAttempts}</dd></div>
            {flow.deadlineAt && !isTerminal(detail.status) && <div><dt>Sandbox closes</dt><dd className="mono">{formatTime(flow.deadlineAt)}</dd></div>}
          </dl>
        </aside>
      </div>
    </div>
  );
}
