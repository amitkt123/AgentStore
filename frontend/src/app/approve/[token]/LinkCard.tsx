"use client";
import { useState, useTransition } from "react";
import styles from "@/features/gates/GateCard.module.css";
import { Button } from "@/ui/Button";
import { decideByLinkAction } from "./actions";
import { FAILURE_MESSAGE } from "./messages";
import type { ApprovalCard, ApprovalOutcome } from "@/lib/api/approvals";

export function LinkCard({ token, card }: { token: string; card: ApprovalCard }) {
  const [outcome, setOutcome] = useState<ApprovalOutcome | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const expired = new Date(card.expiresAt).getTime() <= Date.now();

  const decide = (d: "APPROVE" | "REJECT") =>
    start(async () => {
      setError(null);
      const res = await decideByLinkAction(token, d);
      if (res.ok) setOutcome(res.outcome);
      else setError(FAILURE_MESSAGE[res.reason]);
    });

  return (
    <section className={styles.card} aria-labelledby="link-summary">
      <p>{card.agentName} · run {card.runDisplayId}</p>
      <h2 id="link-summary">{card.summary}</h2>
      {outcome && <p role="status">{outcome.decision === "APPROVED" ? "You approved this." : "You rejected this."} You can close this page.</p>}
      {!outcome && expired && <p role="status">This request has expired.</p>}
      {!outcome && (
        <div className={styles.actions}>
          <Button variant="danger" disabled={expired} loading={pending} onClick={() => decide("REJECT")}>Reject</Button>
          <Button disabled={expired} loading={pending} onClick={() => decide("APPROVE")}>Approve</Button>
        </div>
      )}
      {error && <p role="alert" className={styles.err}>{error}</p>}
    </section>
  );
}
