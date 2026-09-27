import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { readProfileFiles, writeProfileFile } from "../src/profiles.js";
import { tempDir, writeFiles } from "./helpers.js";

describe("readProfileFiles", () => {
  it("returns an empty list when there is no fixtures/profiles directory", () => {
    expect(readProfileFiles(tempDir())).toEqual([]);
  });

  it("parses every yaml file and hashes its raw bytes", () => {
    const dir = tempDir();
    const yaml = "name: happy-path\ndescription: Clean batch\nbindings:\n  - resource: invoices\n    fixture: sample.csv\n";
    writeFiles(dir, { "fixtures/profiles/happy-path.yaml": yaml });

    const entries = readProfileFiles(dir);

    expect(entries).toHaveLength(1);
    expect(entries[0].name).toBe("happy-path");
    expect(entries[0].description).toBe("Clean batch");
    expect(entries[0].bindings).toEqual([{ resource: "invoices", fixture: "sample.csv" }]);
    expect(entries[0].contentHash).toHaveLength(64);
  });

  it("computes a stable hash for the same bytes", () => {
    const dir = tempDir();
    writeFiles(dir, { "fixtures/profiles/p.yaml": "name: p\n" });
    const [first] = readProfileFiles(dir);
    const [second] = readProfileFiles(dir);
    expect(first.contentHash).toBe(second.contentHash);
  });
});

describe("writeProfileFile", () => {
  it("writes a yaml file named after the profile", () => {
    const dir = tempDir();
    writeProfileFile(dir, { name: "pulled", description: "d", bindings: [{ resource: "r", fixture: "f.csv" }] });

    const path = join(dir, "fixtures", "profiles", "pulled.yaml");
    expect(existsSync(path)).toBe(true);
    expect(readFileSync(path, "utf8")).toContain("name: pulled");
  });
});
