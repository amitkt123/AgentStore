"use client";
import { useState, useTransition } from "react";
import styles from "./files.module.css";
import { StorageBar } from "./StorageBar";
import { Button } from "@/ui/Button";
import { Drawer } from "@/ui/Drawer";
import { Dropzone } from "@/ui/Dropzone";
import { EmptyState } from "@/ui/EmptyState";
import { ResponsiveTable } from "@/ui/ResponsiveTable";
import { deleteFileAction, uploadFileAction } from "@/app/(app)/files/actions";
import { formatTime } from "@/lib/format";
import type { Dataset, Storage } from "@/lib/api/types";

export function FilesView({ datasets, storage }: { datasets: Dataset[]; storage: Storage }) {
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<Dataset | null>(null);
  const [pending, start] = useTransition();

  const upload = (file: File) =>
    start(async () => {
      setError(null);
      const fd = new FormData();
      fd.set("file", file);
      const res = await uploadFileAction(fd);
      if (res.error) setError(res.error);
    });

  const remove = (d: Dataset) =>
    start(async () => {
      setError(null);
      const res = await deleteFileAction(d.id, (d.usage ?? []).map((u) => ({ connectionId: u.connectionId, resource: u.resource })));
      if (res.error) setError(res.error);
      else setOpen(null);
    });

  return (
    <div className={styles.wrap}>
      <StorageBar usedBytes={storage.usedBytes} limitBytes={storage.limitBytes} />
      <Dropzone label="Upload file" onFile={upload} disabled={pending} />
      {error && <p role="alert" className={styles.err}>{error}</p>}
      <ResponsiveTable
        rows={datasets}
        rowKey={(d) => d.id}
        empty={<EmptyState title="No files yet" body="An agent only sees the files you plug into it. Upload one to start." />}
        columns={[
          { key: "file", header: "File", render: (d) => <><strong>{d.displayName}</strong>{d.sourceLabel && <div className={styles.sub}>{d.sourceLabel}</div>}</> },
          { key: "rows", header: "Rows", render: (d) => <span className="mono">{d.rowCount ?? "–"}</span> },
          { key: "use", header: "Plugged into", render: (d) => (d.usage?.length ? d.usage.map((u) => <div key={`${u.connectionId}${u.resource}`}>{u.agent}</div>) : "Not plugged in") },
          { key: "added", header: "Added", render: (d) => <span className="mono">{formatTime(d.createdAt)}</span> },
          { key: "go", header: "", render: (d) => <Button variant="ghost" onClick={() => setOpen(d)} aria-label={`Where ${d.displayName} goes`}>Where it goes</Button> },
        ]}
      />
      <Drawer open={!!open} title="Where this file goes" onClose={() => setOpen(null)}>
        {open && (
          <>
            <h3>{open.displayName}</h3>
            <p className="mono">{open.columns.join(", ")}</p>
            {open.usage?.length ? (
              <ul>{open.usage.map((u) => <li key={`${u.connectionId}${u.resource}`}>{u.agent} {u.version} reads it as {u.resource}</li>)}</ul>
            ) : <p>Not plugged into any agent.</p>}
            <Button variant="danger" loading={pending} onClick={() => remove(open)}>Unplug and delete</Button>
          </>
        )}
      </Drawer>
    </div>
  );
}
