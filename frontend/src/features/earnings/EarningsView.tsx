import styles from "./earnings.module.css";
import { MonthNav } from "@/ui/MonthNav";
import { ResponsiveTable } from "@/ui/ResponsiveTable";
import { formatInr } from "@/lib/format";
import { statementUrl } from "@/lib/api/money";
import type { Earnings, EarningsAgent } from "@/lib/api/types";

export function EarningsView({ earnings }: { earnings: Earnings }) {
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
        {earnings.available < 0 ? (
          <p className={styles.owe}>{`You owe ${formatInr(-earnings.available)}; it comes out of future earnings`}</p>
        ) : (
          <p className={`${styles.availableValue} mono`}>{formatInr(earnings.available)}</p>
        )}
      </section>
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
