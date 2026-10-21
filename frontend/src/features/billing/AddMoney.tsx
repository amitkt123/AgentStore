"use client";
import { useEffect, useRef, useState } from "react";
import styles from "./billing.module.css";
import { Button } from "@/ui/Button";
import { Field } from "@/ui/Field";
import { loadBankDetails, startTopUp } from "@/app/(app)/billing/actions";
import { BankTransferDetails } from "./BankTransferDetails";
import { CheckoutFlow } from "./CheckoutFlow";
import { METHOD_LABEL } from "./labels";
import { formatMoney } from "@/lib/format";
import type { BankTransferDetails as Details, Checkout, PaymentMethod, PaymentOptions } from "@/lib/api/types";

export function AddMoney({ options, balance }: { options: PaymentOptions; balance: number }) {
  const [open, setOpen] = useState(false);
  const [amount, setAmount] = useState("");
  const [method, setMethod] = useState<PaymentMethod | null>(options.methods[0] ?? null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [checkout, setCheckout] = useState<Checkout | null>(null);
  const [details, setDetails] = useState<Details | null>(null);
  const [detailsError, setDetailsError] = useState<string | null>(null);

  useEffect(() => {
    if (method !== "BANK_TRANSFER" || details) return;
    let live = true;
    void loadBankDetails().then((res) => {
      if (!live) return;
      if ("error" in res) setDetailsError(res.error); else setDetails(res.details);
    });
    return () => { live = false; };
  }, [method, details]);

  if (!open) return <div><Button type="button" onClick={() => setOpen(true)}>Add money</Button></div>;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busyRef.current || !method) return;
    const value = Number(amount);
    if (!amount || !Number.isFinite(value) || value < options.topUpMin) {
      setError(`Minimum is ${formatMoney(options.topUpMin)}`);
      return;
    }
    if (value > options.topUpRemainingToday) {
      setError(`You can add up to ${formatMoney(options.topUpRemainingToday)} more today`);
      return;
    }
    setError(null);
    busyRef.current = true;
    setBusy(true);
    try {
      const res = await startTopUp(value, method);
      if ("error" in res) setError(res.error); else setCheckout(res.checkout);
    } finally { busyRef.current = false; setBusy(false); }
  };

  return (
    <section className={styles.panel} aria-label="Add money">
      {checkout ? (
        <CheckoutFlow checkout={checkout} watch={balance} />
      ) : (
        <form onSubmit={submit} noValidate>
          <fieldset className={styles.methods}>
            <legend>Payment method</legend>
            {options.methods.map((m) => (
              <label key={m} className={styles.method}>
                <input type="radio" name="method" value={m} checked={method === m} onChange={() => { setMethod(m); setError(null); }} />
                {METHOD_LABEL[m]}
              </label>
            ))}
          </fieldset>
          {method === "BANK_TRANSFER" ? (
            details ? <BankTransferDetails details={details} />
              : <p className={detailsError ? styles.err : styles.note}>{detailsError ?? "Loading bank details…"}</p>
          ) : (
            <>
              <Field
                label="Amount (₹)" type="number" inputMode="decimal" min={options.topUpMin} value={amount}
                hint={`Minimum ${formatMoney(options.topUpMin)}`}
                error={error ?? undefined}
                onChange={(e) => { setAmount(e.target.value); setError(null); }}
              />
              <div className={styles.actions}>
                <Button type="submit" loading={busy}>Continue</Button>
                <Button type="button" variant="ghost" onClick={() => setOpen(false)}>Cancel</Button>
              </div>
            </>
          )}
        </form>
      )}
    </section>
  );
}
