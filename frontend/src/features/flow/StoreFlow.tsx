"use client";
import Link from "next/link";
import { useState } from "react";
import styles from "./StoreFlow.module.css";
import { FlowBlocks } from "./FlowBlocks";
import { blocksFromManifest, toBlockViews } from "./blocks";
import { formatMoney, humanize } from "@/lib/format";
import type { VersionDetail } from "@/lib/api/types";

export function StoreFlow({ agentId, versionId, detail, canConnect }: { agentId: string; versionId: string; detail: VersionDetail; canConnect: boolean }) {
  const m = detail.manifest;
  const views = toBlockViews(blocksFromManifest(m), m);
  const [sel, setSel] = useState<string | undefined>(undefined);
  const cur = views.find((v) => v.id === sel);
  const c = m.costEnvelope;
  return (
    <div className={styles.wrap}>
      <header>
        <h1>{detail.agentName}</h1>
        <p className={styles.sub}>Version {detail.versionNumber}</p>
      </header>
      <FlowBlocks blocks={views} selectedId={sel} onSelect={setSel} />
      <div className={styles.cols}>
        <section aria-label="Selected block" className={styles.panel}>
          {cur ? (<><span className={styles.kind}>{cur.kind}</span><h2>{cur.title}</h2><p>{cur.body}</p></>) : <p>Pick a block to see what it does.</p>}
        </section>
        <section aria-label="What you agree to" className={styles.panel}>
          <h2>What you agree to</h2>
          <dl>
            <div><dt>Price</dt><dd>{`${formatMoney(c.unitPrice, c.currency)} per ${c.unitLabel.replace(/_/g, " ")}`}</dd></div>
            <div><dt>Most a run can cost</dt><dd>{formatMoney(c.maxPerRun, c.currency)}</dd></div>
            <div><dt>Outside websites</dt><dd>{m.networkAllowlist.length ? m.networkAllowlist.join(", ") : "None"}</dd></div>
            <div><dt>Data it handles</dt><dd>{m.dataClasses.length ? m.dataClasses.map((d) => humanize(d)).join(", ") : "None"}</dd></div>
          </dl>
          {canConnect
            ? <Link href={`/connect/${agentId}/${versionId}`} className={styles.cta}>Add to workspace</Link>
            : <p className={styles.sub}>Ask a workspace admin to add this agent.</p>}
        </section>
      </div>
    </div>
  );
}
