import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { validateProject } from "../src/validate.js";
import { tempDir, writeFiles } from "./helpers.js";

const manifest = (overrides: Record<string, unknown> = {}) => JSON.stringify({
  name: "Recon",
  version: "1.0.0",
  runtime: "python-3.12",
  entrypoint: "agent/main.py",
  scopes: [{ type: "READ", resource: "bank_statement", description: "Read rows" }],
  network: { allowlist: [] },
  costEnvelope: { currency: "INR", maxPerRun: 500, pricingUnit: "METERED", unitPrice: 2, unitLabel: "txn" },
  approvalGates: [],
  dataClasses: ["BANK_STATEMENT"],
  ...overrides,
});

function project(files: Record<string, string> = {}, m: Record<string, unknown> = {}): string {
  const dir = tempDir();
  writeFiles(dir, { "manifest.json": manifest(m), "agent/main.py": "print('hi')\n", ...files });
  return dir;
}

describe("validateProject", () => {
  it("accepts a valid python project and returns the packed bundle", () => {
    const result = validateProject(project());
    expect(result.problems).toEqual([]);
    expect(result.bundle?.entries).toEqual(["agent/main.py", "manifest.json"]);
  });

  it("accepts a valid node project", () => {
    const dir = project({ "agent/main.mjs": "export {}" }, { runtime: "node-22", entrypoint: "agent/main.mjs" });
    expect(validateProject(dir).problems).toEqual([]);
  });

  it("says so when there is no manifest.json", () => {
    const dir = tempDir();
    expect(validateProject(dir).problems).toEqual(["manifest.json is missing from the project root"]);
  });

  it("reports invalid JSON without throwing", () => {
    const dir = tempDir();
    writeFiles(dir, { "manifest.json": "{ nope" });
    const { problems, bundle } = validateProject(dir);
    expect(problems[0]).toMatch(/manifest\.json is not valid JSON/);
    expect(bundle).toBeNull();
  });

  it("reports schema violations with their location", () => {
    const dir = project({}, { costEnvelope: { currency: "INR" } });
    const problems = validateProject(dir).problems.join("\n");
    expect(problems).toContain("/costEnvelope");
    expect(problems).toContain("maxPerRun");
  });

  it("requires runtime and entrypoint", () => {
    const dir = tempDir();
    writeFiles(dir, { "manifest.json": manifest({ runtime: undefined, entrypoint: undefined }) });
    expect(validateProject(dir).problems).toEqual(expect.arrayContaining([
      "runtime is required (python-3.12 or node-22)",
      "entrypoint is required",
    ]));
  });

  it("rejects an unknown runtime", () => {
    expect(validateProject(project({}, { runtime: "ruby-3" })).problems.join("\n"))
      .toContain("runtime must be python-3.12 or node-22");
  });

  it.each([
    ["/etc/passwd"], ["../escape.py"], ["a//b.py"], ["agent/../x.py"], ["-lead.py"],
  ])("rejects the entrypoint %s", (entrypoint) => {
    const problems = validateProject(project({}, { entrypoint })).problems.join("\n");
    expect(problems).toContain("entrypoint must be a relative path inside the bundle");
  });

  it("rejects an entrypoint whose extension does not match the runtime", () => {
    const dir = project({ "agent/main.js": "x" }, { entrypoint: "agent/main.js" });
    expect(validateProject(dir).problems).toContain("entrypoint agent/main.js does not match runtime python-3.12");
  });

  it("names the file when the entrypoint does not exist", () => {
    const dir = project({}, { entrypoint: "agent/missing.py" });
    expect(validateProject(dir).problems.join("\n"))
      .toContain("entrypoint agent/missing.py is not in the bundle (missing, or excluded by .agentstoreignore)");
  });

  it("catches an entrypoint that .agentstoreignore excludes", () => {
    const dir = project({ ".agentstoreignore": "agent/\n" });
    const { problems, bundle } = validateProject(dir);
    expect(problems.join("\n")).toContain("entrypoint agent/main.py is not in the bundle");
    expect(problems.join("\n")).toContain(".agentstoreignore");
    expect(bundle).toBeNull();
  });

  it("requires package-lock.json next to package.json for node projects", () => {
    const dir = project({ "agent/main.mjs": "x", "package.json": "{}" }, { runtime: "node-22", entrypoint: "agent/main.mjs" });
    expect(validateProject(dir).problems).toContain("package-lock.json is required next to package.json");

    writeFiles(dir, { "package-lock.json": "{}" });
    expect(validateProject(dir).problems).toEqual([]);
  });

  it("enforces the packed limits", () => {
    const dir = project();
    const limits = { maxBundleBytes: 10, maxEntries: 1, maxManifestBytes: 10 };
    const problems = validateProject(dir, limits).problems.join("\n");
    expect(problems).toContain("manifest.json is");
    expect(problems).toContain("more than 1 files");
  });

  it("enforces the packed size limit", () => {
    const dir = project();
    const limits = { maxBundleBytes: 10, maxEntries: 20_000, maxManifestBytes: 256 * 1024 };
    expect(validateProject(dir, limits).problems.join("\n")).toMatch(/bundle is \d+ bytes; the limit is 10/);
  });

  it("keeps the bundled schema identical to the backend's", () => {
    const here = import.meta.dirname;
    const backend = readFileSync(join(here, "../../backend/src/main/resources/manifest/manifest-schema.json"), "utf8");
    const bundled = readFileSync(join(here, "../src/schema/manifest-schema.json"), "utf8");
    expect(JSON.parse(bundled)).toEqual(JSON.parse(backend));
  });
});
