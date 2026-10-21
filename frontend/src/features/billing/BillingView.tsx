import Link from "next/link";
import styles from "./billing.module.css";
import { EmptyState } from "@/ui/EmptyState";
import { ResponsiveTable } from "@/ui/ResponsiveTable";
import { formatInr } from "@/lib/format";
import type { BillingLine, BillingSummary, Wallet } from "@/lib/api/types";

function shiftMonth(month: string, delta: number): string {
  const [y, m] = month.split("-").map(Number);
  const idx = y * 12 + (m - 1) + delta;
  return `${Math.floor(idx / 12)}-${String((idx % 12) + 1).padStart(2, "0")}`;
}

function figures(wallet: Wallet): [string, number][] {
  if (wallet.mode === "POSTPAID") {
    return [["Credit limit", wallet.creditLimit ?? 0], ["Owed", wallet.owed], ["Available", wallet.available]];
  }
  return [["Balance", wallet.balance], ["On hold", wallet.held], ["Available", wallet.available], ["Spent this month", wallet.spentThisMonth]];
}

export function BillingView({ wallet, summary }: { wallet: Wallet; summary: BillingSummary }) {
  return (
    <div className={styles.wrap}>
      <dl className={styles.figures}>
        {figures(wallet).map(([label, value]) => (
          <div key={label} className={styles.figure}>
            <dt>{label}</dt>
            <dd className="mono">{formatInr(value)}</dd>
          </div>
        ))}
      </dl>
      <nav className={styles.months} aria-label="Month">
        <Link href={`/billing?month=${shiftMonth(summary.month, -1)}`}>Previous month</Link>
        <span className={styles.month}>{summary.month}</span>
        <Link href={`/billing?month=${shiftMonth(summary.month, 1)}`}>Next month</Link>
      </nav>
      {summary.agents.length === 0 ? (
        <EmptyState title="No charges this month." />
      ) : (
        summary.agents.map((agent) => (
          <section key={agent.agentId} className={styles.agent}>
            <h2><span>{agent.agent}</span><span className="mono">{formatInr(agent.total)}</span></h2>
            <ResponsiveTable<BillingLine>
              rows={agent.lines}
              rowKey={(l) => l.runDisplayId}
              empty={null}
              columns={[
                { key: "run", header: "Run", render: (l) => <span className="mono">{l.runDisplayId}</span> },
                { key: "units", header: "Units", render: (l) => (l.unitDisplay ? `${l.units} ${l.unitDisplay}` : String(l.units)) },
                { key: "amount", header: "Amount", render: (l) => <span className="mono">{formatInr(l.amount)}</span> },
                { key: "flag", header: "Note", render: (l) => (l.terminalReason === "COST_CAP_REACHED" ? <span className={styles.flag}>Stopped at limit</span> : null) },
              ]}
            />
          </section>
        ))
      )}
    </div>
  );
}
