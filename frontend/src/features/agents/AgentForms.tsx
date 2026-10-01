"use client";
import { useState } from "react";
import styles from "./agents.module.css";
import { Button } from "@/ui/Button";
import { Field } from "@/ui/Field";
import { TextArea } from "@/ui/TextArea";
import { createAgentAction, createVersionAction } from "@/app/(app)/developer/agents/actions";
import { parseManifestInput } from "./model";
import { useGuarded } from "./useGuarded";

export function NewAgentForm({ onDone }: { onDone: () => void }) {
  const [name, setName] = useState("");
  const [category, setCategory] = useState("");
  const [description, setDescription] = useState("");
  const [errors, setErrors] = useState<{ name?: string; category?: string; form?: string }>({});
  const { busy, run } = useGuarded();

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const next: typeof errors = {};
    if (!name.trim()) next.name = "Give the agent a name.";
    if (!category.trim()) next.category = "Add a category, for example Finance.";
    setErrors(next);
    if (next.name || next.category) return;
    void run(async () => {
      const res = await createAgentAction({ name, category, description });
      if (!res.ok) { setErrors({ form: res.failure.text }); return; }
      onDone();
    });
  };

  return (
    <form className={styles.form} aria-label="New agent" onSubmit={submit}>
      <Field label="Name" value={name} error={errors.name} onChange={(e) => setName(e.target.value)} />
      <Field label="Category" value={category} error={errors.category} onChange={(e) => setCategory(e.target.value)} />
      <Field label="Description (optional)" value={description} onChange={(e) => setDescription(e.target.value)} />
      {errors.form && <p role="alert" className={styles.err}>{errors.form}</p>}
      <div className={styles.actions}>
        <Button type="submit" loading={busy}>Create agent</Button>
        <Button type="button" variant="ghost" onClick={onDone}>Cancel</Button>
      </div>
    </form>
  );
}

export function NewVersionForm({ agentId, onDone }: { agentId: string; onDone: () => void }) {
  const [versionNumber, setVersionNumber] = useState("");
  const [changelog, setChangelog] = useState("");
  const [manifest, setManifest] = useState("");
  const [errors, setErrors] = useState<{ versionNumber?: string; manifest?: string; form?: string }>({});
  const { busy, run } = useGuarded();

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const next: typeof errors = {};
    if (!versionNumber.trim()) next.versionNumber = "Add a version number, for example 1.0.0.";
    const parsed = parseManifestInput(manifest);
    if (!parsed.ok) next.manifest = parsed.error;
    setErrors(next);
    if (next.versionNumber || !parsed.ok) return;
    void run(async () => {
      const res = await createVersionAction(agentId, { versionNumber, manifestJson: parsed.text, changelog });
      if (!res.ok) {
        setErrors(res.failure.field === "manifest" ? { manifest: res.failure.text } : { form: res.failure.text });
        return;
      }
      onDone();
    });
  };

  return (
    <form className={styles.form} aria-label="New version" onSubmit={submit}>
      <Field label="Version number" value={versionNumber} placeholder="1.0.0" error={errors.versionNumber} onChange={(e) => setVersionNumber(e.target.value)} />
      <Field label="Changelog (optional)" value={changelog} onChange={(e) => setChangelog(e.target.value)} />
      <TextArea label="Manifest JSON" className="mono" value={manifest} error={errors.manifest} onChange={(e) => setManifest(e.target.value)} />
      {errors.form && <p role="alert" className={styles.err}>{errors.form}</p>}
      <div className={styles.actions}>
        <Button type="submit" loading={busy}>Create version</Button>
        <Button type="button" variant="ghost" onClick={onDone}>Cancel</Button>
      </div>
    </form>
  );
}
