import { readFileSync, existsSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { credentialsPath } from "../src/config.js";
import { login } from "../src/commands/login.js";
import { makeContext, startServer } from "./helpers.js";

describe("login", () => {
  it("verifies the key against the API, then saves it", async () => {
    const server = await startServer([{ method: "GET", path: "/api/v1/agents/mine", reply: { json: [] } }]);
    const ctx = makeContext({ secrets: ["ask_live_good"] });

    expect(await login(ctx, { apiUrl: `${server.url}/` })).toBe(0);

    expect(server.requests[0].headers.authorization).toBe("Bearer ask_live_good");
    expect(JSON.parse(readFileSync(credentialsPath(ctx.home), "utf8")))
      .toEqual({ apiKey: "ask_live_good", apiUrl: server.url });
    expect(ctx.outLines.join("\n")).toContain("Logged in");
    expect(ctx.outLines.join("\n")).not.toContain("ask_live_good");
  });

  it("does not save a key the API rejects", async () => {
    const server = await startServer([{ method: "GET", path: "/api/v1/agents/mine", reply: { status: 401 } }]);
    const ctx = makeContext({ secrets: ["ask_live_bad"] });

    await expect(login(ctx, { apiUrl: server.url })).rejects.toThrow(/rejected or revoked/);

    expect(existsSync(credentialsPath(ctx.home))).toBe(false);
  });

  it("refuses text that is not an API key without calling the API", async () => {
    const server = await startServer([]);
    const ctx = makeContext({ secrets: ["hunter2"] });

    await expect(login(ctx, { apiUrl: server.url })).rejects.toThrow(/ask_live_/);

    expect(server.requests).toHaveLength(0);
  });

  it("rejects an invalid --api-url before asking for the key", async () => {
    const ctx = makeContext({ secrets: ["ask_live_x"] });
    await expect(login(ctx, { apiUrl: "not a url" })).rejects.toThrow(/not a valid URL/);
    expect(ctx.secrets).toEqual(["ask_live_x"]);
  });

  it("tells the person where to create a key", async () => {
    const server = await startServer([{ method: "GET", path: "/api/v1/agents/mine", reply: { json: [] } }]);
    const ctx = makeContext({ secrets: ["ask_live_good"], env: { AGENTSTORE_PORTAL_URL: "https://portal.test" } });
    await login(ctx, { apiUrl: server.url });
    expect(ctx.outLines.join("\n")).toContain("https://portal.test/dashboard/api-keys");
  });
});
