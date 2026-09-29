import { run, ScopeNotGrantedError, type AgentContext } from "../../src/index.js";

async function handler(ctx: AgentContext) {
  try {
    for await (const _ of ctx.data.records("payroll")) { /* unreachable */ }
  } catch (e) {
    if (e instanceof ScopeNotGrantedError) ctx.log.error("payroll read denied as expected");
    throw e;
  }
}

run(handler);
