"use client";
import Link from "next/link";
import { useState } from "react";
import styles from "./StoreFlow.module.css";
import { FlowCanvas } from "./FlowCanvas";
import { blocksFromManifest, toBlockViews } from "./blocks";
import { layoutFlow, type FlowNode } from "./layout";
import { Glass } from "@/ui/Glass";
import { RunNow } from "@/features/runs/RunNow";
import { formatMoney, humanize } from "@/lib/format";
import type { Manifest, VersionDetail } from "@/lib/api/types";

function detailLines(n: FlowNode, m: Manifest): string[] {
  if (n.stage === "Asks you") {
    const g = m.approvalGates.find((x) => `gate:${x.trigger}` === n.id);
    if (!g) return [];
    const cols = g.preview?.columns;
    return [`Waits up to ${g.timeoutMinutes} minutes for an answer`, ...(cols?.length ? [`You will see: ${cols.map(humanize).join(", ")}`] : [])];
  }
  if (n.stage === "Drafts" || n.derived) return ["You see every change before it is posted"];
  return [];
}

export function StoreFlow({ agentId, versionId, detail, canConnect, connectionId = null }: { agentId: string; versionId: string; detail: VersionDetail; canConnect: boolean; connectionId?: string | null }) {
  const m = detail.manifest;
  const graph = layoutFlow(toBlockViews(blocksFromManifest(m), m));
  const nodes = graph.columns.flatMap((col) => col.nodes);
  const [sel, setSel] = useState<string | undefined>(undefined);
  const cur = nodes.find((v) => v.id === sel) ?? nodes[0];
  const c = m.costEnvelope;
  return (
    <div className={styles.wrap}>
      <header>
        <h1>{detail.agentName}</h1>
        <p className={styles.sub}>Version {detail.versionNumber}</p>
      </header>
      <Glass as="section" aria-label="Flow" className={styles.canvas}>
        <FlowCanvas graph={graph} selectedId={cur?.id} onSelect={setSel} showState={false} caption />
      </Glass>
      <div className={styles.cols}>
        <section aria-label="Selected block" className={styles.panel}>
          {cur ? (<><span className={styles.kind}>{cur.kind}</span><h2>{cur.title}</h2><p>{cur.body}</p>{detailLines(cur, m).map((t) => <p key={t}>{t}</p>)}</>) : null}
        </section>
        <section aria-label="What you agree to" className={styles.panel}>
          <h2>What you agree to</h2>
          <dl>
            <div><dt>Price</dt><dd>{`${formatMoney(c.unitPrice, c.currency)} per ${c.unitLabel.replace(/_/g, " ")}`}</dd></div>
            <div><dt>Most a run can cost</dt><dd>{formatMoney(c.maxPerRun, c.currency)}</dd></div>
            <div><dt>Outside websites</dt><dd>{m.networkAllowlist.length ? m.networkAllowlist.join(", ") : "None"}</dd><span className={styles.note}>Declared by the developer</span></div>
            <div><dt>Data it handles</dt><dd>{m.dataClasses.length ? m.dataClasses.map((d) => humanize(d)).join(", ") : "None"}</dd><span className={styles.note}>Declared by the developer</span></div>
          </dl>
          {connectionId ? (
            <>
              <p className={styles.sub}>This agent is already in your workspace. Runs use the version you added, which may be older than this page.</p>
              {canConnect && <RunNow connectionId={connectionId} />}
              {canConnect && <Link href={`/connect/${agentId}/${versionId}`} className={styles.again}>Set it up again</Link>}
            </>
          ) : canConnect
            ? <Link href={`/connect/${agentId}/${versionId}`} className={styles.cta}>Add to workspace</Link>
            : <p className={styles.sub}>Ask a workspace admin to add this agent.</p>}
        </section>
      </div>
    </div>
  );
}
