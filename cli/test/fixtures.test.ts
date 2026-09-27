import { describe, expect, it } from "vitest";
import { fixturesList, fixturesPush } from "../src/commands/fixtures.js";
import { AGENT_ID, makeContext, startServer, writeFiles } from "./helpers.js";
import { writeFileSync } from "node:fs";
import { join } from "node:path";

describe("fixtures push", () => {
  it("uploads a local file and prints the result", async () => {
    const server = await startServer([
      { method: "POST", path: "/api/v1/developer/fixtures", reply: () => ({ status: 200, json: {
        id: "f1", displayName: "sample.csv", format: "CSV", rowCount: 3, piiScan: { status: "CLEAN", findingCount: 0 },
      } }) },
    ]);
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: server.url } });
    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });
    const filePath = join(ctx.cwd, "sample.csv");
    writeFileSync(filePath, "a,b\n1,2\n");

    const code = await fixturesPush(ctx, { file: filePath });

    expect(code).toBe(0);
    expect(server.requests).toHaveLength(1);
    expect(server.requests[0].method).toBe("POST");
    expect(server.requests[0].path).toBe("/api/v1/developer/fixtures");
    expect(server.requests[0].body.toString()).toContain("sample.csv");
    expect(ctx.outLines.join("\n")).toContain("sample.csv");
  });

  it("warns when the PII scan found something", async () => {
    const server = await startServer([
      { method: "POST", path: "/api/v1/developer/fixtures", reply: () => ({ status: 200, json: {
        id: "f1", displayName: "leak.csv", format: "CSV", rowCount: 1, piiScan: { status: "FOUND", findingCount: 2 },
      } }) },
    ]);
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: server.url } });
    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });
    const filePath = join(ctx.cwd, "leak.csv");
    writeFileSync(filePath, "email\nperson@example.com\n");

    await fixturesPush(ctx, { file: filePath });

    expect(ctx.outLines.join("\n")).toContain("PII scan found");
  });

  it("exits 2 with a readable line when the file does not exist", async () => {
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: "http://x" } });
    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });

    const error = await fixturesPush(ctx, { file: join(ctx.cwd, "missing.csv") }).catch((e) => e);

    expect(error.message).toContain("missing.csv");
    expect(error.exitCode).toBe(2);
  });
});

describe("fixtures list", () => {
  it("prints each fixture with its format and pii scan status", async () => {
    const server = await startServer([
      { method: "GET", path: "/api/v1/developer/fixtures", reply: { json: [
        { id: "f1", displayName: "a.csv", format: "CSV", rowCount: 1, piiScan: { status: "CLEAN" } },
        { id: "f2", displayName: "b.csv", format: "CSV", rowCount: 1, piiScan: null },
      ] } },
    ]);
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: server.url } });
    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });

    const code = await fixturesList(ctx);

    expect(code).toBe(0);
    expect(ctx.outLines.join("\n")).toContain("a.csv");
    expect(ctx.outLines.join("\n")).toContain("clean");
    expect(ctx.outLines.join("\n")).toContain("b.csv");
    expect(ctx.outLines.join("\n")).toContain("unscanned");
  });

  it("prints a friendly line when there are no fixtures", async () => {
    const server = await startServer([{ method: "GET", path: "/api/v1/developer/fixtures", reply: { json: [] } }]);
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: server.url } });
    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });

    expect(await fixturesList(ctx)).toBe(0);
    expect(ctx.outLines).toEqual(["No fixtures."]);
  });
});
