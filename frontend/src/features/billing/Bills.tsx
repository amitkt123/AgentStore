"use client";
import { useRef, useState } from "react";
import styles from "./billing.module.css";
import { Button } from "@/ui/Button";
import { EmptyState } from "@/ui/EmptyState";
import { Pill, type Tone } from "@/ui/Pill";
import { ResponsiveTable } from "@/ui/ResponsiveTable";
import { startBillPayment } from "@/app/(app)/billing/actions";
import { CheckoutFlow } from "./CheckoutFlow";
import { METHOD_LABEL } from "./labels";
import { formatDate, formatInr } from "@/lib/format";
import type { Bill, Checkout, PaymentMethod } from "@/lib/api/types";

const STATUS: Record<Bill["status"], [string, Tone]> = { OPEN: ["Open", "info"], PAID: ["Paid", "ok"], OVERDUE: ["Overdue", "bad"] };
const BILL_METHODS: PaymentMethod[] = ["UPI", "CARD", "NETBANKING"];

export function Bills({ bills, owed, methods }: { bills: Bill[]; owed: number; methods?: PaymentMethod[] }) {
  const choices = methods ? BILL_METHODS.filter((m) => methods.includes(m)) : BILL_METHODS;
  const [paying, setPaying] = useState<Bill | null>(null);
  const [method, setMethod] = useState<PaymentMethod | null>(choices[0] ?? null);
  const [checkout, setCheckout] = useState<Checkout | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);

  const pay = async () => {
    if (busyRef.current || !paying || !method) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      const res = await startBillPayment(paying.id, method);
      if ("error" in res) setError(res.error); else setCheckout(res.checkout);
    } finally { busyRef.current = false; setBusy(false); }
  };

  return (
    <section className={styles.section}>
      <h2>Bills</h2>
      {bills.some((b) => b.status === "OVERDUE") && (
        <p role="alert" className={styles.banner}>A bill is overdue. New runs are paused until it&apos;s paid.</p>
      )}
      <ResponsiveTable<Bill>
        rows={bills}
        rowKey={(b) => b.id}
        empty={<EmptyState title="No bills yet." />}
        columns={[
          { key: "period", header: "Period", render: (b) => b.period },
          { key: "amount", header: "Amount", render: (b) => <span className="mono">{formatInr(b.amount)}</span> },
          { key: "due", header: "Due", render: (b) => formatDate(b.dueAt) },
          { key: "status", header: "Status", render: (b) => <Pill tone={STATUS[b.status][1]}>{STATUS[b.status][0]}</Pill> },
          {
            key: "pay", header: "",
            render: (b) => b.status === "PAID" ? null : (
              <Button type="button" variant="ghost" onClick={() => { setPaying(b); setCheckout(null); setError(null); }}>Pay</Button>
            ),
          },
        ]}
      />
      {paying && (
        <div className={styles.panel} aria-label="Pay bill">
          {checkout ? (
            <CheckoutFlow checkout={checkout} watch={owed} />
          ) : (
            <>
              <fieldset className={styles.methods}>
                <legend>Pay bill for {paying.period}</legend>
                {choices.map((m) => (
                  <label key={m} className={styles.method}>
                    <input type="radio" name="bill-method" value={m} checked={method === m} onChange={() => setMethod(m)} />
                    {METHOD_LABEL[m]}
                  </label>
                ))}
              </fieldset>
              {error && <p role="alert" className={styles.err}>{error}</p>}
              <div className={styles.actions}>
                <Button type="button" loading={busy} disabled={!method} onClick={pay}>Pay {formatInr(paying.amount)}</Button>
                <Button type="button" variant="ghost" onClick={() => setPaying(null)}>Cancel</Button>
              </div>
            </>
          )}
        </div>
      )}
    </section>
  );
}
