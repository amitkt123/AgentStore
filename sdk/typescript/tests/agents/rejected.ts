import { run, type AgentContext } from "../../src/index.js";

run(async (ctx: AgentContext) => {
  await ctx.gates.request({ action: "post_journal_entries", summary: "Post 1 entry", payload: { rows: 1 },
    affects: ["journal_entries"], raiseOnReject: true });
});
