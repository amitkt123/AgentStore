import { describe, expect, it } from "vitest";
import { main } from "../src/cli.js";
import { makeContext } from "./helpers.js";

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
