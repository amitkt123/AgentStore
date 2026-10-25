"use client";
import { useState } from "react";
import styles from "./LiveRun.module.css";
import { useRunPolling } from "./useRunPolling";
import { isTerminal, statusPill, terminalText } from "./status";
import { Glass } from "@/ui/Glass";
import { GateCard } from "@/features/gates/GateCard";
import { Pill } from "@/ui/Pill";
import { ErrorState } from "@/ui/ErrorState";
import { ReplayCanvas } from "@/features/replay/ReplayCanvas";
import { buildReplay } from "@/features/replay/graph";
import { formatMoney, formatTime } from "@/lib/format";
import type { GateStatus, Manifest, RunSnapshot } from "@/lib/api/types";

const GATE_OUTCOME: Record<GateStatus, string> = { PENDING: "Waiting", APPROVED: "Approved", REJECTED: "Rejected", EXPIRED: "Expired" };

export function LiveRun({ runRef, initial, manifest, canDecide, viewerUserId }: { runRef: string; initial: RunSnapshot; manifest: Manifest | null; canDecide: boolean; viewerUserId?: string }) {
  const { data, error, forbidden } = useRunPolling<RunSnapshot>(`/api/runs/${encodeURIComponent(runRef)}`, {
    intervalMs: 3000,
    initial,
    isDone: (s) => isTerminal(s.detail.status),
  });
  const [sel, setSel] = useState<string | undefined>(undefined);
  if (forbidden) return <ErrorState message="You don't have access to this run." />;

  const { detail, flow, gates } = data;
  const pill = statusPill(detail.status);
  const why = terminalText(detail.terminalReason);
  const pending = gates.filter((g) => g.status === "PENDING");
  const terminal = isTerminal(detail.status);
  const replay = buildReplay(detail.steps, gates);
  const block = replay.blocks.find((b) => b.id === sel);
  const toggle = (id: string) => { if (!id.startsWith("ghost:")) setSel((cur) => (cur === id ? undefined : id)); };
  const cap = flow.spend.cap;

  return (
    <div className={styles.wrap}>
      <header className={styles.head}>
        <h1>Run <span className="mono">{detail.displayId}</span></h1>
        <Pill tone={pill.tone}>{pill.text}</Pill>
      </header>
      {error && <p role="status" className={styles.note}>{error}</p>}
      {why && terminal && <p className={styles.why}>{why}</p>}
      {pending.map((g) => <GateCard key={g.gateId} runRef={runRef} gate={g} canDecide={canDecide} viewerUserId={viewerUserId} />)}
      <Glass as="section" className={styles.canvas}>
        <ReplayCanvas graph={replay} live={!terminal} selectedId={sel} onSelect={toggle} />
      </Glass>
      {gates.filter((g) => g.status !== "PENDING").map((g) => (
        <details key={g.gateId} className={styles.decided} open={!isTerminal(detail.status)}>
          <summary>{`${GATE_OUTCOME[g.status]} · ${g.summary}`}</summary>
          <GateCard runRef={runRef} gate={g} canDecide={canDecide} viewerUserId={viewerUserId} />
        </details>
      ))}
      <div className={styles.cols}>
        <Glass as="aside" aria-label="Run facts" className={styles.facts}>
          {block ? (
            <>
              <h2 className={styles.factsTitle}>{block.title}</h2>
              <dl>{block.facts.map(([k, v], i) => <div key={i}><dt>{k}</dt><dd className={styles.fact}>{v}</dd></div>)}</dl>
              <button type="button" className={styles.all} onClick={() => setSel(undefined)}>Close</button>
            </>
          ) : (
            <dl>
              <div>
                <dt>Spend</dt><dd>{formatMoney(flow.spend.charged)} of {formatMoney(cap)}</dd>
                <div role="meter" aria-label="Spend" aria-valuemin={0} aria-valuemax={cap} aria-valuenow={flow.spend.charged} className={styles.meter}>
                  <i style={{ width: `${cap > 0 ? Math.min(100, (flow.spend.charged / cap) * 100) : 0}%` }} />
                </div>
              </div>
              <div><dt>Blocked attempts</dt><dd>{flow.blockedAttempts}</dd></div>
              {flow.deadlineAt && !terminal && <div><dt>Sandbox closes</dt><dd className="mono">{formatTime(flow.deadlineAt)}</dd></div>}
            </dl>
          )}
        </Glass>
      </div>
    </div>
  );
}
