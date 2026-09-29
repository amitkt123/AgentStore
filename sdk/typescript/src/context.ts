import type { Api } from "./api.js";
import type { components } from "./generated/schema.js";
import { Data } from "./data.js";
import { Gates } from "./gates.js";
import { Llm } from "./llm.js";
import { Logger } from "./logs.js";
import { Usage } from "./usage.js";

export interface Limits {
  deadlineAt?: string;
  costCapRemaining?: number;
  currency?: string;
  llm?: { mode?: string; models: string[]; tokensRemaining?: number };
}

export interface GrantedScope { type: string; resource: string; requiresApproval: boolean }

export class AgentContext {
  readonly runId: string;
  readonly mode: string;
  readonly input: Record<string, unknown>;
  readonly limits: Limits;
  readonly grantedScopes: GrantedScope[];
  readonly data: Data;
  readonly llm: Llm;
  readonly gates: Gates;
  readonly usage: Usage;
  readonly log: Logger;

  constructor(api: Api, context: components["schemas"]["ContextResponse"],
              opts: { logFlushSize?: number; logFlushIntervalMs?: number } = {}) {
    this.runId = context.runId!;
    this.mode = context.mode!;
    this.input = (context.input as Record<string, unknown> | undefined) ?? {};
    const l = context.limits;
    this.limits = {
      deadlineAt: l?.deadlineAt, costCapRemaining: l?.costCapRemaining, currency: l?.currency,
      llm: l?.llm ? { mode: l.llm.mode, models: l.llm.models ?? [], tokensRemaining: l.llm.tokensRemaining } : undefined,
    };
    this.grantedScopes = (context.grantedScopes ?? []).map((s) => ({
      type: s.type!, resource: s.resource!, requiresApproval: s.requiresApproval ?? false,
    }));
    this.data = new Data(api);
    this.llm = new Llm(api);
    this.gates = new Gates(api);
    this.usage = new Usage(api);
    this.log = new Logger(api, { flushSize: opts.logFlushSize, flushIntervalMs: opts.logFlushIntervalMs });
  }
}
