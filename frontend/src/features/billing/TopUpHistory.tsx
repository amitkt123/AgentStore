import styles from "./billing.module.css";
import { EmptyState } from "@/ui/EmptyState";
import { Pill, type Tone } from "@/ui/Pill";
import { ResponsiveTable } from "@/ui/ResponsiveTable";
import { METHOD_LABEL } from "./labels";
import { formatDate, formatInr } from "@/lib/format";
import type { PaymentOrder } from "@/lib/api/types";

const STATUS: Record<PaymentOrder["status"], [string, Tone]> = {
  CREATED: ["Pending", "info"], PAID: ["Paid", "ok"], FAILED: ["Failed", "bad"], EXPIRED: ["Expired", "neutral"],
};

export function TopUpHistory({ topUps }: { topUps: PaymentOrder[] }) {
  return (
    <section className={styles.section}>
      <h2>Top-up history</h2>
      <ResponsiveTable<PaymentOrder>
        rows={topUps}
        rowKey={(o) => o.id}
        empty={<EmptyState title="No top-ups yet." />}
        columns={[
          { key: "date", header: "Date", render: (o) => formatDate(o.createdAt) },
          { key: "method", header: "Method", render: (o) => METHOD_LABEL[o.method] },
          { key: "amount", header: "Amount", render: (o) => <span className="mono">{formatInr(o.amount)}</span> },
          {
            key: "status", header: "Status",
            render: (o) => (
              <>
                <Pill tone={STATUS[o.status][1]}>{STATUS[o.status][0]}</Pill>
                {o.refundedAmount > 0 && <span className={styles.refunded}>Refunded {formatInr(o.refundedAmount)}</span>}
              </>
            ),
          },
        ]}
      />
    </section>
  );
}
