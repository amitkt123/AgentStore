"use client";
import { useRef, useState } from "react";
import styles from "./reconciliation.module.css";
import { Button } from "@/ui/Button";
import { Field } from "@/ui/Field";
import { ResponsiveTable } from "@/ui/ResponsiveTable";
import { loadIssuesAction, resolveIssueAction, runReconciliationAction } from "@/app/(app)/admin/reconciliation/actions";
import { GENERIC_ERROR } from "@/features/earnings/labels";
import { formatDate } from "@/lib/format";
import type { ReconciliationIssue } from "@/lib/api/types";

type Result = { issues: ReconciliationIssue[] } | { error: string };

const detailLines = (detail: ReconciliationIssue["detail"]): string[] =>
  detail ? Object.entries(detail).map(([k, v]) => `${k}: ${typeof v === "object" && v !== null ? JSON.stringify(v) : String(v)}`) : [];

export function ReconciliationView({ date: initialDate, issues: initialIssues }: { date: string; issues: ReconciliationIssue[] }) {
  const [date, setDate] = useState(initialDate);
  const [issues, setIssues] = useState(initialIssues);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const latest = useRef(0);
  const busyRef = useRef(false);

  // Latest call wins: a slow reply for an earlier date or action must not overwrite a newer one.
  const call = async (fn: () => Promise<Result>): Promise<boolean> => {
    setError(null);
    const ticket = ++latest.current;
    try {
      const res = await fn();
      if (ticket !== latest.current) return false;
      if ("error" in res) { setError(res.error); return false; }
      setIssues(res.issues);
      return true;
    } catch {
      if (ticket === latest.current) setError(GENERIC_ERROR);
      return false;
    }
  };

  const onDate = (next: string) => {
    setDate(next);
    if (next) void call(() => loadIssuesAction(next));
  };
  const onRun = async () => {
    if (busyRef.current || !date) return;
    busyRef.current = true;
    setBusy(true);
    try { await call(() => runReconciliationAction(date)); } finally { busyRef.current = false; setBusy(false); }
  };

  return (
    <div className={styles.wrap}>
      <div className={styles.bar}>
        <Field label="Date" type="date" value={date} onChange={(e) => onDate(e.target.value)} />
        <Button type="button" loading={busy} disabled={!date} onClick={() => void onRun()}>Run now</Button>
      </div>
      {error && <p className={styles.err} role="alert">{error}</p>}
      <ResponsiveTable
        rows={issues}
        rowKey={(i) => i.id}
        empty={<p>No issues for this day.</p>}
        columns={[
          { key: "kind", header: "Kind", render: (i) => i.kind },
          { key: "ref", header: "Ref", render: (i) => <span className="mono">{i.providerRef ?? "–"}</span> },
          { key: "detail", header: "Detail", render: (i) => (
            <ul className={styles.detail}>{detailLines(i.detail).map((line) => <li key={line}>{line}</li>)}</ul>
          ) },
          { key: "created", header: "Created", render: (i) => formatDate(i.createdAt) },
          { key: "resolved", header: "Resolved", render: (i) => i.resolvedAt
            ? <span>Resolved {formatDate(i.resolvedAt)}</span>
            : <ResolveForm issue={i} onResolve={(note) => call(() => resolveIssueAction(i.id, note, date))} /> },
        ]}
      />
    </div>
  );
}

function ResolveForm({ issue, onResolve }: { issue: ReconciliationIssue; onResolve: (note: string) => Promise<boolean> }) {
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
    try { await onResolve(note.trim()); } finally { busyRef.current = false; setBusy(false); }
  };

  return (
    <form className={styles.resolve} onSubmit={submit} aria-label={`Resolve ${issue.providerRef ?? issue.kind}`}>
      <Field label="Note" value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} error={error ?? undefined} />
      <Button type="submit" variant="ghost" loading={busy}>Resolve</Button>
    </form>
  );
}
