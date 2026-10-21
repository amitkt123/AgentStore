"use client";
import { useRef, useState } from "react";
import styles from "./adminmoney.module.css";
import { Button } from "@/ui/Button";
import { Field } from "@/ui/Field";
import { ResponsiveTable } from "@/ui/ResponsiveTable";
import { adjustWalletAction, saveSettingsAction } from "@/app/(app)/admin/money/actions";
import { formatInr } from "@/lib/format";
import type { BillingMode, OrgMoney, OrgMoneyPatch, PlatformAccounts } from "@/lib/api/types";

type Result = { org: OrgMoney | null; notice?: string } | { error: string };
const GENERIC = "Something went wrong; try again";
type Status = { kind: "ok" | "error"; text: string } | null;

// A form action in flight blocks a second submit (React 18 does not hold isPending across async work).
function useSubmit(onFail: (text: string) => void) {
  const busyRef = useRef(false);
  const [busy, setBusy] = useState(false);
  const submit = async (fn: () => Promise<void>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try { await fn(); } catch { onFail(GENERIC); } finally { busyRef.current = false; setBusy(false); }
  };
  return { busy, submit };
}

function Message({ status }: { status: Status }) {
  if (!status) return null;
  return <p className={status.kind === "ok" ? styles.ok : styles.err} role={status.kind === "error" ? "alert" : "status"}>{status.text}</p>;
}

export function AdminMoneyView({ accounts, org: initial, orgQuery, lookupError }: {
  accounts: PlatformAccounts; org: OrgMoney | null; orgQuery?: string; lookupError?: string;
}) {
  const [org, setOrg] = useState<OrgMoney | null>(initial);
  return (
    <div className={styles.wrap}>
      <section className={styles.section}>
        <h2>Platform balances</h2>
        {!accounts.totalsCheck && <p role="alert" className={styles.warn}>Ledger does not balance</p>}
        {!accounts.balancesMatchPostings && (
          <p role="alert" className={styles.warn}>Some account balances do not match their postings</p>
        )}
        {!accounts.walletsMatchLedger && (
          <p role="alert" className={styles.warn}>Some wallet balances do not match the ledger</p>
        )}
        <ResponsiveTable
          rows={accounts.accounts}
          rowKey={(a) => a.kind}
          empty={<p>No ledger accounts yet.</p>}
          columns={[
            { key: "kind", header: "Account", render: (a) => a.kind },
            { key: "balance", header: "Balance", render: (a) => <span className="mono">{formatInr(a.balance)}</span> },
          ]}
        />
      </section>
      <section className={styles.section}>
        <h2>Org</h2>
        <form className={styles.lookup} method="get">
          <Field label="Org id" name="org" defaultValue={orgQuery ?? ""} />
          <Button type="submit" variant="ghost">Look up</Button>
        </form>
        {lookupError && <p className={styles.err} role="alert">{lookupError}</p>}
        {org && <OrgPanel key={org.orgId} org={org} onChange={setOrg} />}
      </section>
    </div>
  );
}

function OrgPanel({ org, onChange }: { org: OrgMoney; onChange: (o: OrgMoney) => void }) {
  return (
    <>
      <dl className={styles.facts}>
        {([
          ["Name", org.name],
          ["Type", org.type],
          ["Fee", `${org.effectiveFeePercent}%${org.feePercent === null ? " (default)" : ""}`],
          ["Billing mode", org.effectiveBillingMode],
          ["Credit limit", org.creditLimit === null ? "None" : formatInr(org.creditLimit)],
          ["Wallet balance", formatInr(org.walletBalance)],
          ["Payable balance", formatInr(org.payableBalance)],
        ] as [string, string][]).map(([label, value]) => (
          <div key={label} className={styles.fact}><dt>{label}</dt><dd>{value}</dd></div>
        ))}
      </dl>
      <SettingsForm org={org} onChange={onChange} />
      {org.type === "BUYER" && <AdjustForm org={org} onChange={onChange} />}
    </>
  );
}

