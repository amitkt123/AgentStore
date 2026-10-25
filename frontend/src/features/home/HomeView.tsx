import Link from "next/link";
import styles from "./HomeView.module.css";
import { EmptyState } from "@/ui/EmptyState";
import { Pill } from "@/ui/Pill";
import { formatMoney } from "@/lib/format";
import { RunNow } from "@/features/runs/RunNow";
import { isBuyer, type UserRole } from "@/lib/roles";
import type { ConnectionSummary, RunListItem } from "@/lib/api/types";

type Props = { fullName: string; role: UserRole; needsYou: RunListItem[]; connections: ConnectionSummary[]; added?: string };

export function HomeView({ fullName, role, needsYou, connections, added }: Props) {
  const first = fullName.split(/\s+/)[0];
  if (!isBuyer(role)) {
    const admin = role === "ADMIN";
    return (
      <section>
        <h1>Welcome, {first}</h1>
        <EmptyState
          title={admin ? "Platform money lives under Money" : "Your agents live under Agents"}
          body={admin ? "Fees, credit limits, wallet adjustments and ledger checks." : "Create agents, push versions and submit them for review."}
          action={admin ? <Link href="/admin/money">Go to Money</Link> : <Link href="/developer/agents">Go to Agents</Link>}
        />
      </section>
    );
  }
  const active = connections.filter((c) => c.status === "ACTIVE");
  const justAdded = added ? active.find((c) => c.agentId === added) : undefined;
  return (
    <div className={styles.wrap}>
      <h1>Good day, {first}</h1>
      {justAdded && <p role="status" className={styles.added}>{`${justAdded.agentName} is added. Run it when you're ready.`}</p>}
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
      {active.length > 0 && (
        <section aria-labelledby="agents">
          <h2 id="agents">Your agents</h2>
          <ul className={styles.list}>
            {active.map((c) => (
              <li key={c.id}>
                <Link href={`/store#agent-${c.agentId}`}>{c.agentName}</Link>
                <span className={styles.cap}>Up to {formatMoney(c.spendCap)} a run</span>
                <RunNow connectionId={c.id} />
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
