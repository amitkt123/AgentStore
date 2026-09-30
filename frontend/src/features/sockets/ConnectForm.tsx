"use client";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import styles from "./sockets.module.css";
import { defaultCap, grantedScopesOf, socketsOf } from "./sockets";
import { Button } from "@/ui/Button";
import { Dropzone } from "@/ui/Dropzone";
import { Pill } from "@/ui/Pill";
import {
  bindSocketAction, checkSocketAction, createConnectionAction, startRunAction, uploadForSocketAction,
} from "@/app/(app)/connect/actions";
import { formatMoney } from "@/lib/format";
import type { CheckResult, Dataset, Manifest } from "@/lib/api/types";

type Props = { agentId: string; versionId: string; agentName: string; manifest: Manifest; datasets: Dataset[]; canConnect: boolean };
type Picked = { datasetId: string; check: CheckResult | null };

export function ConnectForm({ agentId, versionId, agentName, manifest, datasets: initial, canConnect }: Props) {
  const router = useRouter();
  const sockets = socketsOf(manifest);
  const [datasets, setDatasets] = useState(initial);
  const [picked, setPicked] = useState<Record<string, Picked | undefined>>({});
  const [cap, setCap] = useState(defaultCap(manifest));
  const [connectionId, setConnectionId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const max = defaultCap(manifest);

  if (!canConnect) {
    return <p role="status">Only a workspace admin can add an agent. Ask your admin to add {agentName}.</p>;
  }

  const runCheck = async (resource: string, datasetId: string) => {
    setPicked((p) => ({ ...p, [resource]: { datasetId, check: null } }));
    const res = await checkSocketAction(datasetId, versionId, resource);
    if ("error" in res) { setError(res.error); return; }
    setPicked((p) => ({ ...p, [resource]: { datasetId, check: res.result } }));
  };

  const choose = (resource: string, datasetId: string) =>
    start(async () => {
      setError(null);
      if (!datasetId) { setPicked((p) => ({ ...p, [resource]: undefined })); return; }
      await runCheck(resource, datasetId);
    });

  const upload = (resource: string, file: File) =>
    start(async () => {
      setError(null);
      const fd = new FormData();
      fd.set("file", file);
      const res = await uploadForSocketAction(fd);
      if ("error" in res) { setError(res.error); return; }
      setDatasets((d) => [res.dataset, ...d]);
      await runCheck(resource, res.dataset.id);
    });

  const ready = sockets.every((s) => picked[s.resource]?.check?.ok) && cap > 0 && cap <= max;

  const submit = () =>
    start(async () => {
      setError(null);
      let cid = connectionId;
      if (!cid) {
        const created = await createConnectionAction(agentId, grantedScopesOf(manifest), cap);
        if ("error" in created) { setError(created.error); return; }
        cid = created.connectionId;
        setConnectionId(cid);
      }
      for (const s of sockets) {
        const bound = await bindSocketAction(cid, s.resource, picked[s.resource]!.datasetId);
        if ("error" in bound) { setError(bound.error); return; }
      }
      const ran = await startRunAction(cid);
      if ("error" in ran) { setError(ran.error); return; }
      router.push(`/runs/${ran.displayId}`);
    });

  return (
    <div className={styles.wrap}>
      {sockets.map((s) => {
        const p = picked[s.resource];
        return (
          <section key={s.resource} className={styles.socket}>
            <div>
              <span className={styles.kind}>Reads</span>
              <strong>{s.label}</strong>
              <p className={styles.sub}>{s.description}{s.requiredColumns.length ? `. Needs: ${s.requiredColumns.join(", ")}` : ""}</p>
            </div>
            <label className={styles.pick}>
              <span className="sr-only">{s.label}</span>
              <select value={p?.datasetId ?? ""} onChange={(e) => choose(s.resource, e.target.value)} disabled={pending}>
                <option value="">Choose a file</option>
                {datasets.map((d) => <option key={d.id} value={d.id}>{d.displayName}</option>)}
              </select>
            </label>
            <Dropzone label="Upload a file" onFile={(f) => upload(s.resource, f)} disabled={pending} />
            {p?.check && (
              <p role="status">
                {p.check.ok
                  ? <Pill tone="ok">{`${p.check.rowCount} rows, columns match`}</Pill>
                  : <Pill tone="bad">{p.check.missingColumns.length ? `Missing: ${p.check.missingColumns.join(", ")}` : `${p.check.rowErrorCount} rows have problems`}</Pill>}
              </p>
            )}
          </section>
        );
      })}
      <section className={styles.limits}>
        <h2>Limits</h2>
        <label>
          Most one run can cost
          <input type="number" min={1} max={max} value={cap} onChange={(e) => setCap(Number(e.target.value))} />
        </label>
        <p className={styles.sub}>{formatMoney(max, manifest.costEnvelope.currency)} is the most this agent ever charges. A run stops when it reaches your limit.</p>
      </section>
      {error && <p role="alert" className={styles.err}>{error}</p>}
      {!ready && sockets.length > 0 && <p className={styles.sub}>Plug in a file for each block above to continue.</p>}
      <Button onClick={submit} loading={pending} disabled={!ready}>Add and run now</Button>
    </div>
  );
}
