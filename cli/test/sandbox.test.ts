import { describe, expect, it } from "vitest";
import { main } from "../src/cli.js";
import { POLL_INTERVAL_MS, sandboxLogs, sandboxRun } from "../src/commands/sandbox.js";
import { AGENT_ID, makeContext, startServer, writeFiles, type Reply, type Route } from "./helpers.js";

const RUN_ID = "dddddddd-2222-4333-8444-555555555555";
const VERSION_ID_1 = "eeeeeeee-2222-4333-8444-555555555555";
const VERSION_ID_2 = "ffffffff-2222-4333-8444-555555555555";
const PROFILE_ID = "11111111-9999-4333-8444-555555555555";
const GATE_ID = "22222222-9999-4333-8444-555555555555";

function agentsMine(versions: Array<{ id: string; versionNumber: string; status: string }>): Route {
  return {
    method: "GET",
    path: "/api/v1/agents/mine",
    reply: { json: [{ agentId: AGENT_ID, agentName: "Recon", category: "finance", versions }] },
  };
}

function profilesRoute(profiles: Array<{ id: string; name: string }>): Route {
  return { method: "GET", path: `/api/v1/agents/${AGENT_ID}/sandbox-profiles`, reply: { json: profiles } };
}

function startRoute(status = "RUNNING"): Route {
  return {
    method: "POST",
    path: "/api/v1/sandbox/runs",
    reply: { status: 201, json: { id: RUN_ID, displayId: "S-DDDDDDDD", status } },
  };
}

interface DetailStep {
  kind: string;
  detail: string;
}

function detailRoute(steps: DetailStep[], status: string, pendingGate: { id: string; action: string; summary: string } | null = null): Route {
  return {
    method: "GET",
    path: `/api/v1/sandbox/runs/${RUN_ID}`,
    reply: {
      json: {
        id: RUN_ID, displayId: "S-DDDDDDDD", status, simulatedCharge: 6, requestedCharge: 6,
        steps: steps.map((s) => ({ kind: s.kind, source: "PLATFORM", detail: s.detail, createdAt: "2026-01-01T00:00:00Z" })),
        pendingGate,
      },
    },
  };
}

async function harness(routes: Route[]) {
  const { url, requests } = await startServer(routes);
  const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: url } });
  writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });
  return { ctx, requests };
}

