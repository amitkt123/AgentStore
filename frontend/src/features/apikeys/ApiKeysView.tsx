"use client";
import { useRef, useState } from "react";
import styles from "./apikeys.module.css";
import { Button } from "@/ui/Button";
import { Drawer } from "@/ui/Drawer";
import { EmptyState } from "@/ui/EmptyState";
import { Field } from "@/ui/Field";
import { Pill } from "@/ui/Pill";
import { ResponsiveTable } from "@/ui/ResponsiveTable";
import { createKeyAction, revokeKeyAction } from "@/app/(app)/developer/api-keys/actions";
import { formatDate } from "@/lib/format";
import type { CreatedApiKey, DeveloperApiKey } from "@/lib/api/types";

export function ApiKeysView({ keys }: { keys: DeveloperApiKey[] }) {
  const [name, setName] = useState("");
  // The full key is held here and nowhere else; it is gone once dismissed.
  const [revealed, setRevealed] = useState<CreatedApiKey | null>(null);
  const [copied, setCopied] = useState(false);
  const [confirm, setConfirm] = useState<DeveloperApiKey | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);

  // React 18 does not hold isPending across an async transition, so guard double clicks by hand.
  const run = async (fn: () => Promise<void>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try { await fn(); } finally { busyRef.current = false; setBusy(false); }
  };

  const create = (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim() || revealed) return;
    setError(null);
    void run(async () => {
      const res = await createKeyAction(name.trim());
      if ("error" in res) { setError(res.error); return; }
      setRevealed(res.key);
      setCopied(false);
      setName("");
    });
  };

  const copy = async () => {
    if (!revealed) return;
    try {
      await navigator.clipboard.writeText(revealed.key);
      setCopied(true);
    } catch {
      setError("Copy didn't work. Select the key and copy it by hand.");
    }
  };

  const revoke = () => {
    if (!confirm) return;
    setError(null);
    void run(async () => {
      const res = await revokeKeyAction(confirm.id);
      if (res.error) setError(res.error);
      else setConfirm(null);
    });
  };

  return (
    <div className={styles.wrap}>
      <form className={styles.create} onSubmit={create}>
        <Field label="Key name" value={name} maxLength={100} placeholder="For example, my laptop" disabled={!!revealed} onChange={(e) => setName(e.target.value)} />
        <Button type="submit" loading={busy} disabled={!!revealed}>Create key</Button>
      </form>
      {error && !confirm && <p role="alert" className={styles.err}>{error}</p>}
      {revealed && (
        <section className={styles.reveal} aria-label="New API key">
          <strong>Copy your key now</strong>
          <code className={`mono ${styles.key}`}>{revealed.key}</code>
          <p>You won&apos;t see this again.</p>
          <div className={styles.actions}>
            <Button variant="ghost" onClick={copy}>{copied ? "Copied" : "Copy"}</Button>
            <Button onClick={() => setRevealed(null)}>Done</Button>
          </div>
        </section>
      )}
      <ResponsiveTable
        rows={keys}
        rowKey={(k) => k.id}
        empty={<EmptyState title="No API keys yet" body="Run `karya login` in your terminal to sign the CLI in, or create a key above." />}
        columns={[
          { key: "name", header: "Name", render: (k) => <strong>{k.name}</strong> },
          { key: "prefix", header: "Key", render: (k) => <span className="mono">{k.prefix}…</span> },
          { key: "created", header: "Created", render: (k) => formatDate(k.createdAt) },
          { key: "used", header: "Last used", render: (k) => (k.lastUsedAt ? formatDate(k.lastUsedAt) : "Never") },
          { key: "status", header: "Status", render: (k) => (k.revokedAt ? <Pill tone="neutral">Revoked</Pill> : <Pill tone="ok">Active</Pill>) },
          { key: "go", header: "", render: (k) => (k.revokedAt ? null : <Button variant="ghost" aria-label={`Revoke ${k.name}`} onClick={() => { setError(null); setConfirm(k); }}>Revoke</Button>) },
        ]}
      />
      <Drawer open={!!confirm} title="Revoke this key?" onClose={() => { setConfirm(null); setError(null); }}>
        {confirm && (
          <>
            <h3>{confirm.name}</h3>
            <p>Anything using <span className="mono">{confirm.prefix}…</span> stops working straight away. This can&apos;t be undone.</p>
            {error && <p role="alert" className={styles.err}>{error}</p>}
            <Button variant="danger" loading={busy} onClick={revoke}>Revoke key</Button>
          </>
        )}
      </Drawer>
    </div>
  );
}
