"use client";
import { useState } from "react";
import styles from "./earnings.module.css";
import { WithdrawForm } from "./WithdrawForm";
import { accountLabel } from "./labels";
import type { PayoutsSummary } from "@/lib/api/types";

// Keeps the "Withdrawal requested." notice on screen even when the form goes away because the balance hit zero.
export function WithdrawSection({ payouts, blocked, owes }: { payouts: PayoutsSummary; blocked: string | null; owes: boolean }) {
  const [notice, setNotice] = useState<string | null>(null);
  return (
    <>
      {!owes && payouts.canWithdraw && payouts.account && (
        <WithdrawForm
          available={payouts.available}
          dailyLimitLeft={Math.max(0, payouts.dailyLimit - payouts.withdrawnToday)}
          minimum={payouts.minimum}
          accountLabel={accountLabel(payouts.account)}
          onDone={() => setNotice("Withdrawal requested.")}
        />
      )}
      {!owes && blocked && <p className={styles.blocked}>{blocked}</p>}
      {notice && <p role="status" className={styles.ok}>{notice}</p>}
    </>
  );
}
