import { describe, expect, it } from "vitest";
import { AgentContext } from "../src/context.js";
import { GateRejectedError, RunTerminatedError } from "../src/errors.js";
import { run } from "../src/runner.js";
import { fakeApi } from "./helpers.js";

const ENV = { AGENTSTORE_API_URL: "http://x", AGENTSTORE_RUN_TOKEN: "t", AGENTSTORE_RUN_ID: "r1", AGENTSTORE_MODE: "SANDBOX" };
const CONTEXT = {
  runId: "r1", mode: "SANDBOX", input: { month: "2026-01" },
  grantedScopes: [{ type: "READ", resource: "bank_statement", requiresApproval: false }],
  limits: { deadlineAt: "2026-01-01T00:10:00Z", costCapRemaining: 500, currency: "USD", llm: { mode: "PLATFORM", models: ["m1"], tokensRemaining: 1000 } },
};
const api = (extra: Record<string, unknown> = {}) => fakeApi({ context: { ...CONTEXT }, ...extra });
const opts = (f: ReturnType<typeof fakeApi>) => ({ env: ENV, api: f.api, heartbeatIntervalMs: 60_000 });

describe("AgentContext", () => {
  it("exposes run, input and limits", () => {
    const ctx = new AgentContext(fakeApi().api, CONTEXT as any);
    expect([ctx.runId, ctx.mode, ctx.input]).toEqual(["r1", "SANDBOX", { month: "2026-01" }]);
    expect(ctx.limits).toMatchObject({ deadlineAt: "2026-01-01T00:10:00Z", costCapRemaining: 500 });
    expect(ctx.limits.llm).toEqual({ mode: "PLATFORM", models: ["m1"], tokensRemaining: 1000 });
    expect(ctx.grantedScopes[0].resource).toBe("bank_statement");
  });

  it("tolerates missing optional sections", () => {
    const ctx = new AgentContext(fakeApi().api, { runId: "r", mode: "TRIAL" } as any);
    expect(ctx.input).toEqual({});
    expect(ctx.limits.llm).toBeUndefined();
    expect(ctx.grantedScopes).toEqual([]);
  });
});

describe("run", () => {
  it("flushes logs then completes with the outputs", async () => {
    const f = api({ complete: { status: "COMPLETED" } });
    await run(async (ctx) => { ctx.log.info("working"); return { matched: 3 }; }, opts(f));
    expect(f.names()).toEqual(["context", "logs", "complete"]);
    expect(f.calls.at(-1)![1]).toEqual([{ matched: 3 }]);
  });

  it("completes without outputs when the handler returns nothing", async () => {
    const f = api({ complete: { status: "COMPLETED" } });
    await run(() => undefined, opts(f));
    expect(f.calls.at(-1)).toEqual(["complete", [undefined]]);
  });

  it("logs the stack, fails and rethrows on an exception", async () => {
    const f = api({ fail: { status: "FAILED" } });
    await expect(run(() => { throw new Error("boom"); }, opts(f))).rejects.toThrow("boom");
    expect(f.names()).toEqual(["context", "logs", "fail"]);
    const logged = f.calls[1][1][0] as any[];
    expect(logged.at(-1).level).toBe("error");
    expect(logged.at(-1).fields.stack).toContain("Error: boom");
    expect(f.calls.at(-1)![1]).toEqual(["Error: boom", false]);
  });

  it("fails the run with a clear message when the handler returns a non-object", async () => {
    const f = api({ fail: { status: "FAILED" } });
    await expect(run(() => [1, 2, 3] as any, opts(f))).rejects.toThrow(/object/);
    expect(f.names()).not.toContain("complete");
    expect(String(f.calls.at(-1)![1][0])).toMatch(/object/);
  });

  it("a 409 from fail does not mask the handler's error", async () => {
    const f = api({ fail: [new RunTerminatedError(409, "RUN_TERMINATED", "already stopped")] });
    await expect(run(() => { throw new Error("original"); }, opts(f))).rejects.toThrow("original");
  });

  it("propagates a GateRejectedError and fails the run", async () => {
    const f = api({ fail: { status: "FAILED" } });
    await expect(run(() => { throw new GateRejectedError("g", "REJECTED", "no"); }, opts(f))).rejects.toBeInstanceOf(GateRejectedError);
    expect(f.names()).toContain("fail");
  });

  it.each(["AGENTSTORE_API_URL", "AGENTSTORE_RUN_TOKEN"])("fails fast naming a missing %s", async (missing) => {
    const env: Record<string, string> = { ...ENV };
    delete env[missing];
    const f = fakeApi();
    await expect(run(() => undefined, { env, api: f.api })).rejects.toThrow(missing);
    expect(f.calls).toEqual([]);
  });
});
