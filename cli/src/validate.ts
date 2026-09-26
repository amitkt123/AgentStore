import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { Ajv } from "ajv";   // the default import is not constructable under NodeNext (verified with tsc)
import { IGNORE_FILE, type Limits, LIMITS, listFiles, pack, type PackedBundle } from "./pack.js";
import schema from "./schema/manifest-schema.json" with { type: "json" };

/** Mirrors AgentRuntime in the backend: which entrypoint extensions each runtime accepts. */
const RUNTIMES: Record<string, string[]> = {
  "python-3.12": [".py"],
  "node-22": [".js", ".mjs", ".cjs"],
};

/** Mirrors BundleManifestRules.ENTRYPOINT. */
const ENTRYPOINT = /^[A-Za-z0-9_][A-Za-z0-9_./-]*$/;

const checkSchema = new Ajv({ allErrors: true, strict: false }).compile(schema);

export interface Validation {
  problems: string[];
  /** The packed project, only when there are no problems. */
  bundle: PackedBundle | null;
}

/** Fast feedback only: the backend's check at finalize is final. */
export function validateProject(dir: string, limits: Limits = LIMITS): Validation {
  const manifestPath = join(dir, "manifest.json");
  if (!existsSync(manifestPath)) {
    return { problems: ["manifest.json is missing from the project root"], bundle: null };
  }
  const raw = readFileSync(manifestPath);
  const problems: string[] = [];
  if (raw.length > limits.maxManifestBytes) {
    problems.push(`manifest.json is ${raw.length} bytes; the limit is ${limits.maxManifestBytes} (256 KiB)`);
  }
  let manifest: Record<string, unknown>;
  try {
    manifest = JSON.parse(raw.toString("utf8"));
  } catch (e) {
    return { problems: [...problems, `manifest.json is not valid JSON: ${(e as Error).message}`], bundle: null };
  }

  if (!checkSchema(manifest)) {
    for (const error of checkSchema.errors ?? []) {
      problems.push(`manifest.json ${error.instancePath || "/"} ${error.message}`);
    }
  }

  const files = listFiles(dir);
  problems.push(...bundleRules(manifest, files));

  let bundle: PackedBundle | null = null;
  if (files.length > limits.maxEntries) {
    problems.push(`the project has more than ${limits.maxEntries} files (${files.length}); exclude some in ${IGNORE_FILE}`);
  } else {
    bundle = pack(dir, files);
    if (bundle.bytes.length > limits.maxBundleBytes) {
      problems.push(`the packed bundle is ${bundle.bytes.length} bytes; the limit is ${limits.maxBundleBytes}. Exclude large files in ${IGNORE_FILE}`);
    }
  }
  return { problems, bundle: problems.length === 0 ? bundle : null };
}

/** Mirrors BundleManifestRules, the entrypoint-in-bundle check, and the build recipe's lockfile requirement. */
function bundleRules(manifest: Record<string, unknown>, files: string[]): string[] {
  const problems: string[] = [];
  const { runtime, entrypoint } = manifest;
  const extensions = typeof runtime === "string" ? RUNTIMES[runtime] : undefined;

  if (runtime === undefined) {
    problems.push("runtime is required (python-3.12 or node-22)");
  } else if (!extensions) {
    problems.push("runtime must be python-3.12 or node-22");
  }

  if (typeof entrypoint !== "string" || entrypoint.trim() === "") {
    problems.push("entrypoint is required");
  } else if (!ENTRYPOINT.test(entrypoint) || entrypoint.includes("//") || entrypoint.split("/").includes("..")) {
    problems.push("entrypoint must be a relative path inside the bundle (letters, digits, '_', '-', '.', '/')");
  } else {
    if (extensions && !extensions.some((extension) => entrypoint.endsWith(extension))) {
      problems.push(`entrypoint ${entrypoint} does not match runtime ${String(runtime)}`);
    }
    if (!files.includes(entrypoint)) {
      problems.push(`entrypoint ${entrypoint} is not in the bundle (missing, or excluded by ${IGNORE_FILE})`);
    }
  }

  if (runtime === "node-22" && files.includes("package.json") && !files.includes("package-lock.json")) {
    problems.push("package-lock.json is required next to package.json");
  }
  return problems;
}
