"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./earnings.module.css";
import { Button } from "@/ui/Button";
import { Pill, type Tone } from "@/ui/Pill";
import { GENERIC_ERROR } from "./labels";
import { simulatePayoutAction } from "@/app/(app)/developer/earnings/actions";
import { formatDate, formatInr } from "@/lib/format";
import type { PayoutSimulateOutcome, Withdrawal } from "@/lib/api/types";

const STATUS: Record<Withdrawal["status"], [string, Tone]> = {
  REQUESTED: ["Sending", "info"], PROCESSING: ["Sending", "info"], PROCESSED: ["Sent", "ok"],
  REVERSED: ["Returned", "warn"], FAILED: ["Failed", "bad"],
};
const OUTCOMES: [PayoutSimulateOutcome, string][] = [["PROCESSED", "Processed"], ["REVERSED", "Reversed"], ["FAILED", "Failed"]];

// Dev-only menu, shown when the backend runs the fake payout provider.
function SimulateMenu({ id }: { id: string }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = async (outcome: PayoutSimulateOutcome) => {
    setBusy(true);
    setError(null);
    try {
      const res = await simulatePayoutAction(id, outcome);
      if ("error" in res) { setError(res.error); return; }
      setOpen(false);
      router.refresh();
    } catch {
      setError(GENERIC_ERROR);
    } finally {
      setBusy(false);
    }
  };
  return (
    <span className={styles.simulate}>
      <Button type="button" variant="ghost" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(!open)}>Simulate</Button>
      {open && (
        <span role="menu" className={styles.menu}>
          {OUTCOMES.map(([outcome, label]) => (
            <button key={outcome} type="button" role="menuitem" disabled={busy} onClick={() => run(outcome)}>{label}</button>
          ))}
        </span>
      )}
      {error && <span role="alert" className={styles.err}>{error}</span>}
    </span>
  );
}

export function WithdrawalHistory({ withdrawals, simulate }: { withdrawals: Withdrawal[]; simulate: boolean }) {
  if (withdrawals.length === 0) return null;
  return (
    <section className={styles.section}>
      <h2>Withdrawals</h2>
      <ul className={styles.history}>
        {withdrawals.map((w) => (
          <li key={w.id}>
            <span>{formatDate(w.createdAt)}</span>
            <span className="mono">{formatInr(w.amount)}</span>
            <span>{w.accountLabel}</span>
            <Pill tone={STATUS[w.status][1]}>{STATUS[w.status][0]}</Pill>
            {w.status === "PROCESSED" && w.utr && <span className={`${styles.note} mono`}>{`UTR ${w.utr}`}</span>}
            {simulate && w.status === "PROCESSING" && <SimulateMenu id={w.id} />}
          </li>
        ))}
      </ul>
    </section>
  );
}
