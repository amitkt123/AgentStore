import { describe, expect, it } from "vitest";
import { Api } from "../src/api.js";
import {
  AgentStoreApiError, BudgetExceededError, RateLimitedError, RunTerminatedError, ScopeNotGrantedError,
} from "../src/errors.js";
import { createFetch } from "../src/http.js";
import { instantRetry, json, mockFetch, problem } from "./helpers.js";

const apiOn = (handler: Parameters<typeof mockFetch>[0], retry = {}) => {
  const m = mockFetch(handler);
  return { api: new Api({ baseUrl: "http://runtime.test", token: "tok-123", retry: { ...instantRetry, fetchImpl: m.fetchImpl, ...retry } }), m };
};

describe("Api over the retrying fetch", () => {
  it("sends the bearer token and decodes json", async () => {
    let seen = "";
    const { api } = apiOn((req) => {
      seen = `${req.headers.get("authorization")} ${new URL(req.url).pathname}`;
      return json({ runId: "r1", mode: "SANDBOX" });
    });
    expect((await api.context()).runId).toBe("r1");
    expect(seen).toBe("Bearer tok-123 /runtime/v1/context");
  });

  it("retries 429 then succeeds", async () => {
    const { api, m } = apiOn((_r, n) => (n < 3 ? problem(429, "RATE_LIMITED") : new Response(null, { status: 204 })));
    await api.heartbeat();
    expect(m.calls()).toBe(3);
  });

  it("retries 5xx up to the max then throws a generic api error", async () => {
    const { api, m } = apiOn(() => new Response("down", { status: 503 }));
    await expect(api.heartbeat()).rejects.toMatchObject({ status: 503, code: "" });
    expect(m.calls()).toBe(4);
  });

  it("does not retry 501", async () => {
    const { api, m } = apiOn(() => problem(501, "NOT_IMPLEMENTED"));
    await expect(api.heartbeat()).rejects.toBeInstanceOf(AgentStoreApiError);
    expect(m.calls()).toBe(1);
  });

  it("honours Retry-After when sleeping and reports it on exhaustion", async () => {
    const slept: number[] = [];
    const { api } = apiOn(() => problem(429, "RATE_LIMITED", { "retry-after": "2" }), { sleep: async (ms: number) => { slept.push(ms); } });
    await expect(api.heartbeat()).rejects.toMatchObject({ retryAfter: 2 });
    expect(slept).toEqual([2000, 2000, 2000]);
  });

  it.each([
    [403, "SCOPE_NOT_GRANTED", ScopeNotGrantedError],
    [409, "RUN_TERMINATED", RunTerminatedError],
    [402, "BUDGET_EXCEEDED", BudgetExceededError],
    [429, "RATE_LIMITED", RateLimitedError],
  ])("maps %i %s to its typed error", async (status, code, cls) => {
    const { api } = apiOn(() => problem(status, code));
    const err = await api.records("payroll").catch((e) => e);
    expect(err).toBeInstanceOf(cls);
    expect(err).toBeInstanceOf(AgentStoreApiError);
    expect(err.code).toBe(code);
  });

  it("sends camelCase bodies for write/usage/fail and no body for a bare complete", async () => {
    const bodies: Record<string, string> = {};
    const { api } = apiOn(async (req) => {
      bodies[new URL(req.url).pathname] = await req.text();
      return json({ accepted: 1, staged: false, status: "COMPLETED" });
    });
    await api.write("journal_entries", [{ account: "1000" }]);
    await api.usage(2, "reconciled_transaction", "ref-1");
    await api.complete();
    await api.fail("boom", false);
    expect(JSON.parse(bodies["/runtime/v1/resources/journal_entries/records"])).toEqual({ records: [{ account: "1000" }] });
    expect(JSON.parse(bodies["/runtime/v1/usage"])).toEqual({ quantity: 2, unit: "reconciled_transaction", outcomeRef: "ref-1" });
    expect(bodies["/runtime/v1/complete"]).toBe("");
    expect(JSON.parse(bodies["/runtime/v1/fail"])).toEqual({ error: "boom", retriable: false });
  });

  it("returns file content as bytes and passes gate wait", async () => {
    let url = "";
    const { api } = apiOn((req) => {
      url = req.url;
      return new Response(new Uint8Array([97, 44, 98]), { headers: { "content-type": "application/octet-stream" } });
    });
    expect(Array.from(await api.fileContent("bank_statement"))).toEqual([97, 44, 98]);
    const { api: gateApi } = apiOn((req) => { url = req.url; return json({ gateId: "g", status: "APPROVED" }); });
    await gateApi.getGate("3f2b8c1e-0000-4000-8000-000000000001", 25);
    expect(url).toContain("wait=25");
  });
});

describe("createFetch", () => {
  it("re-sends the request body on each retry", async () => {
    const bodies: string[] = [];
    const m = mockFetch(async (req, n) => { bodies.push(await req.text()); return n < 2 ? new Response("x", { status: 500 }) : json({}); });
    const f = createFetch({ ...instantRetry, fetchImpl: m.fetchImpl });
    await f(new Request("http://x/y", { method: "POST", body: JSON.stringify({ a: 1 }) }));
    expect(bodies).toEqual(['{"a":1}', '{"a":1}']);
  });
});
