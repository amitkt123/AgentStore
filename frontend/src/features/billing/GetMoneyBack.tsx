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

// One id per intended refund: a retry of the same amount reuses it, so the server returns the first refund
// instead of making a second one. crypto.randomUUID needs a secure context, hence the fallback.
function newRequestId(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

export function GetMoneyBack({ refundable, refunds }: { refundable: Refundable; refunds: Refund[] }) {
  const router = useRouter();
  const [amount, setAmount] = useState(String(refundable.refundable));
  const [parts, setParts] = useState(refundable.parts);
  const [error, setError] = useState<string | null>(null);            // preview: blocks confirm
  const [submitError, setSubmitError] = useState<string | null>(null); // request: shown, retry allowed
  const [previewing, setPreviewing] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const requestId = useRef<string | null>(null);
  if (requestId.current === null) requestId.current = newRequestId();

  // The server splits the amount across payments; ask again (debounced) when the amount differs from the full refundable one.
  useEffect(() => {
    const value = Number(amount);
    setError(null);
    if (amount === String(refundable.refundable)) { setParts(refundable.parts); setPreviewing(false); return; }
    if (!amount || !Number.isFinite(value) || value <= 0) { setParts([]); setPreviewing(false); return; }
    let live = true;
    setPreviewing(true);
    const id = setTimeout(() => {
      void previewRefund(value).then((res) => {
        if (!live) return;
        if ("error" in res) { setError(res.error); setParts([]); } else setParts(res.refundable.parts);
        setPreviewing(false);
      });
    }, 300);
    return () => { live = false; clearTimeout(id); };
  }, [amount, refundable]);

  const confirm = async () => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setNotice(null);
    setSubmitError(null);
    try {
      const res = await requestRefund(Number(amount), requestId.current!);
      if ("error" in res) { setSubmitError(res.error); return; }
      requestId.current = newRequestId();
      setAmount("");
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
            error={error ?? submitError ?? undefined}
            onChange={(e) => {
              setAmount(e.target.value);
              setNotice(null);
              setSubmitError(null);
              requestId.current = newRequestId();
            }}
          />
          <ul className={styles.parts}>
            {parts.map((p) => (
              <li key={p.paymentOrderId}>
                {formatMoney(p.amount)} back to your {METHOD_LABEL[p.method]} payment of {formatDate(p.paidAt)}
              </li>
            ))}
          </ul>
          <p className={styles.note}>Refunds reach the original payment in 5–7 working days.</p>
          <Button type="button" loading={busy} disabled={!!error || previewing || !Number.isFinite(value) || value <= 0} onClick={confirm}>
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
