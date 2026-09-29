import { vi } from "vitest";
import flow from "./fixtures/flow.json";
import datasets from "./fixtures/datasets.json";

const calls: { url: string; init?: RequestInit }[] = [];
vi.mock("next/headers", () => ({ cookies: () => ({ get: () => ({ value: "tok" }) }) }));
vi.mock("next/navigation", () => ({ redirect: vi.fn() }));

import { runQueryString, getRunSnapshot, decideGate } from "./runs";
import { listDatasets } from "./datasets";

function respond(map: Record<string, unknown>) {
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, init });
    const key = Object.keys(map).find((k) => url.includes(k))!;
    return new Response(JSON.stringify(map[key]), { status: 200 });
  }));
}

beforeEach(() => { calls.length = 0; process.env.BACKEND_URL = "http://be"; });

it("builds a run query string and skips empty values", () => {
  expect(runQueryString({ group: "needs_you", q: "", page: 0 })).toBe("?group=needs_you&page=0");
  expect(runQueryString({})).toBe("");
});

it("assembles a run snapshot from detail, flow and gates", async () => {
  respond({ "/flow": flow, "/gates": [], "/runs/R-1": { id: "x", displayId: "R-1", status: "RUNNING", terminalReason: null, costCap: 500, totalCharged: 0, steps: [] } });
  const snap = await getRunSnapshot("R-1");
  expect(snap.detail.displayId).toBe("R-1");
  expect(snap.flow.blocks.length).toBeGreaterThan(0);
  expect(snap.gates).toEqual([]);
});

it("posts a gate decision with the note", async () => {
  respond({ "/decision": { id: "x", displayId: "R-1", status: "RUNNING", terminalReason: null } });
  await decideGate("R-1", "g1", "APPROVE", "looks right");
  expect(calls[0].url).toContain("/api/v1/runs/R-1/gates/g1/decision");
  expect(JSON.parse(String(calls[0].init?.body))).toEqual({ decision: "APPROVE", note: "looks right" });
});

it("requests datasets with usage", async () => {
  respond({ "/datasets": datasets });
  const list = await listDatasets();
  expect(calls[0].url).toContain("/api/v1/datasets?include=usage");
  expect(list[0].displayName).toBeTruthy();
});
