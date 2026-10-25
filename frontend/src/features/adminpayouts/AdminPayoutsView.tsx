"use client";
import { useRef, useState } from "react";
import styles from "./adminpayouts.module.css";
import { Button } from "@/ui/Button";
import { Field } from "@/ui/Field";
import { Pill, type Tone } from "@/ui/Pill";
import { ResponsiveTable } from "@/ui/ResponsiveTable";
import { approveAccountAction, loadWithdrawalsAction } from "@/app/(app)/admin/payouts/actions";
import type { WithdrawalFilter } from "@/lib/api/money";
import { accountLabel, GENERIC_ERROR, WITHDRAWAL_STATUS_LABEL } from "@/features/earnings/labels";
import { formatDate, formatInr } from "@/lib/format";
import type { AdminPayoutAccount, AdminWithdrawal } from "@/lib/api/types";

const TONE: Record<string, Tone> = { REQUESTED: "info", PROCESSING: "info", PROCESSED: "ok", REVERSED: "warn", FAILED: "bad" };
const FILTERS: [WithdrawalFilter, string][] = [
  ["OPEN", "Requested and processing"], ["ALL", "All"], ["REQUESTED", "Requested"], ["PROCESSING", "Processing"],
  ["PROCESSED", "Processed"], ["REVERSED", "Reversed"], ["FAILED", "Failed"],
];

export function AdminPayoutsView({ accounts: initialAccounts, withdrawals: initialWithdrawals }: {
  accounts: AdminPayoutAccount[]; withdrawals: AdminWithdrawal[];
}) {
  const [accounts, setAccounts] = useState(initialAccounts);
  const [withdrawals, setWithdrawals] = useState(initialWithdrawals);
  const [filter, setFilter] = useState<WithdrawalFilter>("OPEN");
  const [error, setError] = useState<string | null>(null);
  const latest = useRef(0);

  const onFilter = async (next: WithdrawalFilter) => {
    setFilter(next);
    setError(null);
    const ticket = ++latest.current;
    try {
      const res = await loadWithdrawalsAction(next);
      if (ticket !== latest.current) return; // a newer choice superseded this one
      if ("error" in res) setError(res.error); else setWithdrawals(res.withdrawals);
    } catch {
      if (ticket === latest.current) setError(GENERIC_ERROR);
    }
  };

  return (
    <div className={styles.wrap}>
      <section className={styles.section} aria-labelledby="review-h">
        <h2 id="review-h">Accounts to review</h2>
        {accounts.length === 0 ? <p>No accounts need review.</p> : (
          <ul className={styles.cards}>
            {accounts.map((a) => (
              <ReviewCard key={a.id} account={a} onApproved={() => setAccounts((list) => list.filter((x) => x.id !== a.id))} />
            ))}
          </ul>
        )}
      </section>
      <section className={styles.section} aria-labelledby="wd-h">
        <h2 id="wd-h">Withdrawals</h2>
        <div className={styles.select}>
          <label htmlFor="wd-status">Status</label>
          <select id="wd-status" value={filter} onChange={(e) => void onFilter(e.target.value as WithdrawalFilter)}>
            {FILTERS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </div>
        {error && <p className={styles.err} role="alert">{error}</p>}
        <ResponsiveTable
          rows={withdrawals}
          rowKey={(w) => w.id}
          empty={<p>No withdrawals.</p>}
          columns={[
            { key: "org", header: "Org", render: (w) => w.orgName ?? w.orgId },
            { key: "amount", header: "Amount", render: (w) => <span className="mono">{formatInr(w.amount)}</span> },
            { key: "account", header: "Account", render: (w) => w.accountLabel ?? "–" },
            { key: "status", header: "Status", render: (w) => <Pill tone={TONE[w.status] ?? "info"}>{WITHDRAWAL_STATUS_LABEL[w.status] ?? w.status}</Pill> },
            { key: "utr", header: "UTR", render: (w) => <span className="mono">{w.utr ?? "–"}</span> },
            { key: "created", header: "Created", render: (w) => formatDate(w.createdAt) },
          ]}
        />
      </section>
    </div>
  );
}

function ReviewCard({ account, onApproved }: { account: AdminPayoutAccount; onApproved: () => void }) {
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!note.trim()) { setError("A note is required"); return; }
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try {
      const res = await approveAccountAction(account.id, note.trim());
      if ("error" in res) setError(res.error); else onApproved();
    } catch {
      setError(GENERIC_ERROR);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  return (
    <li className={styles.card}>
      <p className={styles.org}>{account.orgName ?? account.orgId} <span className="mono">{accountLabel(account)}</span></p>
      <dl className={styles.names}>
        <div><dt>Legal name</dt><dd>{account.legalName ?? "–"}</dd></div>
        <div><dt>Bank name</dt><dd>{account.validatedName ?? "–"}</dd></div>
      </dl>
      <form className={styles.form} onSubmit={submit} aria-label={`Approve ${account.orgName ?? account.id}`}>
        <Field label="Note" value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} />
        <Button type="submit" loading={busy}>Approve</Button>
      </form>
      {error && <p className={styles.err} role="alert">{error}</p>}
    </li>
  );
}
