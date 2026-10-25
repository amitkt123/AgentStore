"use client";
import { useState } from "react";
import styles from "./LiveRun.module.css";
import { useRunPolling } from "./useRunPolling";
import { isTerminal, statusPill, terminalText } from "./status";
import { stepNote, stepText } from "./stepText";
import { FlowCanvas } from "@/features/flow/FlowCanvas";
import { toBlockViews } from "@/features/flow/blocks";
import { layoutFlow, type GateOutcome } from "@/features/flow/layout";
import { stepsForNode } from "./stepFilter";
import { Glass } from "@/ui/Glass";
import { GateCard } from "@/features/gates/GateCard";
import { Pill } from "@/ui/Pill";
import { ErrorState } from "@/ui/ErrorState";
import { formatMoney, formatTime, formatWhen } from "@/lib/format";
import type { GateDetail, GateStatus, Manifest, RunSnapshot } from "@/lib/api/types";

/** The latest decided gate's outcome, else PENDING while one waits, else null. */
function outcomeOf(gates: GateDetail[]): GateOutcome {
  const decided = gates.filter((g) => g.status !== "PENDING" && g.decidedAt).sort((a, b) => (a.decidedAt! < b.decidedAt! ? 1 : -1));
  if (decided.length) return decided[0].status;
  const any = gates.find((g) => g.status !== "PENDING");
  if (any) return any.status;
  return gates.length ? "PENDING" : null;
}

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
  const views = toBlockViews(flow.blocks.map((b) => ({ id: b.block, state: b.state, metrics: b.metrics, flag: b.flag })), manifest);
  const terminal = isTerminal(detail.status);
  const graph = layoutFlow(views, { gateOutcome: outcomeOf(gates), reachedBlock: flow.reachedBlock, terminal });
  const selNode = [...graph.columns.flatMap((c) => c.nodes), ...graph.offPath].find((n) => n.id === sel);
  const steps = selNode ? stepsForNode(detail.steps, selNode) : detail.steps;
  const endReached = graph.ends.some((e) => e.reached);
  const cap = flow.spend.cap;

  return (
    <div className={styles.wrap}>
      <header className={styles.head}>
        <h1>Run <span className="mono">{detail.displayId}</span></h1>
        <Pill tone={pill.tone}>{pill.text}</Pill>
      </header>
      {error && <p role="status" className={styles.note}>{error}</p>}
      {why && terminal && !endReached && <p className={styles.why}>{why}</p>}
      {pending.map((g) => <GateCard key={g.gateId} runRef={runRef} gate={g} canDecide={canDecide} viewerUserId={viewerUserId} />)}
      <Glass as="section" aria-label="Flow" className={styles.canvas}>
        <FlowCanvas graph={graph} live={!terminal} selectedId={sel} onSelect={(id) => setSel((cur) => (cur === id ? undefined : id))} endText={why} />
      </Glass>
      {gates.filter((g) => g.status !== "PENDING").map((g) => (
        <details key={g.gateId} className={styles.decided} open={!isTerminal(detail.status)}>
          <summary>{`${GATE_OUTCOME[g.status]} · ${g.summary}`}</summary>
          <GateCard runRef={runRef} gate={g} canDecide={canDecide} viewerUserId={viewerUserId} />
        </details>
      ))}
      <div className={styles.cols}>
        <section aria-labelledby="tl">
          <h2 id="tl">Timeline</h2>
          <p className={styles.sub}>Recorded by Karya as it happens, not reported by the agent.</p>
          {selNode && <button type="button" className={styles.all} onClick={() => setSel(undefined)}>Show all steps</button>}
          {selNode && steps.length === 0 && <p className={styles.sub}>No steps for this part yet.</p>}
          <ol className={styles.steps}>
            {steps.map((s, i) => (
              <li key={i}>
                <span className="mono">{formatWhen(s.createdAt)}</span>
                <span>{stepText(s)}{stepNote(s) && <span className={styles.stepNote} title={stepNote(s, Infinity) ?? undefined}>{stepNote(s)}</span>}</span>
              </li>
            ))}
          </ol>
        </section>
        <Glass as="aside" aria-label="Run facts" className={styles.facts}>
          <dl>
            <div>
              <dt>Spend</dt><dd>{formatMoney(flow.spend.charged)} of {formatMoney(cap)}</dd>
              <div role="meter" aria-label="Spend" aria-valuemin={0} aria-valuemax={cap} aria-valuenow={flow.spend.charged} className={styles.meter}>
                <i style={{ width: `${cap > 0 ? Math.min(100, (flow.spend.charged / cap) * 100) : 0}%` }} />
              </div>
            </div>
            <div><dt>Blocked attempts</dt><dd>{flow.blockedAttempts}</dd></div>
            {flow.deadlineAt && !isTerminal(detail.status) && <div><dt>Sandbox closes</dt><dd className="mono">{formatTime(flow.deadlineAt)}</dd></div>}
          </dl>
        </Glass>
      </div>
    </div>
  );
}
