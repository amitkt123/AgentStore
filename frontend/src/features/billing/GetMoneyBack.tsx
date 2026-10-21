"use client";
import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./billing.module.css";
import { Button } from "@/ui/Button";
import { Field } from "@/ui/Field";
import { Pill, type Tone } from "@/ui/Pill";
import { previewRefund, requestRefund } from "@/app/(app)/billing/actions";
import { METHOD_LABEL } from "./labels";
import { formatDate, formatInr, formatMoney } from "@/lib/format";
import type { Refund, Refundable } from "@/lib/api/types";

const STATUS: Record<Refund["status"], [string, Tone]> = {
  REQUESTED: ["Requested", "info"], PROCESSING: ["Processing", "info"], DONE: ["Done", "ok"],
  PARTLY_FAILED: ["Partly failed", "warn"], FAILED: ["Failed", "bad"],
};

export function GetMoneyBack({ refundable, refunds }: { refundable: Refundable; refunds: Refund[] }) {
  const router = useRouter();
  const [amount, setAmount] = useState(String(refundable.refundable));
  const [parts, setParts] = useState(refundable.parts);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);

  // The server splits the amount across payments; ask again (debounced) when the amount differs from the full refundable one.
  useEffect(() => {
    const value = Number(amount);
    setError(null);
    if (amount === String(refundable.refundable)) { setParts(refundable.parts); return; }
    if (!amount || !Number.isFinite(value) || value <= 0) { setParts([]); return; }
    let live = true;
    const id = setTimeout(() => {
      void previewRefund(value).then((res) => {
        if (!live) return;
        if ("error" in res) { setError(res.error); setParts([]); } else setParts(res.refundable.parts);
      });
    }, 300);
    return () => { live = false; clearTimeout(id); };
  }, [amount, refundable]);

  const confirm = async () => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setNotice(null);
    try {
      const res = await requestRefund(Number(amount));
      if ("error" in res) { setError(res.error); return; }
      setNotice("Refund requested.");
      router.refresh();
    } finally { busyRef.current = false; setBusy(false); }
  };

  const none = refundable.refundable <= 0;
  const value = Number(amount);
  return (
    <section className={styles.section}>
      <h2>Get money back</h2>
      {none ? (
        <p className={styles.note}>Money added by bank adjustment or still held by running runs can&apos;t be returned here.</p>
      ) : (
        <>
          <p>You can get back {formatMoney(refundable.refundable)}</p>
          <Field
            label="Amount (₹)" type="number" inputMode="decimal" min={0.01} max={refundable.refundable} value={amount}
            error={error ?? undefined} onChange={(e) => { setAmount(e.target.value); setNotice(null); }}
          />
          <ul className={styles.parts}>
            {parts.map((p) => (
              <li key={p.paymentOrderId}>
                {formatMoney(p.amount)} back to your {METHOD_LABEL[p.method]} payment of {formatDate(p.paidAt)}
              </li>
            ))}
          </ul>
          <p className={styles.note}>Refunds reach the original payment in 5–7 working days.</p>
          <Button type="button" loading={busy} disabled={!!error || !Number.isFinite(value) || value <= 0} onClick={confirm}>
            Get money back
          </Button>
          {notice && <p role="status" className={styles.ok}>{notice}</p>}
        </>
      )}
      {refunds.length > 0 && (
        <ul className={styles.recent} aria-label="Recent refunds">
          {refunds.map((r) => (
            <li key={r.id}>
              <span>{formatDate(r.createdAt)}</span>
              <span className="mono">{formatInr(r.amount)}</span>
              <Pill tone={STATUS[r.status][1]}>{STATUS[r.status][0]}</Pill>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
