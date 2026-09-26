import { describe, expect, it } from "vitest";
import { CliError } from "../src/errors.js";
import { readProject } from "../src/project.js";
import { AGENT_ID, tempDir, writeFiles } from "./helpers.js";

describe("readProject", () => {
  it("reads the agent id and name", () => {
    const dir = tempDir();
    writeFiles(dir, { "agentstore.yaml": `agentId: ${AGENT_ID}\nname: My Agent\n` });
    expect(readProject(dir)).toEqual({ agentId: AGENT_ID, name: "My Agent" });
  });

  it("points at init when there is no agentstore.yaml", () => {
    expect(() => readProject(tempDir())).toThrow(/agentstore init/);
  });

  it("rejects a file without an agent id", () => {
    const dir = tempDir();
    writeFiles(dir, { "agentstore.yaml": "name: x\n" });
    expect(() => readProject(dir)).toThrow(/no agentId/);
  });

  it("rejects an agent id that is not a UUID, since it goes into request paths", () => {
    const dir = tempDir();
    writeFiles(dir, { "agentstore.yaml": "agentId: ../../admin\n" });
    const error = (() => { try { readProject(dir); } catch (e) { return e; } })();
    expect(error).toBeInstanceOf(CliError);
    expect((error as CliError).message).toMatch(/not a valid agent id/);
  });
});
