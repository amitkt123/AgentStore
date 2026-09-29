import type { Api } from "../src/api.js";

export const instantRetry = { sleep: async () => {}, jitter: () => 0 };

export function mockFetch(handler: (req: Request, n: number) => Response | Promise<Response>) {
  let n = 0;
  const impl = async (input: RequestInfo | URL, init?: RequestInit) => handler(new Request(input as any, init), ++n);
  return { fetchImpl: impl as typeof fetch, calls: () => n };
}

export function problem(status: number, code: string, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify({ status, code, detail: "d" }), {
    status, headers: { "content-type": "application/problem+json", ...headers },
  });
}

export const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

/** Scripted stand-in for Api: values are returned as-is, arrays are consumed (Errors thrown), functions called. */
export function fakeApi(scripted: Record<string, unknown> = {}) {
  const calls: Array<[string, unknown[]]> = [];
  const api = new Proxy({}, {
    get: (_t, name: string) => async (...args: unknown[]) => {
      calls.push([name, args]);
      const r = scripted[name];
      if (typeof r === "function") return r(...args);
      if (Array.isArray(r)) {
        if (r.length === 0) return undefined;
        const item = r.shift();
        if (item instanceof Error) throw item;
        return item;
      }
      return r;
    },
  }) as unknown as Api;
  return { api, calls, names: () => calls.map((c) => c[0]) };
}
