import styles from "./Stepper.module.css";
import { Icon } from "./Icon";

export interface StepperItem { key: string; name: string; hint: string; state: "todo" | "cur" | "done"; disabled?: boolean }

export function Stepper({ items, onPick, note }: { items: StepperItem[]; onPick: (key: string) => void; note?: { title: string; hint: string } }) {
  return (
    <ol className={styles.list}>
      {items.map((s, i) => (
        <li key={s.key}>
          <button
            type="button"
            data-state={s.state}
            aria-current={s.state === "cur" ? "step" : undefined}
            disabled={s.disabled}
            onClick={() => onPick(s.key)}
            className={`${styles.st} ${i === 0 ? styles.first : ""} ${s.state === "done" ? styles.done : ""} ${s.state === "cur" ? styles.cur : ""}`}
          >
            <span className={styles.n}>{s.state === "done" ? <Icon name="check" /> : i + 1}</span>
            <span><b>{s.name}</b><small>{s.hint}</small></span>
          </button>
        </li>
      ))}
      {note && (
        <li>
          <div className={`${styles.st} ${styles.karya}`}>
            <span className={styles.n}><Icon name="shield" /></span>
            <span><b>{note.title}</b><small>{note.hint}</small></span>
          </div>
        </li>
      )}
    </ol>
  );
}
