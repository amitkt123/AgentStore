import Link from "next/link";
import styles from "./store.module.css";
import { Pill } from "@/ui/Pill";
import { humanize } from "@/lib/format";
import type { CatalogListing } from "@/lib/api/types";

export function StoreGrid({ listings, addedAgentIds }: { listings: CatalogListing[]; addedAgentIds: Set<string> }) {
  return (
    <ul className={styles.grid}>
      {listings.map((l) => (
        <li key={l.agentId} id={`agent-${l.agentId}`} className={styles.card}>
          <div className={styles.head}>
            <Link href={`/store/${l.agentId}/${l.versionId}`} className={styles.name}>{l.agentName}</Link>
            {addedAgentIds.has(l.agentId) && <Pill tone="ok">Added</Pill>}
          </div>
          <div className={styles.sub}>{humanize(l.category)} · v{l.versionNumber}</div>
        </li>
      ))}
    </ul>
  );
}
