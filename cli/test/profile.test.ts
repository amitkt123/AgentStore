import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { profileList, profilePull } from "../src/commands/profile.js";
import { AGENT_ID, makeContext, startServer, writeFiles } from "./helpers.js";

const BASE = `/api/v1/agents/${AGENT_ID}/sandbox-profiles`;

describe("profile list", () => {
  it("prints each profile's status, trial flag, and source", async () => {
    const server = await startServer([
      { method: "GET", path: BASE, reply: { json: [
        { name: "happy-path", status: "READY", trialEnabled: true, source: "PORTAL" },
      ] } },
    ]);
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: server.url } });
    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });

    expect(await profileList(ctx)).toBe(0);
    expect(ctx.outLines.join("\n")).toContain("happy-path");
    expect(ctx.outLines.join("\n")).toContain("READY");
    expect(ctx.outLines.join("\n")).toContain("trial=true");
  });

  it("prints a friendly line when there are no profiles", async () => {
    const server = await startServer([{ method: "GET", path: BASE, reply: { json: [] } }]);
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: server.url } });
    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });

    expect(await profileList(ctx)).toBe(0);
    expect(ctx.outLines).toEqual(["No sandbox profiles."]);
  });
});

describe("profile pull", () => {
  it("writes every server-side profile to fixtures/profiles/<name>.yaml", async () => {
    const server = await startServer([
      { method: "GET", path: BASE, reply: { json: [
        {
          name: "happy-path", description: "Clean batch", input: {}, gatePolicy: { default: "AUTO_APPROVE", actions: {} },
          llmMode: "MOCK", spendCap: 50, bindings: [{ resource: "invoices", datasetId: "d1", displayName: "sample-invoices.csv" }],
        },
      ] } },
    ]);
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: server.url } });
    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });

    expect(await profilePull(ctx)).toBe(0);

    const written = readFileSync(join(ctx.cwd, "fixtures", "profiles", "happy-path.yaml"), "utf8");
    expect(written).toContain("name: happy-path");
    expect(written).toContain("fixture: sample-invoices.csv");
    expect(ctx.outLines.join("\n")).toContain("happy-path.yaml");
  });
});
