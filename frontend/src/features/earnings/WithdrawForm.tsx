"use client";
import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./earnings.module.css";
import { Button } from "@/ui/Button";
import { Field } from "@/ui/Field";
import { withdraw } from "@/app/(app)/developer/earnings/actions";
import { newRequestId } from "./newRequestId";
import { GENERIC_ERROR } from "./labels";
import { formatInr } from "@/lib/format";

export function WithdrawForm({ available, dailyLimitLeft, accountLabel, onDone }: { available: number; dailyLimitLeft: number; accountLabel: string; onDone?: () => void }) {
  const router = useRouter();
  const max = Math.max(0, Math.floor(Math.min(available, dailyLimitLeft) * 100) / 100);
  const [amount, setAmount] = useState(String(max));
  const [step, setStep] = useState<"edit" | "confirm">("edit");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const requestId = useRef<string | null>(null);
  if (requestId.current === null) requestId.current = newRequestId();

  // The balance changes after a withdrawal; start again from the new most that can be withdrawn.
  useEffect(() => { setAmount(String(max)); }, [max]);

  const value = Number(amount);
  const valid = amount !== "" && Number.isFinite(value) && value > 0 && value <= max;

  const confirm = async () => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      const res = await withdraw(value, requestId.current!);
      if ("error" in res) { setError(res.error); return; }
      requestId.current = newRequestId();
      setStep("edit");
      setAmount(String(max));
      onDone?.();
      router.refresh();
    } catch {
      setError(GENERIC_ERROR);
    } finally { busyRef.current = false; setBusy(false); }
  };

  if (step === "confirm") {
    return (
      <div className={styles.form}>
        <p>{`Send ${formatInr(value)} to ${accountLabel}?`}</p>
        {error && <p role="alert" className={styles.err}>{error}</p>}
        <div className={styles.actions}>
          <Button type="button" loading={busy} onClick={confirm}>Confirm</Button>
          <Button type="button" variant="ghost" disabled={busy} onClick={() => setStep("edit")}>Back</Button>
        </div>
      </div>
    );
  }
  return (
    <div className={styles.form}>
      <Field
        label="Amount (₹)" type="number" inputMode="decimal" min={0.01} max={max} value={amount}
        error={amount !== "" && value > max ? `You can withdraw up to ${formatInr(max)} now` : undefined}
        onChange={(e) => {
          setAmount(e.target.value);
          setError(null);
          requestId.current = newRequestId();
        }}
      />
      <div className={styles.actions}>
        <Button type="button" disabled={!valid} onClick={() => setStep("confirm")}>Withdraw</Button>
      </div>
    </div>
  );
}
