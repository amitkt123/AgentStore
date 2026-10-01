import { useId } from "react";
import styles from "./TextArea.module.css";

type Props = React.TextareaHTMLAttributes<HTMLTextAreaElement> & { label: string; error?: string; hint?: string };

export function TextArea({ label, error, hint, id, ...rest }: Props) {
  const auto = useId();
  const areaId = id ?? auto;
  const noteId = `${areaId}-note`;
  return (
    <div className={styles.field}>
      <label htmlFor={areaId}>{label}</label>
      <textarea id={areaId} aria-invalid={!!error} aria-describedby={error || hint ? noteId : undefined} {...rest} />
      {(error || hint) && (
        <span id={noteId} className={error ? styles.err : styles.hint}>
          {error ?? hint}
        </span>
      )}
    </div>
  );
}
