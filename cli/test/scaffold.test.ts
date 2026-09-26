import { describe, expect, it } from "vitest";
import { scaffoldFiles } from "../src/scaffold.js";

describe("scaffold agent code", () => {
  it.each([
    ["python", "agent/main.py"],
    ["node", "agent/main.mjs"],
  ] as const)("the %s scaffold completes through the runtime API", (runtime, path) => {
    const source = scaffoldFiles(runtime, "Recon", "agent-id")[path];

    expect(source).toContain("AGENTSTORE_API_URL");
    expect(source).toContain("AGENTSTORE_RUN_TOKEN");
    expect(source).toContain("/runtime/v1/complete");
    expect(source).not.toContain("AGENT_CALLBACK");
    expect(source).not.toContain("/events");
  });
});
