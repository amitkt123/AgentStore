"use client";
import { useId, useState } from "react";
import styles from "./Dropzone.module.css";
import { Icon } from "./Icon";

export function Dropzone({ onFile, label, disabled }: { onFile: (f: File) => void; label: string; disabled?: boolean }) {
  const id = useId();
  const [over, setOver] = useState(false);
  return (
    <div
      className={`${styles.zone} ${over ? styles.over : ""}`}
      onDragOver={(e) => { e.preventDefault(); setOver(true); }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => { e.preventDefault(); setOver(false); const f = e.dataTransfer.files?.[0]; if (f && !disabled) onFile(f); }}
    >
      <Icon name="upload" />
      <label htmlFor={id}>{label}</label>
      <span className={styles.hint}>or drop a file here</span>
      <input id={id} type="file" className={styles.input} disabled={disabled} onChange={(e) => { const f = e.target.files?.[0]; if (f) onFile(f); e.target.value = ""; }} />
    </div>
  );
}
