import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { push } from "../src/commands/push.js";
import { AGENT_ID, makeContext, type Recorded, type Reply, startServer, writeFiles } from "./helpers.js";

const BUNDLE_ID = "bbbbbbbb-2222-4333-8444-555555555555";
const BUILD_ID = "cccccccc-2222-4333-8444-555555555555";
const SECRET = "X-Amz-Signature=topsecret";
const BASE = `/api/v1/agents/${AGENT_ID}`;

const manifest = JSON.stringify({
  name: "R", version: "1.0.0", runtime: "python-3.12", entrypoint: "agent/main.py", scopes: [],
  network: { allowlist: [] },
  costEnvelope: { currency: "INR", maxPerRun: 1, pricingUnit: "METERED", unitPrice: 1, unitLabel: "u" },
  approvalGates: [], dataClasses: [],
});

interface Options {
  /** What each successive GET /builds/{id} returns (the last one repeats). */
  builds?: Array<{ status: string; logUrl?: boolean; failureCode?: string; failureReason?: string }>;
  /** What each successive GET /log returns (the last one repeats). */
  logs?: string[];
  putStatus?: number;
  finalize?: Reply;
  apiUrlSuffix?: string;
  files?: Record<string, string>;
  sync?: Reply;
}

async function harness(options: Options = {}) {
  let base = "";
  let buildCall = 0;
  let logCall = 0;
  const builds = options.builds ?? [{ status: "SUCCEEDED", logUrl: true }];
  const logs = options.logs ?? [""];
  const server = await startServer([
    { method: "POST", path: `${BASE}/bundles/uploads`, reply: () => ({ status: 201, json: { bundleId: BUNDLE_ID, uploadUrl: `${base}/upload/${BUNDLE_ID}?${SECRET}`, expiresAt: "2030-01-01T00:00:00Z" } }) },
    { method: "PUT", path: /^\/upload\//, reply: () => ({ status: options.putStatus ?? 200 }) },
    { method: "POST", path: `${BASE}/bundles/${BUNDLE_ID}/finalize`, reply: options.finalize ?? { json: { bundleId: BUNDLE_ID, versionId: "v1", versionNumber: "1.0.0", buildId: BUILD_ID, buildStatus: "QUEUED" } } },
    { method: "POST", path: `${BASE}/sandbox-profiles/sync`, reply: options.sync ?? { json: [] } },
    { method: "GET", path: `${BASE}/builds/${BUILD_ID}`, reply: (): Reply => {
      const step = builds[Math.min(buildCall++, builds.length - 1)];
      return { json: { id: BUILD_ID, status: step.status, failureCode: step.failureCode ?? null, failureReason: step.failureReason ?? null, logUrl: step.logUrl ? `${base}/log?${SECRET}` : null } };
    } },
    { method: "GET", path: /^\/log/, reply: (): Reply => ({ text: logs[Math.min(logCall++, logs.length - 1)] }) },
  ]);
  base = server.url;
  const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: server.url + (options.apiUrlSuffix ?? "") } });
  writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n`, "manifest.json": manifest, "agent/main.py": "print('hi')\n", ...options.files });
  return { server, ctx };
}

const paths = (requests: Recorded[]) => requests.map((r) => `${r.method} ${r.path.split("?")[0]}`);

describe("push", () => {
  it("validates, uploads, finalizes and stops without --wait", async () => {
    const { server, ctx } = await harness();

    expect(await push(ctx, { wait: false, timeoutSeconds: 900 })).toBe(0);

    expect(paths(server.requests)).toEqual([
      `POST ${BASE}/bundles/uploads`,
      `PUT /upload/${BUNDLE_ID}`,
      `POST ${BASE}/bundles/${BUNDLE_ID}/finalize`,
    ]);
    const declared = JSON.parse(server.requests[0].body.toString());
    const uploaded = server.requests[1].body;
    expect(declared.sizeBytes).toBe(uploaded.length);
    expect(declared.sha256).toBe(createHash("sha256").update(uploaded).digest("hex"));
    expect(JSON.parse(server.requests[2].body.toString())).toEqual({ sha256: declared.sha256 });
    expect(ctx.outLines.join("\n")).toContain("Pushed version 1.0.0");
    expect(ctx.outLines.join("\n")).toContain(BUILD_ID);
  });

  it("sends the key to the API but not to the upload URL", async () => {
    const { server, ctx } = await harness();
    await push(ctx, { wait: false, timeoutSeconds: 900 });
    expect(server.requests[0].headers.authorization).toBe("Bearer ask_live_test");
    expect(server.requests[1].headers.authorization).toBeUndefined();
  });

  it("with --wait, tails the log and exits 0 when the build succeeds", async () => {
    const { server, ctx } = await harness({
      builds: [{ status: "QUEUED" }, { status: "BUILDING", logUrl: true }, { status: "BUILDING", logUrl: true }, { status: "SUCCEEDED", logUrl: true }],
      // one entry per poll that has a log URL: the second and third BUILDING polls, then the terminal one
      logs: ["step 1\n", "step 1\nstep 2\n", "step 1\nstep 2\ndone\n"],
    });

    expect(await push(ctx, { wait: true, timeoutSeconds: 900 })).toBe(0);

    expect(ctx.outLines.filter((l) => l === "step 1")).toHaveLength(1);
    expect(ctx.outLines.filter((l) => l === "step 2")).toHaveLength(1);
    expect(ctx.outLines).toContain("done");
    expect(ctx.outLines.join("\n")).toContain("Build succeeded");
    expect(ctx.sleeps).toEqual([2000, 2000, 2000]);
    expect(server.requests.filter((r) => r.path.startsWith(`${BASE}/builds/`))).toHaveLength(4);
  });

  it("with --wait, exits 1 and prints the failure code and reason when the build fails", async () => {
    const { ctx } = await harness({
      builds: [{ status: "FAILED", logUrl: true, failureCode: "SECRET_FOUND", failureReason: "config.py:3" }],
      logs: ["scanning\n"],
    });

    expect(await push(ctx, { wait: true, timeoutSeconds: 900 })).toBe(1);

    expect(ctx.errLines.join("\n")).toContain("SECRET_FOUND");
    expect(ctx.errLines.join("\n")).toContain("config.py:3");
    expect(ctx.outLines).toContain("scanning");
  });

  it("keeps waiting while the log is not available yet and does not crash", async () => {
    const { ctx } = await harness({ builds: [{ status: "QUEUED" }, { status: "QUEUED" }, { status: "SUCCEEDED" }] });
    expect(await push(ctx, { wait: true, timeoutSeconds: 900 })).toBe(0);
    expect(ctx.errLines).toEqual([]);
  });

  it("holds back a half-written last line until it is complete", async () => {
    const { ctx } = await harness({
      builds: [{ status: "BUILDING", logUrl: true }, { status: "SUCCEEDED", logUrl: true }],
      logs: ["one\ntw", "one\ntwo\n"],
    });
    await push(ctx, { wait: true, timeoutSeconds: 900 });
    expect(ctx.outLines.filter((l) => l === "one" || l === "two" || l === "tw")).toEqual(["one", "two"]);
  });

  it("prints a rewritten log from the top when it gets shorter (a re-queued build)", async () => {
    const { ctx } = await harness({
      builds: [{ status: "BUILDING", logUrl: true }, { status: "BUILDING", logUrl: true }, { status: "SUCCEEDED", logUrl: true }],
      logs: ["a\nb\nc\n", "x\n", "x\ny\n"],
    });
    await push(ctx, { wait: true, timeoutSeconds: 900 });
    expect(ctx.outLines).toEqual(expect.arrayContaining(["a", "b", "c", "x", "y"]));
    expect(ctx.outLines.filter((l) => l === "y")).toHaveLength(1);
  });

  it("times out with exit 2 while the build is still running", async () => {
    const { ctx } = await harness({ builds: [{ status: "BUILDING" }] });

    const error = await push(ctx, { wait: true, timeoutSeconds: 10 }).catch((e) => e);

    expect(error.message).toMatch(/Timed out after 10s.*BUILDING/);
    expect(error.exitCode).toBe(2);
    expect(ctx.sleeps).toEqual([2000, 2000, 2000, 2000, 2000]);
  });

  it("does not print the signed URL or the key, in success or failure", async () => {
    const ok = await harness({ builds: [{ status: "SUCCEEDED", logUrl: true }], logs: ["hello\n"] });
    await push(ok.ctx, { wait: true, timeoutSeconds: 900 });
    const bad = await harness({ putStatus: 403 });
    const error = await push(bad.ctx, { wait: false, timeoutSeconds: 900 }).catch((e) => e);

    const everything = [...ok.ctx.outLines, ...ok.ctx.errLines, ...bad.ctx.outLines, ...bad.ctx.errLines, error.message].join("\n");
    expect(everything).not.toContain("topsecret");
    expect(everything).not.toContain("ask_live_test");
  });

  it("exits 2 with a readable line when the signed upload is rejected", async () => {
    const { server, ctx } = await harness({ putStatus: 403 });

    const error = await push(ctx, { wait: false, timeoutSeconds: 900 }).catch((e) => e);

    expect(error.message).toMatch(/upload was rejected \(HTTP 403\)/);
    expect(error.exitCode).toBe(2);
    expect(paths(server.requests)).not.toContain(`POST ${BASE}/bundles/${BUNDLE_ID}/finalize`);
  });

  it("shows the backend's reason when finalize rejects the bundle", async () => {
    const { ctx } = await harness({ finalize: { status: 422, json: { code: "INVALID_BUNDLE", message: "Entrypoint agent/main.py is not in the bundle" } } });
    const error = await push(ctx, { wait: false, timeoutSeconds: 900 }).catch((e) => e);
    expect(error.message).toContain("Entrypoint agent/main.py is not in the bundle");
  });

  it("stops with exit 1 before any request when the project does not validate", async () => {
    const { server, ctx } = await harness({ files: { ".agentstoreignore": "agent/\n" } });

    expect(await push(ctx, { wait: false, timeoutSeconds: 900 })).toBe(1);

    expect(server.requests).toHaveLength(0);
    expect(ctx.errLines.join("\n")).toContain("entrypoint agent/main.py is not in the bundle");
    expect(ctx.errLines.join("\n")).toContain(".agentstoreignore");
  });

  it("needs an agentstore.yaml and a login", async () => {
    const ctx = makeContext();
    await expect(push(ctx, { wait: false, timeoutSeconds: 900 })).rejects.toThrow(/agentstore init/);

    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });
    await expect(push(ctx, { wait: false, timeoutSeconds: 900 })).rejects.toThrow(/agentstore login/);
  });

  it("tolerates a trailing slash on the API URL", async () => {
    const { server, ctx } = await harness({ apiUrlSuffix: "/" });
    expect(await push(ctx, { wait: false, timeoutSeconds: 900 })).toBe(0);
    expect(server.requests.every((r) => !r.path.includes("//"))).toBe(true);
  });

  it("reports an unreachable API server in one line with exit 2", async () => {
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: "http://127.0.0.1:1" } });
    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n`, "manifest.json": manifest, "agent/main.py": "x" });

    const error = await push(ctx, { wait: false, timeoutSeconds: 900 }).catch((e) => e);

    expect(error.message).toContain("Could not reach http://127.0.0.1:1");
    expect(error.message).not.toContain("\n");
    expect(error.exitCode).toBe(2);
  });

  it("syncs fixtures/profiles/*.yaml after a successful finalize", async () => {
    const { server, ctx } = await harness({
      files: { "fixtures/profiles/happy-path.yaml": "name: happy-path\nbindings: []\n" },
      sync: { json: [{ name: "happy-path", action: "created", unresolvedFixtures: [] }] },
    });

    expect(await push(ctx, { wait: false, timeoutSeconds: 900 })).toBe(0);

    const syncRequest = server.requests.find((r) => r.path === `${BASE}/sandbox-profiles/sync`);
    expect(syncRequest).toBeDefined();
    const body = JSON.parse(syncRequest!.body.toString());
    expect(body).toHaveLength(1);
    expect(body[0].name).toBe("happy-path");
    expect(typeof body[0].contentHash).toBe("string");
    expect(ctx.outLines.join("\n")).toContain('Profile "happy-path": created');
  });

  it("prints a warning for each unresolved fixture without failing the push", async () => {
    const { ctx } = await harness({
      files: { "fixtures/profiles/p.yaml": "name: p\nbindings:\n  - resource: invoices\n    fixture: missing.csv\n" },
      sync: { json: [{ name: "p", action: "created", unresolvedFixtures: ["missing.csv"] }] },
    });

    expect(await push(ctx, { wait: false, timeoutSeconds: 900 })).toBe(0);

    expect(ctx.errLines.join("\n")).toContain("missing.csv");
  });

  it("does not call sync when there is no fixtures/profiles directory", async () => {
    const { server, ctx } = await harness();

    await push(ctx, { wait: false, timeoutSeconds: 900 });

    expect(server.requests.some((r) => r.path === `${BASE}/sandbox-profiles/sync`)).toBe(false);
  });
});
