import { createHash } from "node:crypto";
import { symlinkSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { unzipSync } from "fflate";
import { describe, expect, it } from "vitest";
import { listFiles, pack } from "../src/pack.js";
import { tempDir, writeFiles } from "./helpers.js";

describe("pack", () => {
  it("puts manifest.json at the root of the zip", () => {
    const dir = tempDir();
    writeFiles(dir, { "manifest.json": "{}", "agent/main.py": "print('hi')" });

    const names = Object.keys(unzipSync(pack(dir).bytes)).sort();

    expect(names).toEqual(["agent/main.py", "manifest.json"]);
  });

  it("applies .agentstoreignore, including directories", () => {
    const dir = tempDir();
    writeFiles(dir, {
      "manifest.json": "{}",
      "agent/main.py": "x",
      "agent/debug.log": "x",
      "secrets/key.txt": "x",
      ".agentstoreignore": "*.log\nsecrets/\n",
    });

    expect(listFiles(dir)).toEqual([".agentstoreignore", "agent/main.py", "manifest.json"]);
  });

  it("always leaves out .git and the root agentstore.yaml, but not a nested one", () => {
    const dir = tempDir();
    writeFiles(dir, {
      "manifest.json": "{}",
      "agentstore.yaml": "agentId: x",
      "sub/agentstore.yaml": "kept: true",
      ".git/config": "x",
      "sub/.git/config": "x",
    });

    expect(listFiles(dir)).toEqual(["manifest.json", "sub/agentstore.yaml"]);
  });

  it("does not follow symlinks", () => {
    const dir = tempDir();
    const outside = tempDir();
    writeFileSync(join(outside, "secret.txt"), "top secret");
    writeFiles(dir, { "manifest.json": "{}" });
    symlinkSync(join(outside, "secret.txt"), join(dir, "link.txt"));
    symlinkSync(outside, join(dir, "linked-dir"));

    expect(listFiles(dir)).toEqual(["manifest.json"]);
  });

  it("is deterministic and reports the sha256 of the zip bytes", () => {
    const dir = tempDir();
    writeFiles(dir, { "manifest.json": "{}", "agent/main.py": "x" });

    const first = pack(dir);
    const second = pack(dir);

    expect(first.sha256).toBe(second.sha256);
    expect(first.sha256).toBe(createHash("sha256").update(first.bytes).digest("hex"));
    expect(first.entries).toEqual(["agent/main.py", "manifest.json"]);
  });
});
