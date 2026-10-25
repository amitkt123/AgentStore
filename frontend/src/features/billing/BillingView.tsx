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
  /** The panels whose call failed, by key, so the page can say so instead of leaving a gap. */
  failed?: (keyof Omit<PaymentPanels, "failed">)[];
}

const PANEL_NAMES: Record<keyof Omit<PaymentPanels, "failed">, string> = {
  options: "Add money", topUps: "your top-ups", refundable: "Get money back", refunds: "your refunds", bills: "your bills",
};

/** The failed panels in page order, joined as English: "A", "A and B", "A, B and C". */
function failedList(failed: NonNullable<PaymentPanels["failed"]>): string {
  const names = (Object.keys(PANEL_NAMES) as (keyof typeof PANEL_NAMES)[]).filter((k) => failed.includes(k)).map((k) => PANEL_NAMES[k]);
  return names.length > 1 ? `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}` : names[0];
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
      {payments?.failed && payments.failed.length > 0 && (
        <p role="status" className={styles.failed}>{`We couldn't load ${failedList(payments.failed)}. Refresh to try again.`}</p>
      )}
      {payments && wallet.mode === "PREPAID" && (
        <>
          {payments.options && <AddMoney options={payments.options} balance={wallet.balance} />}
          {payments.topUps && <TopUpHistory topUps={payments.topUps} />}
        </>
      )}
      {/* Prepaid always shows the panel (its zero state explains why); postpaid only when it holds refundable top-ups. */}
      {payments?.refundable && (wallet.mode === "PREPAID" || payments.refundable.refundable > 0) && (
        <GetMoneyBack refundable={payments.refundable} refunds={payments.refunds ?? []} />
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