describe("sandbox run", () => {
  it("without --follow posts an inline profile for the highest version and prints the display id", async () => {
    const { ctx, requests } = await harness([
      agentsMine([
        { id: VERSION_ID_1, versionNumber: "1.9.0", status: "LIVE" },
        { id: VERSION_ID_2, versionNumber: "1.10.0", status: "DRAFT" },
      ]),
      startRoute(),
    ]);

    const code = await sandboxRun(ctx, { follow: false });

    expect(code).toBe(0);
    expect(ctx.outLines.join("\n")).toContain("S-DDDDDDDD");
    const start = requests.find((r) => r.method === "POST" && r.path === "/api/v1/sandbox/runs")!;
    const body = JSON.parse(start.body.toString());
    expect(body).toEqual({ agentVersionId: VERSION_ID_2, inlineProfile: {} });
  });

  it("--version picks that version; an unknown version lists what is available", async () => {
    const { ctx } = await harness([
      agentsMine([{ id: VERSION_ID_1, versionNumber: "1.0.0", status: "LIVE" }]),
    ]);

    const code = await main(["sandbox", "run", "--version", "9.9.9"], ctx);

    expect(code).toBe(2);
    expect(ctx.errLines.join("\n")).toContain("1.0.0");
  });

  it("--profile resolves the profile id by name; an unknown profile lists what is available", async () => {
    const { ctx } = await harness([
      agentsMine([{ id: VERSION_ID_1, versionNumber: "1.0.0", status: "LIVE" }]),
      profilesRoute([{ id: PROFILE_ID, name: "smoke" }]),
    ]);

    const code = await main(["sandbox", "run", "--profile", "nope"], ctx);

    expect(code).toBe(2);
    expect(ctx.errLines.join("\n")).toContain("smoke");
  });

  it("posts the resolved profile id when --profile matches", async () => {
    const { ctx, requests } = await harness([
      agentsMine([{ id: VERSION_ID_1, versionNumber: "1.0.0", status: "LIVE" }]),
      profilesRoute([{ id: PROFILE_ID, name: "smoke" }]),
      startRoute(),
    ]);

    const code = await sandboxRun(ctx, { profile: "smoke", follow: false });

    expect(code).toBe(0);
    const start = requests.find((r) => r.method === "POST" && r.path === "/api/v1/sandbox/runs")!;
    expect(JSON.parse(start.body.toString())).toEqual({ agentVersionId: VERSION_ID_1, profileId: PROFILE_ID });
  });

  it("--follow prints each step once across polls, then the terminal summary, exit 0 for COMPLETED", async () => {
    let call = 0;
    const details = [
      detailRoute([{ kind: "READ", detail: "{}" }], "RUNNING"),
      detailRoute([{ kind: "READ", detail: "{}" }, { kind: "COMPLETE", detail: JSON.stringify({ summary: "done" }) }], "COMPLETED"),
    ];
    const { ctx } = await harness([
      agentsMine([{ id: VERSION_ID_1, versionNumber: "1.0.0", status: "LIVE" }]),
      startRoute(),
      { method: "GET", path: `/api/v1/sandbox/runs/${RUN_ID}`, reply: () => details[Math.min(call++, details.length - 1)].reply as Reply },
    ]);

    const code = await sandboxRun(ctx, { follow: true });

    expect(code).toBe(0);
    const out = ctx.outLines.join("\n");
    expect(out).toContain("READ: {}");
    expect((out.match(/READ: \{\}/g) ?? []).length).toBe(1);
    expect(out).toContain("COMPLETE: done");
    expect(out).toContain("S-DDDDDDDD COMPLETED");
    expect(ctx.sleeps.every((ms) => ms === POLL_INTERVAL_MS)).toBe(true);
  });

  it("--follow exits 1 for a FAILED run", async () => {
    const { ctx } = await harness([
      agentsMine([{ id: VERSION_ID_1, versionNumber: "1.0.0", status: "LIVE" }]),
      startRoute(),
      detailRoute([], "FAILED"),
    ]);

    expect(await sandboxRun(ctx, { follow: true })).toBe(1);
  });

  it("prompts for a MANUAL gate and posts the decision, never asking about the same gate twice", async () => {
    let call = 0;
    const decisions: unknown[] = [];
    const { ctx } = await harness([
      agentsMine([{ id: VERSION_ID_1, versionNumber: "1.0.0", status: "LIVE" }]),
      startRoute(),
      {
        method: "GET", path: `/api/v1/sandbox/runs/${RUN_ID}`,
        reply: () => {
          call++;
          if (call <= 2) {
            return detailRoute([], "AWAITING_APPROVAL", { id: GATE_ID, action: "post_journal_entries", summary: "Post 2" }).reply as Reply;
          }
          return detailRoute([{ kind: "COMPLETE", detail: "{}" }], "COMPLETED").reply as Reply;
        },
      },
      {
        method: "POST", path: `/api/v1/sandbox/runs/${RUN_ID}/gates/${GATE_ID}/decision`,
        reply: (req) => { decisions.push(JSON.parse(req.body.toString())); return { json: {} }; },
      },
    ]);
    ctx.answers.push("y");

    const code = await sandboxRun(ctx, { follow: true });

    expect(code).toBe(0);
    expect(decisions).toEqual([{ approve: true }]);
  });

  it("a 'n' answer posts approve:false", async () => {
    let call = 0;
    const decisions: unknown[] = [];
    const { ctx } = await harness([
      agentsMine([{ id: VERSION_ID_1, versionNumber: "1.0.0", status: "LIVE" }]),
      startRoute(),
      {
        method: "GET", path: `/api/v1/sandbox/runs/${RUN_ID}`,
        reply: () => {
          call++;
          if (call === 1) {
            return detailRoute([], "AWAITING_APPROVAL", { id: GATE_ID, action: "a", summary: "s" }).reply as Reply;
          }
          return detailRoute([], "CANCELLED").reply as Reply;
        },
      },
      {
        method: "POST", path: `/api/v1/sandbox/runs/${RUN_ID}/gates/${GATE_ID}/decision`,
        reply: (req) => { decisions.push(JSON.parse(req.body.toString())); return { json: {} }; },
      },
    ]);
    ctx.answers.push("n");

    await sandboxRun(ctx, { follow: true });

    expect(decisions).toEqual([{ approve: false }]);
  });

  it("surfaces a 429 quota message with exit 2", async () => {
    const { ctx } = await harness([
      agentsMine([{ id: VERSION_ID_1, versionNumber: "1.0.0", status: "LIVE" }]),
      {
        method: "POST", path: "/api/v1/sandbox/runs",
        reply: { status: 429, json: { code: "SANDBOX_QUOTA_EXCEEDED", message: "Sandbox limit reached: concurrentRuns", limit: "concurrentRuns", used: 1, max: 1 } },
      },
    ]);

    let thrown: Error | null = null;
    try {
      await sandboxRun(ctx, { follow: false });
    } catch (e) {
      thrown = e as Error;
    }

    expect(thrown?.message).toContain("Sandbox limit reached: concurrentRuns");
  });
});

describe("sandbox logs", () => {
  it("prints every step once and returns 0", async () => {
    const { ctx } = await harness([
      detailRoute([{ kind: "READ", detail: "{}" }, { kind: "COMPLETE", detail: "{}" }], "COMPLETED"),
    ]);

    const code = await sandboxLogs(ctx, RUN_ID);

    expect(code).toBe(0);
    expect(ctx.outLines.filter((l) => l.startsWith("READ:"))).toHaveLength(1);
  });

  it("a 404 gives a readable error", async () => {
    const { ctx } = await harness([
      { method: "GET", path: `/api/v1/sandbox/runs/${RUN_ID}`, reply: { status: 404, json: { code: "NOT_FOUND", message: "Run not found" } } },
    ]);

    let thrown: Error | null = null;
    try {
      await sandboxLogs(ctx, RUN_ID);
    } catch (e) {
      thrown = e as Error;
    }

    expect(thrown?.message).toContain("Run not found");
  });
});

describe("sandbox command via main", () => {
  it("no subcommand is a usage error", async () => {
    const ctx = makeContext();
    expect(await main(["sandbox"], ctx)).toBe(2);
  });

  it("an unknown subcommand is a usage error", async () => {
    const ctx = makeContext();
    expect(await main(["sandbox", "bogus"], ctx)).toBe(2);
  });

  it("a stray flag on run is a usage error", async () => {
    const ctx = makeContext();
    expect(await main(["sandbox", "run", "--wat"], ctx)).toBe(2);
  });

  it("logs with no run id is a usage error", async () => {
    const ctx = makeContext();
    expect(await main(["sandbox", "logs"], ctx)).toBe(2);
  });
});
