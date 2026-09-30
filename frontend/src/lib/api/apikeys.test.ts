import { vi } from "vitest";
vi.mock("next/headers", () => ({ cookies: () => ({ get: () => ({ value: "tok" }) }) }));
vi.mock("next/navigation", () => ({ redirect: vi.fn() }));
import { createApiKey, listApiKeys, revokeApiKey } from "./apikeys";

beforeEach(() => { process.env.BACKEND_URL = "http://be"; });

it("lists keys", async () => {
  const f = vi.fn().mockResolvedValue(new Response("[]", { status: 200 }));
  vi.stubGlobal("fetch", f);
  expect(await listApiKeys()).toEqual([]);
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/developer/api-keys");
});

it("creates a key by name and returns the one-time value", async () => {
  const created = { id: "k1", name: "CI", prefix: "ask_live_ab", key: "ask_live_abcdef", createdAt: "2026-09-30T00:00:00Z" };
  const f = vi.fn().mockResolvedValue(new Response(JSON.stringify(created), { status: 201 }));
  vi.stubGlobal("fetch", f);
  expect(await createApiKey("CI")).toEqual(created);
  expect(f.mock.calls[0][1].method).toBe("POST");
  expect(JSON.parse(f.mock.calls[0][1].body)).toEqual({ name: "CI" });
});

it("revokes by id and treats 204 as success", async () => {
  const f = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
  vi.stubGlobal("fetch", f);
  await expect(revokeApiKey("k1")).resolves.toBeUndefined();
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/developer/api-keys/k1");
  expect(f.mock.calls[0][1].method).toBe("DELETE");
});
