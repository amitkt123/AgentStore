import styles from "./KeyValue.module.css";

export function KeyValue({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className={styles.kv}>
      <span>{label}</span>
      <span>{children}</span>
    </div>
  );
}
