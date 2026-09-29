import { run, type AgentContext } from "../../src/index.js";

async function handler(ctx: AgentContext) {
  const statement: Record<string, any>[] = [];
  for await (const r of ctx.data.records("bank_statement")) statement.push(r);
  ctx.log.info("read bank statement", { rows: statement.length });
  const entries = statement.map((r) => ({ account: "1000", amount: r.amount }));
  const decision = await ctx.gates.request({
    action: "post_journal_entries", summary: `Post ${entries.length} entries`,
    payload: { rows: entries.length }, affects: ["journal_entries"], raiseOnReject: true,
  });
  const result = await ctx.data.write("journal_entries", entries);
  await ctx.usage.report(entries.length, "reconciled_transaction");
  return { posted: result.accepted, staged: result.staged, gate: decision.status };
}

run(handler);
