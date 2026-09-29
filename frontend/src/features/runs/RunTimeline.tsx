import Link from "next/link";
import styles from "./runs.module.css";
import { layoutBars } from "./timeline";
import type { RunListItem } from "@/lib/api/types";

export function RunTimeline({ items, nowMs }: { items: RunListItem[]; nowMs: number }) {
  const { lanes, startHour, endHour } = layoutBars(items, nowMs);
  if (lanes.length === 0) return null;
  const hours = Array.from({ length: endHour - startHour + 1 }, (_, i) => startHour + i);
  return (
    <section aria-label="Today" className={styles.timeline}>
      <h2>Today</h2>
      <p className={styles.sub}>Each bar is one run, from start to finish.</p>
      {lanes.map((l) => (
        <div key={l.agent} className={styles.lane}>
          <span className={styles.laneName}>{l.agent}</span>
          <div className={styles.track}>
            {l.bars.map((b) => (
              <Link key={b.id} href={`/runs/${b.displayId}`} aria-label={`Run ${b.displayId}`} className={styles.bar} style={{ left: `${b.leftPct}%`, width: `${b.widthPct}%` }} />
            ))}
          </div>
        </div>
      ))}
      <div className={styles.axis} aria-hidden="true">{hours.map((h) => <span key={h} className="mono">{String(h % 24).padStart(2, "0")}:00</span>)}</div>
    </section>
  );
}
