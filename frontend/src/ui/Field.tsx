import { useId } from "react";
import styles from "./Field.module.css";

type Props = React.InputHTMLAttributes<HTMLInputElement> & { label: string; error?: string; hint?: string };

export function Field({ label, error, hint, id, ...rest }: Props) {
  const auto = useId();
  const inputId = id ?? auto;
  const noteId = `${inputId}-note`;
  return (
    <div className={styles.field}>
      <label htmlFor={inputId}>{label}</label>
      <input id={inputId} aria-invalid={!!error} aria-describedby={error || hint ? noteId : undefined} {...rest} />
      {(error || hint) && (
        <span id={noteId} className={error ? styles.err : styles.hint}>
          {error ?? hint}
        </span>
      )}
    </div>
  );
}
