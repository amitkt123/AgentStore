import styles from "./studio.module.css";
import { KeyValue } from "@/ui/KeyValue";
import { Pill } from "@/ui/Pill";
import { formatMoney } from "@/lib/format";
import type { Manifest } from "@/lib/api/types";
import { RemoveScopeButton } from "./StudioActions";

const PRICING: Record<string, string> = { METERED: "Pay per use", OUTCOME: "Pay per outcome", SUBSCRIPTION: "Subscription" };
const LLM: Record<string, string> = { PLATFORM: "Karya's models", BYOK_PROXY: "Your key, through Karya", BYOK_DIRECT: "Your key, direct" };
// The backend writes enum names in capitals; read them in either case so a lower-case wire value still gets its label.
const label = (table: Record<string, string>, value: string) => table[value.toUpperCase()] ?? value;

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  const id = `declared-${title.toLowerCase().replace(/\W+/g, "-")}`;
  return (
    <section className={styles.card} aria-labelledby={id}>
      <h3 id={id}>{title}</h3>
      {children}
    </section>
  );
}

const NotDeclared = () => <p className={styles.muted}>Not declared</p>;

export function Declared({ manifest: m, editable }: { manifest: Manifest; editable?: { agentId: string; versionId: string } }) {
  const resources = m.resources ?? [];
  return (
    <div className={styles.grid}>
      <Card title="Scopes">
        {m.scopes.length === 0 ? <NotDeclared /> : (
          <ul className={styles.list}>
            {m.scopes.map((s) => (
              <li key={`${s.type}:${s.resource}`} className={styles.row}>
                <span>
                  {s.type === "READ" ? "Read" : "Write"} <span className="mono">{s.resource}</span>
                  {s.description && <span className={styles.sub}>{s.description}</span>}
                </span>
                <span className={styles.actions}>
                  {s.requiresApproval && <Pill tone="warn">Needs approval</Pill>}
                  {editable && <RemoveScopeButton agentId={editable.agentId} versionId={editable.versionId} scope={s} />}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Card title="Allowed hosts">
        {m.networkAllowlist.length === 0 ? <NotDeclared /> : (
          <ul className={styles.list}>
            {m.networkAllowlist.map((h) => <li key={h} className="mono">{h}</li>)}
          </ul>
        )}
      </Card>
      <Card title="Resources">
        {resources.length === 0 ? <NotDeclared /> : (
          <ul className={styles.list}>
            {resources.map((r) => (
              <li key={r.name} className={styles.row}>
                <span className="mono">{r.name}</span>
                <Pill>{r.format.toUpperCase()}</Pill>
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Card title="Approval gates">
        {m.approvalGates.length === 0 ? <NotDeclared /> : (
          <ul className={styles.list}>
            {m.approvalGates.map((g) => (
              <li key={g.trigger} className={styles.row}>
                <span>
                  <span className="mono">{g.trigger}</span>
                  <span className={styles.sub}>{g.description}</span>
                </span>
                <span>{g.timeoutMinutes} min</span>
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Card title="Cost envelope">
        {m.costEnvelope ? (
          <>
            <KeyValue label="Price">{`${formatMoney(m.costEnvelope.unitPrice, m.costEnvelope.currency)} per ${m.costEnvelope.unitLabel.replace(/_/g, " ")}`}</KeyValue>
            <KeyValue label="Most a run can cost">{formatMoney(m.costEnvelope.maxPerRun, m.costEnvelope.currency)}</KeyValue>
            <KeyValue label="Pricing">{label(PRICING, m.costEnvelope.pricingUnit)}</KeyValue>
          </>
        ) : <NotDeclared />}
      </Card>
      <Card title="Data classes">
        {m.dataClasses.length === 0 ? <NotDeclared /> : (
          <div className={styles.pills}>
            {m.dataClasses.map((d) => <Pill key={d}>{d}</Pill>)}
          </div>
        )}
      </Card>
      <Card title="Runtime">
        {m.runtime || m.entrypoint ? (
          <>
            <KeyValue label="Runtime"><span className="mono">{m.runtime ?? "-"}</span></KeyValue>
            <KeyValue label="Entrypoint"><span className="mono">{m.entrypoint ?? "-"}</span></KeyValue>
          </>
        ) : <NotDeclared />}
      </Card>
      <Card title="LLM access">
        {m.llm ? (
          <>
            <KeyValue label="Mode">{label(LLM, m.llm.mode)}</KeyValue>
            <KeyValue label="Models">{m.llm.models.length ? m.llm.models.join(", ") : "-"}</KeyValue>
            <KeyValue label="Most tokens per run">{String(m.llm.maxTokensPerRun)}</KeyValue>
            {m.llm.provider && <KeyValue label="Provider">{m.llm.provider}</KeyValue>}
          </>
        ) : <p className={styles.muted}>None</p>}
      </Card>
    </div>
  );
}
