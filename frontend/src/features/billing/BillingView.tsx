import styles from "./billing.module.css";
import { MonthNav } from "@/ui/MonthNav";
import { EmptyState } from "@/ui/EmptyState";
import { ResponsiveTable } from "@/ui/ResponsiveTable";
import { formatInr } from "@/lib/format";
import { AddMoney } from "./AddMoney";
import { Bills } from "./Bills";
import { GetMoneyBack } from "./GetMoneyBack";
import { TopUpHistory } from "./TopUpHistory";
import type {
  Bill, BillingLine, BillingSummary, PaymentOptions, PaymentOrder, Refund, Refundable, Wallet,
} from "@/lib/api/types";

/** Each part is null when its optional call failed; that hides only its own panel. */
export interface PaymentPanels {
  options: PaymentOptions | null;
  topUps: PaymentOrder[] | null;
  refundable: Refundable | null;
  refunds: Refund[] | null;
  bills: Bill[] | null;
}

function figures(wallet: Wallet): [string, number][] {
  if (wallet.mode === "POSTPAID") {
    return [["Credit limit", wallet.creditLimit ?? 0], ["Owed", wallet.owed], ["Available", wallet.available]];
  }
  return [["Balance", wallet.balance], ["On hold", wallet.held], ["Available", wallet.available], ["Spent this month", wallet.spentThisMonth]];
}

export function BillingView({ wallet, summary, payments }: { wallet: Wallet; summary: BillingSummary; payments?: PaymentPanels }) {
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
      {payments && wallet.mode === "PREPAID" && (
        <>
          {payments.options && <AddMoney options={payments.options} balance={wallet.balance} />}
          {payments.topUps && <TopUpHistory topUps={payments.topUps} />}
          {payments.refundable && <GetMoneyBack refundable={payments.refundable} refunds={payments.refunds ?? []} />}
        </>
      )}
      {payments?.bills && wallet.mode === "POSTPAID" && (
        <Bills bills={payments.bills} owed={wallet.owed} methods={payments.options?.methods} />
      )}
      <MonthNav basePath="/billing" month={summary.month} />
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
