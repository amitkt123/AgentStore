import { existsSync, mkdirSync, readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { init } from "../src/commands/init.js";
import { readProject } from "../src/project.js";
import { validateProject } from "../src/validate.js";
import { AGENT_ID, makeContext, startServer, tempDir, writeFiles } from "./helpers.js";

async function setup(opts: { cwd?: string; answers?: string[]; reply?: object } = {}) {
  const server = await startServer([
    { method: "POST", path: "/api/v1/agents", reply: { json: opts.reply ?? { id: AGENT_ID, name: "Recon", category: "finance", description: "d" } } },
  ]);
  const ctx = makeContext({
    cwd: opts.cwd,
    answers: opts.answers,
    env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: server.url },
  });
  return { server, ctx };
}

describe("init", () => {
  it("creates the agent, then scaffolds a python project holding its id", async () => {
    const { server, ctx } = await setup();

    expect(await init(ctx, { runtime: "python", name: "Recon", category: "finance", description: "d" })).toBe(0);

    expect(JSON.parse(server.requests[0].body.toString())).toEqual({ name: "Recon", category: "finance", description: "d" });
    expect(server.requests[0].headers.authorization).toBe("Bearer ask_live_test");
    expect(readProject(ctx.cwd)).toEqual({ agentId: AGENT_ID, name: "Recon" });
    for (const path of ["manifest.json", "agent/main.py", "requirements.txt", "fixtures/.gitkeep", "evals/.gitkeep", "schemas/.gitkeep", ".agentstoreignore"]) {
      expect(existsSync(join(ctx.cwd, path)), path).toBe(true);
    }
    const manifest = JSON.parse(readFileSync(join(ctx.cwd, "manifest.json"), "utf8"));
    expect(manifest).toMatchObject({ name: "Recon", runtime: "python-3.12", entrypoint: "agent/main.py" });
    expect(ctx.outLines.join("\n")).toContain(AGENT_ID);
  });

  it("scaffolds a node project for --node, and --typescript is an alias", async () => {
    for (const runtime of ["node", "typescript"] as const) {
      const { ctx } = await setup();
      await init(ctx, { runtime, name: "Recon", category: "finance", description: "d" });
      const manifest = JSON.parse(readFileSync(join(ctx.cwd, "manifest.json"), "utf8"));
      expect(manifest).toMatchObject({ runtime: "node-22", entrypoint: "agent/main.mjs" });
      expect(existsSync(join(ctx.cwd, "agent/main.mjs"))).toBe(true);
      expect(existsSync(join(ctx.cwd, "package.json"))).toBe(false);
    }
  });

  it.each(["python", "node"] as const)("scaffolds a %s project that passes validate", async (runtime) => {
    const { ctx } = await setup();
    await init(ctx, { runtime, name: "Recon", category: "finance", description: "d" });
    expect(validateProject(ctx.cwd).problems).toEqual([]);
  });

  it("asks for what the flags left out, defaulting the name to the directory", async () => {
    const cwd = join(tempDir(), "my-agent");
    mkdirSync(cwd);
    const { server, ctx } = await setup({ cwd, answers: ["", "", ""] });

    await init(ctx, { runtime: "python" });

    expect(JSON.parse(server.requests[0].body.toString())).toEqual({ name: "my-agent", category: "general", description: "" });
  });

  it("refuses a non-empty directory before calling the API", async () => {
    const { server, ctx } = await setup();
    writeFiles(ctx.cwd, { "notes.txt": "mine" });

    await expect(init(ctx, { runtime: "python", name: "Recon", category: "c", description: "" })).rejects.toThrow(/not empty/);

    expect(server.requests).toHaveLength(0);
    expect(readdirSync(ctx.cwd)).toEqual(["notes.txt"]);
  });

  it("refuses to run over an existing agentstore.yaml before calling the API", async () => {
    const { server, ctx } = await setup();
    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });

    await expect(init(ctx, { runtime: "python", name: "Recon", category: "c", description: "" })).rejects.toThrow(/not empty/);

    expect(server.requests).toHaveLength(0);
  });

  it("allows a directory that only holds .git", async () => {
    const { ctx } = await setup();
    writeFiles(ctx.cwd, { ".git/HEAD": "ref: refs/heads/main" });
    expect(await init(ctx, { runtime: "python", name: "Recon", category: "c", description: "" })).toBe(0);
  });

  it("writes nothing when the API refuses to create the agent", async () => {
    const server = await startServer([{ method: "POST", path: "/api/v1/agents", reply: { status: 401 } }]);
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_bad", AGENTSTORE_API_URL: server.url } });

    await expect(init(ctx, { runtime: "python", name: "R", category: "c", description: "" })).rejects.toThrow(/rejected or revoked/);

    expect(readdirSync(ctx.cwd)).toEqual([]);
  });

  it("prints the agent id when the scaffold cannot be written, so init is not re-run", async () => {
    const { ctx } = await setup();

    const error = await init(ctx, { runtime: "python", name: "Recon", category: "c", description: "" },
      () => { throw new Error("disk full"); }).catch((e) => e);

    expect(error.message).toContain(AGENT_ID);
    expect(error.message).toContain("disk full");
    expect(error.message).toContain(`agentId: ${AGENT_ID}`);
  });

  it("requires a login first", async () => {
    const ctx = makeContext();
    await expect(init(ctx, { runtime: "python", name: "R", category: "c", description: "" })).rejects.toThrow(/agentstore login/);
  });
});
