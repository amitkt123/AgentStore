import styles from "./states.module.css";

export function Skeleton({ lines = 3 }: { lines?: number }) {
  return (
    <div aria-busy="true" aria-label="Loading" className={styles.skel}>
      {Array.from({ length: lines }, (_, i) => (
        <div key={i} className={styles.line} style={{ width: `${90 - i * 12}%` }} />
      ))}
    </div>
  );
}
