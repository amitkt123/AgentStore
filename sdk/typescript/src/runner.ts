import { Api } from "./api.js";
import { AgentContext } from "./context.js";
import { Heartbeat } from "./heartbeat.js";

export interface RunOptions {
  env?: Record<string, string | undefined>;
  api?: Api;
  heartbeatIntervalMs?: number;
}

const REQUIRED = ["AGENTSTORE_API_URL", "AGENTSTORE_RUN_TOKEN"] as const;

function toOutputs(result: unknown): Record<string, unknown> | undefined {
  if (result === undefined || result === null) return undefined;
  if (typeof result === "object" && !Array.isArray(result)) return result as Record<string, unknown>;
  throw new TypeError(`handler must return an object or nothing, got ${Array.isArray(result) ? "array" : typeof result}`);
}

export async function run(
  handler: (ctx: AgentContext) => unknown | Promise<unknown>,
  opts: RunOptions = {},
): Promise<void> {
  const env = opts.env ?? process.env;
  for (const name of REQUIRED) {
    if (!env[name]) throw new Error(`${name} is not set; run() must be started by the AgentStore sandbox`);
  }
  const api = opts.api ?? new Api({ baseUrl: env.AGENTSTORE_API_URL!, token: env.AGENTSTORE_RUN_TOKEN! });
  const ctx = new AgentContext(api, await api.context());
  const heartbeat = new Heartbeat(api, opts.heartbeatIntervalMs);
  heartbeat.start();
  try {
    let outputs: Record<string, unknown> | undefined;
    try {
      outputs = toOutputs(await handler(ctx));
    } catch (err) {
      const e = err instanceof Error ? err : new Error(String(err));
      ctx.log.error(`handler failed: ${e.message}`, { stack: e.stack });
      await ctx.log.flush();
      heartbeat.stop();
      try { await api.fail(`${e.name}: ${e.message}`, false); } catch { /* never mask the handler's own error */ }
      throw err;
    }
    await ctx.log.flush();
    heartbeat.stop();
    await api.complete(outputs);
  } finally {
    heartbeat.stop();
  }
}
