import styles from "./earnings.module.css";
import { MonthNav } from "@/ui/MonthNav";
import { ResponsiveTable } from "@/ui/ResponsiveTable";
import { formatInr } from "@/lib/format";
import { statementUrl } from "@/lib/api/money";
import { PayoutAccountPanel } from "./PayoutAccountPanel";
import { WithdrawSection } from "./WithdrawSection";
import { WithdrawalHistory } from "./WithdrawalHistory";
import type { Earnings, EarningsAgent, PayoutsSummary, Withdrawal } from "@/lib/api/types";

function blockedText(p: PayoutsSummary): string | null {
  switch (p.blockedReason) {
    case "NO_VERIFIED_PAYOUT_ACCOUNT": return "Add and verify a payout account first";
    case "INSUFFICIENT_EARNINGS": return `You can withdraw once you have ${formatInr(p.minimum).replace(/\.00$/, "")}`;
    case "DAILY_LIMIT_REACHED": return `You've reached today's ${formatInr(p.dailyLimit).replace(/\.00$/, "")} limit`;
    case "MONEY_FROZEN": return "Withdrawals are paused on your account. Contact support.";
    case "DEVELOPER_NOT_VERIFIED": return "Get verified to withdraw";
    default: return p.blockedReason;
  }
}

export function EarningsView({ earnings, payouts, withdrawals }: { earnings: Earnings; payouts: PayoutsSummary; withdrawals: Withdrawal[] }) {
  const owes = earnings.available < 0;
  const blocked = payouts.canWithdraw ? null : blockedText(payouts);
  const figures: [string, number, boolean][] = [
    ["Buyers paid", earnings.buyersPaid, false],
    [`Karya fee (${earnings.feePercent}%)`, earnings.platformFee, false],
    ["AI usage", earnings.aiUsage, false],
  ];
  if (earnings.tax !== 0) figures.push(["Tax", earnings.tax, false]);
  figures.push(["You earned", earnings.earned, true]);
  return (
    <div className={styles.wrap}>
      <dl className={styles.figures}>
        {figures.map(([label, value, strong]) => (
          <div key={label} className={`${styles.figure} ${strong ? styles.earned : ""}`}>
            <dt>{label}</dt>
            <dd className="mono">{formatInr(value)}</dd>
          </div>
        ))}
      </dl>
      <section className={styles.available}>
        <h2>Available to withdraw</h2>
        {owes ? (
          <p className={styles.owe}>{`You owe ${formatInr(-earnings.available)}; it comes out of future earnings`}</p>
        ) : (
          <p className={`${styles.availableValue} mono`}>{formatInr(earnings.available)}</p>
        )}
        <WithdrawSection payouts={payouts} blocked={blocked} owes={owes} />
      </section>
      <PayoutAccountPanel account={payouts.account} attemptsLeft={payouts.validationAttemptsLeft} verifiedName={payouts.verifiedName} />
      <WithdrawalHistory withdrawals={withdrawals} simulate={payouts.provider === "fake"} />
      <div className={styles.bar}>
        <MonthNav basePath="/developer/earnings" month={earnings.month} />
        <a className={styles.download} href={statementUrl(earnings.month)}>Download statement (CSV)</a>
      </div>
      <ResponsiveTable<EarningsAgent>
        rows={earnings.byAgent}
        rowKey={(a) => a.agentId}
        empty={null}
        columns={[
          { key: "agent", header: "Agent", render: (a) => a.agent },
          { key: "runs", header: "Runs", render: (a) => String(a.runs) },
          { key: "paid", header: "Buyers paid", render: (a) => <span className="mono">{formatInr(a.buyersPaid)}</span> },
          { key: "fee", header: "Fee", render: (a) => <span className="mono">{formatInr(a.fee)}</span> },
          { key: "ai", header: "AI usage", render: (a) => <span className="mono">{formatInr(a.aiUsage)}</span> },
          { key: "earned", header: "Earned", render: (a) => <span className="mono">{formatInr(a.earned)}</span> },
          { key: "perRun", header: "Per run", render: (a) => <span className="mono">{formatInr(a.perRun)}</span> },
        ]}
      />
    </div>
  );
}
