import styles from "./files.module.css";
import { formatBytes } from "@/lib/format";

export function StorageBar({ usedBytes, limitBytes }: { usedBytes: number; limitBytes: number }) {
  const pct = limitBytes > 0 ? Math.min(100, Math.round((usedBytes / limitBytes) * 100)) : 0;
  const full = limitBytes > 0 && usedBytes >= limitBytes;
  return (
    <div className={styles.storage}>
      <div role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct} aria-label="Storage used" className={styles.bar}>
        <span style={{ width: `${pct}%` }} />
      </div>
      <span>{formatBytes(usedBytes)} of {formatBytes(limitBytes)} used</span>
      {full && <strong role="status">Storage is full. Delete a file to upload more.</strong>}
    </div>
  );
}
