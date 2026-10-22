"use client";
import { useState, useTransition } from "react";
import styles from "./GateCard.module.css";
import { Button } from "@/ui/Button";
import { decideGateAction } from "@/app/(app)/runs/actions";
import { formatMoney, humanize } from "@/lib/format";
import { cell, decisionText } from "./previewCells";
import type { GateDetail } from "@/lib/api/types";

export function gateIsExpired(g: GateDetail, nowMs: number): boolean {
  return g.status === "EXPIRED" || (g.status === "PENDING" && new Date(g.expiresAt).getTime() <= nowMs);
}

export function GateCard({ runRef, gate, canDecide, viewerUserId }: { runRef: string; gate: GateDetail; canDecide: boolean; viewerUserId?: string }) {
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const expired = gateIsExpired(gate, Date.now());
  const decided = gate.status === "APPROVED" || gate.status === "REJECTED";

  const decide = (d: "APPROVE" | "REJECT") =>
    start(async () => {
      setError(null);
      const res = await decideGateAction(runRef, gate.gateId, d, undefined);
      if (res.error) setError(res.error);
    });

  const cols = gate.preview?.columns ?? (gate.preview?.rows[0] ? Object.keys(gate.preview.rows[0]) : []);
  const numericCols = new Set(cols.filter((c) => gate.preview?.rows.some((r) => cell(r[c]).numeric)));

  return (
    <section className={styles.card} aria-labelledby={`g-${gate.gateId}`}>
      <h2 id={`g-${gate.gateId}`}>{gate.summary}</h2>
      {gate.costIfApproved != null && <p>Approving costs {formatMoney(gate.costIfApproved)}.</p>}
      {gate.preview && gate.preview.rows.length > 0 && (
        <div className={styles.scroll}>
          <table>
            <thead><tr>{cols.map((c) => <th key={c} className={numericCols.has(c) ? styles.num : undefined}>{humanize(c)}</th>)}</tr></thead>
            <tbody>
              {gate.preview.rows.map((r, i) => (
                <tr key={i}>{cols.map((c) => { const v = cell(r[c]); return <td key={c} className={`mono ${v.numeric ? styles.num : ""}`}>{v.text}</td>; })}</tr>
              ))}
            </tbody>
          </table>
          <p className={styles.more}>
            Showing {gate.preview.rows.length} of {gate.preview.totalRows} rows
            {gate.preview.total != null ? `, total ${formatMoney(gate.preview.total)}` : ""}.
          </p>
        </div>
      )}
      {decided && <p role="status">{decisionText(gate, viewerUserId)}</p>}
      {!decided && expired && <p role="status">This request has expired.</p>}
      {!decided && !canDecide && <p role="status">An approver has to decide this. You can see what it will do but not answer it.</p>}
      {!decided && canDecide && (
        <div className={styles.actions}>
          <Button variant="danger" disabled={expired} loading={pending} onClick={() => decide("REJECT")}>Reject</Button>
          <Button disabled={expired} loading={pending} onClick={() => decide("APPROVE")}>Approve</Button>
        </div>
      )}
      {error && <p role="alert" className={styles.err}>{error}</p>}
    </section>
  );
}
