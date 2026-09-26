import { describe, expect, it } from "vitest";
import { main } from "../src/cli.js";
import { validateCommand } from "../src/commands/validate.js";
import { makeContext, writeFiles } from "./helpers.js";

describe("main", () => {
  it("prints usage and exits 2 when no command is given", async () => {
    const ctx = makeContext();
    expect(await main([], ctx)).toBe(2);
    expect(ctx.errLines.join("\n")).toContain("Usage: agentstore");
  });

  it("prints usage and exits 0 for help", async () => {
    const ctx = makeContext();
    expect(await main(["--help"], ctx)).toBe(0);
    expect(ctx.outLines.join("\n")).toContain("push");
  });

  it("rejects an unknown command with exit 2", async () => {
    const ctx = makeContext();
    expect(await main(["frobnicate"], ctx)).toBe(2);
    expect(ctx.errLines.join("\n")).toContain('Unknown command "frobnicate"');
  });

  it("rejects an unknown option with exit 2 and the usage text", async () => {
    const ctx = makeContext();
    expect(await main(["login", "--bogus"], ctx)).toBe(2);
    expect(ctx.errLines.join("\n")).toContain("Usage: agentstore");
  });

  it("maps a CliError to its message and exit code", async () => {
    const ctx = makeContext({ secrets: ["not-a-key"] });
    expect(await main(["login"], ctx)).toBe(2);
    expect(ctx.errLines.join("\n")).toContain("ask_live_");
  });
});

describe("validate command", () => {
  it("exits 1 and lists every problem when the project is bad", async () => {
    const ctx = makeContext();
    expect(await main(["validate"], ctx)).toBe(1);
    expect(ctx.errLines.join("\n")).toContain("manifest.json is missing");
  });

  it("exits 0 for a valid project", async () => {
    const ctx = makeContext();
    writeFiles(ctx.cwd, {
      "manifest.json": JSON.stringify({
        name: "R", version: "1.0.0", runtime: "python-3.12", entrypoint: "agent/main.py", scopes: [],
        network: { allowlist: [] },
        costEnvelope: { currency: "INR", maxPerRun: 1, pricingUnit: "METERED", unitPrice: 1, unitLabel: "u" },
        approvalGates: [], dataClasses: [],
      }),
      "agent/main.py": "x",
    });
    expect(await validateCommand(ctx)).toBe(0);
    expect(ctx.outLines.join("\n")).toMatch(/^OK: 2 files/);
  });

  it("rejects stray arguments with exit 2", async () => {
    const ctx = makeContext();
    expect(await main(["validate", "--wat"], ctx)).toBe(2);
  });
});

describe("init command", () => {
  it("rejects two runtime flags with exit 2", async () => {
    const ctx = makeContext();
    expect(await main(["init", "--python", "--node"], ctx)).toBe(2);
    expect(ctx.errLines.join("\n")).toContain("Choose one of");
  });
});

describe("push command", () => {
  it.each(["0", "-5", "abc", "1.5"])("rejects --timeout %s with exit 2", async (timeout) => {
    const ctx = makeContext();
    expect(await main(["push", `--timeout=${timeout}`], ctx)).toBe(2);
    expect(ctx.errLines.join("\n")).toContain("--timeout must be a whole number of seconds");
  });

  it("reports a missing agentstore.yaml with exit 2", async () => {
    const ctx = makeContext();
    expect(await main(["push"], ctx)).toBe(2);
    expect(ctx.errLines.join("\n")).toContain("agentstore init");
  });
});
