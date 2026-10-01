"use client";
import { useState } from "react";
import styles from "./studio.module.css";
import { Button } from "@/ui/Button";
import { Field } from "@/ui/Field";
import { patchAction, submitAction } from "@/app/(app)/developer/agents/actions";
import { DATA_FORMATS } from "@/lib/api/enums";
import type { ManifestPatchRequest, Scope, VersionStatus } from "@/lib/api/types";
import { useStudio } from "./feedback";

type Patch = (req: ManifestPatchRequest, onOk?: () => void) => Promise<void>;

export function usePatch(agentId: string, versionId: string): Patch {
  const { setMessage, run } = useStudio();
  return (req, onOk) =>
    run(async () => {
      setMessage(null);
      const res = await patchAction(agentId, versionId, req);
      if (!res.ok) { setMessage({ tone: "bad", text: res.failure.text }); return; }
      setMessage({ tone: "ok", text: res.message ?? "Saved." });
      onOk?.();
    });
}

export function RemoveScopeButton({ agentId, versionId, scope }: { agentId: string; versionId: string; scope: Scope }) {
  const patch = usePatch(agentId, versionId);
  const { busy } = useStudio();
  return (
    <Button
      variant="ghost"
      loading={busy}
      aria-label={`Remove ${scope.type.toLowerCase()} scope ${scope.resource}`}
      onClick={() => void patch({ op: "remove_scope", type: scope.type, resource: scope.resource })}
    >
      Remove
    </Button>
  );
}

function AddScopeForm({ patch }: { patch: Patch }) {
  const { busy } = useStudio();
  const [resource, setResource] = useState("");
  const [description, setDescription] = useState("");
  const [approval, setApproval] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!resource.trim()) { setError("Enter the resource to read, for example bank_statement."); return; }
    setError(null);
    void patch({ op: "add_read_scope", resource: resource.trim(), description: description.trim(), requiresApproval: approval }, () => {
      setResource(""); setDescription(""); setApproval(false);
    });
  };

  return (
    <form className={styles.form} aria-label="Add a read scope" onSubmit={submit}>
      <h3>Add a read scope</h3>
      <Field label="Resource" value={resource} error={error ?? undefined} onChange={(e) => setResource(e.target.value)} />
      <Field label="Description" value={description} onChange={(e) => setDescription(e.target.value)} />
      <label className={styles.chk}>
        <input type="checkbox" checked={approval} onChange={(e) => setApproval(e.target.checked)} />
        Needs approval before it runs
      </label>
      <div><Button type="submit" loading={busy}>Add scope</Button></div>
    </form>
  );
}

function AddResourceForm({ patch }: { patch: Patch }) {
  const { busy } = useStudio();
  const [name, setName] = useState("");
  const [format, setFormat] = useState<string>(DATA_FORMATS[0]);
  const [error, setError] = useState<string | null>(null);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) { setError("Enter a name for the resource."); return; }
    setError(null);
    void patch({ op: "add_resource", name: name.trim(), format: format.toLowerCase() }, () => setName(""));
  };

  return (
    <form className={styles.form} aria-label="Add a resource" onSubmit={submit}>
      <h3>Add a resource</h3>
      <Field label="Name" value={name} error={error ?? undefined} onChange={(e) => setName(e.target.value)} />
      <div className={styles.pick}>
        <label htmlFor="resource-format">Format</label>
        <select id="resource-format" className={styles.select} value={format} onChange={(e) => setFormat(e.target.value)}>
          {DATA_FORMATS.map((f) => <option key={f} value={f}>{f}</option>)}
        </select>
      </div>
      <div><Button type="submit" loading={busy}>Add resource</Button></div>
    </form>
  );
}

function AddHostForm({ patch }: { patch: Patch }) {
  const { busy } = useStudio();
  const [host, setHost] = useState("");
  const [error, setError] = useState<string | null>(null);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const value = host.trim();
    if (!value) { setError("Enter a host, like api.example.com."); return; }
    if (/\s/.test(value) || value.includes("/")) { setError("Enter just the host, like api.example.com."); return; }
    setError(null);
    void patch({ op: "add_allowlist_host", host: value }, () => setHost(""));
  };

  return (
    <form className={styles.form} aria-label="Add an allowed host" onSubmit={submit}>
      <h3>Add an allowed host</h3>
      <Field label="Host" value={host} error={error ?? undefined} onChange={(e) => setHost(e.target.value)} />
      <div><Button type="submit" loading={busy}>Add host</Button></div>
    </form>
  );
}

export function PatchForms({ agentId, versionId }: { agentId: string; versionId: string }) {
  const patch = usePatch(agentId, versionId);
  return (
    <section className={styles.fix} aria-labelledby="fix-heading">
      <h2 id="fix-heading">Fix the manifest</h2>
      <p className={styles.hint}>Each change updates the manifest straight away.</p>
      <div className={styles.forms}>
        <AddScopeForm patch={patch} />
        <AddResourceForm patch={patch} />
        <AddHostForm patch={patch} />
      </div>
    </section>
  );
}

const NOTES: Record<VersionStatus, string | null> = {
  DRAFT: null,
  IN_REVIEW: "This version is in review. It can't be edited or submitted again.",
  LIVE: "This version is live and can't be edited.",
  REJECTED: "This version was rejected and can't be edited.",
};

export function SubmitBar({ agentId, versionId, status, ready }: { agentId: string; versionId: string; status: VersionStatus; ready: boolean }) {
  const { setMessage, busy, run } = useStudio();
  const draft = status === "DRAFT";

  const submit = () =>
    void run(async () => {
      setMessage(null);
      const res = await submitAction(agentId, versionId);
      if (!res.ok) { setMessage({ tone: "bad", text: res.failure.text, verify: res.failure.verify }); return; }
      setMessage({ tone: "ok", text: res.message ?? "Submitted." });
    });

  return (
    <div className={styles.submit}>
      <Button onClick={submit} loading={busy} disabled={!draft || !ready}>Submit for review</Button>
      {draft && !ready && <p className={styles.hint}>Fix the items above before you submit.</p>}
      {NOTES[status] && <p className={styles.hint}>{NOTES[status]}</p>}
    </div>
  );
}
