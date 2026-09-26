import { chmodSync, mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { describe, expect, it } from "vitest";
import { CliError } from "../src/errors.js";
import { credentialsPath, DEFAULT_API_URL, loadCredentials, requireCredentials, saveCredentials } from "../src/config.js";
import { makeContext } from "./helpers.js";

describe("credentials", () => {
  it("saves JSON in a private file inside a private directory", () => {
    const ctx = makeContext();
    saveCredentials(ctx, { apiKey: "ask_live_abc", apiUrl: "http://localhost:8080/" });

    const file = credentialsPath(ctx.home);
    expect(statSync(file).mode & 0o777).toBe(0o600);
    expect(statSync(dirname(file)).mode & 0o777).toBe(0o700);
    expect(JSON.parse(readFileSync(file, "utf8"))).toEqual({ apiKey: "ask_live_abc", apiUrl: "http://localhost:8080" });
  });

  it("tightens the mode when it overwrites a file that was too open", () => {
    const ctx = makeContext();
    const file = credentialsPath(ctx.home);
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, "{}");
    chmodSync(file, 0o644);

    saveCredentials(ctx, { apiKey: "ask_live_abc", apiUrl: DEFAULT_API_URL });

    expect(statSync(file).mode & 0o777).toBe(0o600);
  });

  it("returns null when there is no file and no environment", () => {
    expect(loadCredentials(makeContext())).toBeNull();
  });

  it("loads the stored key and defaults the URL", () => {
    const ctx = makeContext();
    saveCredentials(ctx, { apiKey: "ask_live_abc", apiUrl: "http://example.test" });
    expect(loadCredentials(ctx)).toEqual({ apiKey: "ask_live_abc", apiUrl: "http://example.test" });

    const file = credentialsPath(ctx.home);
    writeFileSync(file, JSON.stringify({ apiKey: "ask_live_only_key" }));
    expect(loadCredentials(ctx)).toEqual({ apiKey: "ask_live_only_key", apiUrl: DEFAULT_API_URL });
  });

  it("lets the environment override the file", () => {
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_env", AGENTSTORE_API_URL: "http://env.test/" } });
    saveCredentials(ctx, { apiKey: "ask_live_file", apiUrl: "http://file.test" });
    expect(loadCredentials(ctx)).toEqual({ apiKey: "ask_live_env", apiUrl: "http://env.test" });
  });

  it("works from the environment alone", () => {
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_env" } });
    expect(loadCredentials(ctx)).toEqual({ apiKey: "ask_live_env", apiUrl: DEFAULT_API_URL });
  });

  it("warns when the file is readable by other users", () => {
    const ctx = makeContext();
    saveCredentials(ctx, { apiKey: "ask_live_abc", apiUrl: DEFAULT_API_URL });
    chmodSync(credentialsPath(ctx.home), 0o644);

    loadCredentials(ctx);

    expect(ctx.errLines.join("\n")).toContain("chmod 600");
  });

  it("rejects a corrupt file with a readable error", () => {
    const ctx = makeContext();
    const file = credentialsPath(ctx.home);
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, "not json", { mode: 0o600 });
    expect(() => loadCredentials(ctx)).toThrow(CliError);
    expect(() => loadCredentials(ctx)).toThrow(/agentstore login/);
  });

  it("requireCredentials points at login when there is nothing", () => {
    expect(() => requireCredentials(makeContext())).toThrow(/agentstore login/);
  });
});
