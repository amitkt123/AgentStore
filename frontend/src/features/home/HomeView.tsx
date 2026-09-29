import Link from "next/link";
import styles from "./HomeView.module.css";
import { EmptyState } from "@/ui/EmptyState";
import { Pill } from "@/ui/Pill";
import { formatMoney } from "@/lib/format";
import { isBuyer, type UserRole } from "@/lib/roles";
import type { ConnectionSummary, RunListItem } from "@/lib/api/types";

type Props = { fullName: string; role: UserRole; needsYou: RunListItem[]; connections: ConnectionSummary[] };

export function HomeView({ fullName, role, needsYou, connections }: Props) {
  const first = fullName.split(/\s+/)[0];
  if (!isBuyer(role)) {
    return (
      <section>
        <h1>Welcome, {first}</h1>
        <EmptyState
          title="Your console is coming back"
          body="The developer console is being rebuilt. Until then, use the Karya CLI and your API keys to publish and manage agents."
        />
      </section>
    );
  }
  return (
    <div className={styles.wrap}>
      <h1>Good day, {first}</h1>
      <section aria-labelledby="needs">
        <h2 id="needs">Needs you</h2>
        {needsYou.length === 0 ? (
          <EmptyState
            title="Nothing is waiting for you"
            body="When an agent asks for your approval, it shows up here."
            action={<Link href="/store">Browse the agent store</Link>}
          />
        ) : (
          <ul className={styles.list}>
            {needsYou.map((r) => (
              <li key={r.id}>
                <Link href={`/runs/${r.displayId}`}>{r.displayId} · {r.agent}</Link>
                <Pill tone="warn">Needs you</Pill>
                <span className="mono">{formatMoney(r.held)} held</span>
              </li>
            ))}
          </ul>
        )}
      </section>
      {connections.length > 0 && (
        <section aria-labelledby="agents">
          <h2 id="agents">Your agents</h2>
          <ul className={styles.list}>
            {connections.map((c) => (
              <li key={c.id}>
                <span>{c.agentName}</span>
                <Pill tone={c.status === "ACTIVE" ? "ok" : "neutral"}>{c.status === "ACTIVE" ? "Active" : "Revoked"}</Pill>
                <span className="mono">Up to {formatMoney(c.spendCap)} a run</span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