function SettingsForm({ org, onChange }: { org: OrgMoney; onChange: (o: OrgMoney) => void }) {
  const [fee, setFee] = useState("");
  const [mode, setMode] = useState<BillingMode>(org.effectiveBillingMode);
  const [limit, setLimit] = useState("");
  const [status, setStatus] = useState<Status>(null);
  const { busy, submit } = useSubmit((text) => setStatus({ kind: "error", text }));

  const onSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setStatus(null);
    const patch: OrgMoneyPatch = {};
    if (fee.trim() !== "") patch.feePercent = Number(fee);
    if (limit.trim() !== "") patch.creditLimit = Number(limit);
    if (mode !== org.effectiveBillingMode) patch.billingMode = mode;
    if (Number.isNaN(patch.feePercent) || Number.isNaN(patch.creditLimit)) {
      setStatus({ kind: "error", text: "Fee and credit limit must be numbers" });
      return;
    }
    if (Object.keys(patch).length === 0) { setStatus({ kind: "error", text: "Nothing to change" }); return; }
    void submit(async () => {
      const res = await saveSettingsAction(org.orgId, patch);
      if ("error" in res) { setStatus({ kind: "error", text: res.error }); return; }
      onChange(res.org);
      setStatus({ kind: "ok", text: "Saved" });
    });
  };

  return (
    <form className={styles.form} onSubmit={onSubmit} aria-label="Money settings">
      <Field label="Fee %" type="number" step="0.01" min="0" max="100" value={fee} onChange={(e) => setFee(e.target.value)} hint={`Blank keeps ${org.effectiveFeePercent}%`} />
      {org.type === "BUYER" && (
        <>
          <div className={styles.select}>
            <label htmlFor="billing-mode">Billing mode</label>
            <select id="billing-mode" value={mode} onChange={(e) => setMode(e.target.value as BillingMode)}>
              <option value="PREPAID">PREPAID</option>
              <option value="POSTPAID">POSTPAID</option>
            </select>
          </div>
          <Field label="Credit limit" type="number" step="0.01" min="0" value={limit} onChange={(e) => setLimit(e.target.value)} hint="Blank keeps the current limit" />
        </>
      )}
      <Button type="submit" loading={busy}>Save settings</Button>
      <Message status={status} />
    </form>
  );
}

function AdjustForm({ org, onChange }: { org: OrgMoney; onChange: (o: OrgMoney) => void }) {
  const [amount, setAmount] = useState("");
  const [note, setNote] = useState("");
  const [status, setStatus] = useState<Status>(null);
  const { busy, submit } = useSubmit((text) => setStatus({ kind: "error", text }));
  // One id per attempt: kept across failed submits so a retry is a server-side replay, renewed on success or edit.
  const requestId = useRef<string | null>(null);

  const onSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setStatus(null);
    const value = Number(amount);
    if (amount.trim() === "" || Number.isNaN(value) || value === 0) {
      setStatus({ kind: "error", text: "Enter a non-zero amount (negative debits the wallet)" });
      return;
    }
    if (!note.trim()) { setStatus({ kind: "error", text: "A note is required" }); return; }
    void submit(async () => {
      requestId.current ??= crypto.randomUUID();
      const res: Result = await adjustWalletAction(org.orgId, value, note.trim(), requestId.current);
      if ("error" in res) { setStatus({ kind: "error", text: res.error }); return; }
      requestId.current = null;
      if (res.org) onChange(res.org);
      setAmount("");
      setNote("");
      setStatus({ kind: "ok", text: res.notice ?? "Wallet adjusted" });
    });
  };

  return (
    <form className={styles.form} onSubmit={onSubmit} aria-label="Adjust wallet">
      <Field label="Amount" type="number" step="0.01" value={amount} onChange={(e) => { requestId.current = null; setAmount(e.target.value); }} hint="Negative debits the wallet" />
      <Field label="Note" value={note} onChange={(e) => { requestId.current = null; setNote(e.target.value); }} />
      <Button type="submit" loading={busy}>Adjust wallet</Button>
      <Message status={status} />
    </form>
  );
}
