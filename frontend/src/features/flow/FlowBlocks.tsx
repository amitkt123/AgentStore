import styles from "./FlowBlocks.module.css";
import type { BlockView } from "./blocks";
import type { BlockState } from "@/lib/api/types";

const STATE_TEXT: Record<BlockState, string> = {
  DONE: "Done", WAITING: "Waiting for you", FAILED: "Failed", SKIPPED: "Skipped", IDLE: "Not started",
};

export function FlowBlocks({ blocks, selectedId, onSelect, showState = true }: { blocks: BlockView[]; selectedId?: string; onSelect?: (id: string) => void; showState?: boolean }) {
  return (
    <ol className={styles.flow}>
      {blocks.map((b) => (
        <li key={b.id}>
          <button
            type="button"
            className={`${styles.block} ${styles[b.state]} ${selectedId === b.id ? styles.sel : ""}`}
            aria-pressed={selectedId === b.id}
            onClick={() => onSelect?.(b.id)}
          >
            <span className={styles.kind}>{b.kind}</span>
            <strong>{b.title}</strong>
            <span className={styles.body}>{b.body}</span>
            {b.metric && <span className={`${styles.metric} mono`}>{b.metric}</span>}
            {b.undeclared && <span className={styles.flag}>Not declared by the agent</span>}
            {showState && <span className={styles.state}>{STATE_TEXT[b.state]}</span>}
          </button>
        </li>
      ))}
    </ol>
  );
}
