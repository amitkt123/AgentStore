import { vi } from "vitest";
vi.mock("next/headers", () => ({ cookies: () => ({ get: () => ({ value: "tok" }) }) }));
vi.mock("next/navigation", () => ({ redirect: vi.fn() }));
import { createAgent, createVersion, getReadiness, listMyAgents, patchManifest, submitVersion } from "./agents";

beforeEach(() => { process.env.BACKEND_URL = "http://be"; });
const stub = (body: unknown, status = 200) => {
  const f = vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status }));
  vi.stubGlobal("fetch", f);
  return f;
};

it("lists my agents", async () => {
  const f = stub([]);
  expect(await listMyAgents()).toEqual([]);
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/agents/mine");
});

it("creates an agent", async () => {
  const f = stub({ id: "a1", name: "Bot", category: "Finance", description: null });
  await createAgent({ name: "Bot", category: "Finance" });
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/agents");
  expect(f.mock.calls[0][1].method).toBe("POST");
  expect(JSON.parse(f.mock.calls[0][1].body)).toEqual({ name: "Bot", category: "Finance" });
});

it("creates a version under its agent", async () => {
  const f = stub({ id: "v1", versionNumber: "1.0.0", status: "DRAFT" });
  await createVersion("a1", { versionNumber: "1.0.0", manifestJson: "{}", changelog: "first" });
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/agents/a1/versions");
  expect(JSON.parse(f.mock.calls[0][1].body)).toEqual({ versionNumber: "1.0.0", manifestJson: "{}", changelog: "first" });
});

it("reads readiness", async () => {
  const f = stub({ ready: true, blockers: [] });
  expect(await getReadiness("a1", "v1")).toEqual({ ready: true, blockers: [] });
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/agents/a1/versions/v1/readiness");
});

it("posts a manifest patch", async () => {
  const f = stub({ manifestRevision: 2, diff: { addedScopes: [], removedScopes: [], addedHosts: ["h"] } });
  await patchManifest("a1", "v1", { op: "add_allowlist_host", host: "h" });
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/agents/a1/versions/v1/manifest/patches");
  expect(f.mock.calls[0][1].method).toBe("POST");
  expect(JSON.parse(f.mock.calls[0][1].body)).toEqual({ op: "add_allowlist_host", host: "h" });
});

it("submits a version with no body", async () => {
  const f = stub({ id: "v1", versionNumber: "1.0.0", status: "LIVE" });
  expect((await submitVersion("a1", "v1")).status).toBe("LIVE");
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/agents/a1/versions/v1/submit");
  expect(f.mock.calls[0][1].method).toBe("POST");
  expect(f.mock.calls[0][1].body).toBeUndefined();
});
