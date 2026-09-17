import { backend } from "@/lib/backend";
import { getSession } from "@/lib/session";
import { formatInr } from "@/lib/format";
import { groupStatementByAgent } from "@/lib/statement";
import type { StatementLineResponse, WalletResponse } from "@/lib/types";

export default async function BillingPage() {
  const session = await getSession();
  if (!session) return null;

  const [wallet, lines] = await Promise.all([
    backend.get<WalletResponse>(`/api/v1/orgs/${session.orgId}/wallet`),
    backend.get<StatementLineResponse[]>(`/api/v1/orgs/${session.orgId}/statement`),
  ]);
  const groups = groupStatementByAgent(lines);

  return (
    <div className="grid grid-cols-[1fr_360px] gap-8 items-start">
      <div>
        <h1 className="font-serif text-2xl mb-6">Billing</h1>
        <div className="border border-line rounded-lg p-5 mb-6">
          <div className="font-serif text-3xl">{formatInr(wallet.balance)}</div>
          <div className="text-sm text-ink2 mt-1">
            Available{wallet.cap ? ` · monthly cap ${formatInr(wallet.cap)}` : ""}
          </div>
        </div>
        <h2 className="font-semibold text-sm mb-3">This month, by agent</h2>
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-muted text-xs">
              <th className="pb-2">Agent</th>
              <th className="pb-2 text-right">Charged</th>
            </tr>
          </thead>
          <tbody>
            {groups.map((g) => (
              <tr key={g.agentName} className="border-t border-line">
                <td className="py-2">{g.agentName}</td>
                <td className="py-2 text-right">{formatInr(g.total)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <aside className="bg-doc border border-line rounded p-6">
        <h3 className="font-serif text-lg mb-4">Statement</h3>
        {lines.map((line, i) => (
          <div key={i} className="flex justify-between text-sm py-2 border-b border-dotted border-line">
            <span>{line.agentName} · {line.type.toLowerCase()}</span>
            <span>{formatInr(line.amount)}</span>
          </div>
        ))}
      </aside>
    </div>
  );
}
