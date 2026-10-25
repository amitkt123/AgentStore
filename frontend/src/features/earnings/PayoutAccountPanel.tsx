"use client";
import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./earnings.module.css";
import { Button } from "@/ui/Button";
import { Field } from "@/ui/Field";
import { Segmented } from "@/ui/Segmented";
import { savePayoutAccount } from "@/app/(app)/developer/earnings/actions";
import { GENERIC_ERROR, accountLabel, failureText } from "./labels";
import type { PayoutAccount, PayoutAccountBody, PayoutAccountType } from "@/lib/api/types";

const IFSC = /^[A-Z]{4}0[A-Z0-9]{6}$/;
const VPA = /^[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9]{1,63}$/;
const ACCOUNT_NUMBER = /^\d{9,18}$/;

const triesLeft = (n: number) => `${n} ${n === 1 ? "try" : "tries"} left today`;

function AccountForm({ attemptsLeft, onDone }: { attemptsLeft: number; onDone: () => void }) {
  const router = useRouter();
  const [type, setType] = useState<PayoutAccountType>("BANK");
  const [holder, setHolder] = useState("");
  const [number, setNumber] = useState("");
  const [ifsc, setIfsc] = useState("");
  const [vpa, setVpa] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busyRef.current) return;
    const errs: Record<string, string> = {};
    if (!holder.trim()) errs.holder = "Enter the name on the account";
    const code = ifsc.trim().toUpperCase();
    if (type === "BANK") {
      if (!ACCOUNT_NUMBER.test(number.trim())) errs.number = "Enter 9 to 18 digits";
      if (!IFSC.test(code)) errs.ifsc = "Enter a valid IFSC, like HDFC0001234";
    } else if (!VPA.test(vpa.trim())) errs.vpa = "Enter a valid UPI ID, like name@bank";
    setErrors(errs);
    setSubmitError(null);
    if (Object.keys(errs).length > 0) return;
    const body: PayoutAccountBody = type === "BANK"
      ? { type, accountNumber: number.trim(), ifsc: code, holderName: holder.trim() }
      : { type, vpa: vpa.trim(), holderName: holder.trim() };
    busyRef.current = true;
    setBusy(true);
    try {
      const res = await savePayoutAccount(body);
      if ("error" in res) { setSubmitError(res.error); return; }
      setHolder(""); setNumber(""); setIfsc(""); setVpa("");
      onDone();
      router.refresh();
    } catch {
      setSubmitError(GENERIC_ERROR);
    } finally { busyRef.current = false; setBusy(false); }
  };

  return (
    <form className={styles.form} onSubmit={submit} noValidate>
      <h3>Add payout account</h3>
      <Segmented<PayoutAccountType>
        label="Account type" value={type} onChange={setType}
        options={[{ value: "BANK", label: "Bank" }, { value: "VPA", label: "UPI" }]}
      />
      <Field label="Name on account" value={holder} error={errors.holder} onChange={(e) => setHolder(e.target.value)} autoComplete="off" />
      {type === "BANK" ? (
        <>
          <Field label="Account number" inputMode="numeric" value={number} error={errors.number} onChange={(e) => setNumber(e.target.value)} autoComplete="off" />
          <Field label="IFSC" value={ifsc} error={errors.ifsc} onChange={(e) => setIfsc(e.target.value)} autoComplete="off" />
        </>
      ) : (
        <Field label="UPI ID" value={vpa} error={errors.vpa} onChange={(e) => setVpa(e.target.value)} autoComplete="off" />
      )}
      {submitError && <p role="alert" className={styles.err}>{submitError}</p>}
      <div className={styles.actions}>
        <Button type="submit" loading={busy} disabled={attemptsLeft <= 0}>Verify account</Button>
        <span className={styles.note}>{triesLeft(attemptsLeft)}</span>
      </div>
    </form>
  );
}

export function PayoutAccountPanel({ account, attemptsLeft, verifiedName = null }: { account: PayoutAccount | null; attemptsLeft: number; verifiedName?: string | null }) {
  const [replacing, setReplacing] = useState(false);
  const retry = (label: string) => <Button type="button" variant="ghost" onClick={() => setReplacing(true)}>{label}</Button>;

  let body: React.ReactNode;
  if (!account || replacing) {
    body = <AccountForm attemptsLeft={attemptsLeft} onDone={() => setReplacing(false)} />;
  } else if (account.status === "PENDING") {
    body = <p>Checking your account…</p>;
  } else if (account.status === "VERIFIED") {
    body = (
      <>
        <p className="mono">{account.type === "BANK" ? `${accountLabel(account)} · ${account.ifsc}` : accountLabel(account)}</p>
        {account.validatedName && <p className={styles.note}>Name on account: {account.validatedName}</p>}
        {retry("Change account")}
      </>
    );
  } else if (account.failureReason === "NAME_MISMATCH") {
    body = (
      <>
        <p>{`The bank says this account belongs to ${account.validatedName}, which does not match ${verifiedName ?? account.holderName}. Karya will review it, or add a different account.`}</p>
        {retry("Add a different account")}
      </>
    );
  } else {
    body = (
      <>
        <p>{failureText(account.failureReason)}</p>
        {retry("Try another account")}
      </>
    );
  }
  return (
    <section className={styles.panel} aria-label="Payout account">
      <h2>Payout account</h2>
      {body}
    </section>
  );
}
