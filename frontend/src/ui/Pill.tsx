import styles from "./Pill.module.css";

export type Tone = "neutral" | "ok" | "warn" | "bad" | "info";

export function Pill({ tone = "neutral", children }: { tone?: Tone; children: React.ReactNode }) {
  return <span className={`${styles.pill} ${styles[tone]}`}>{children}</span>;
}
