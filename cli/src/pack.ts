import { createHash } from "node:crypto";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { zipSync } from "fflate";
import ignore from "ignore";

export const IGNORE_FILE = ".agentstoreignore";

/** The same limits the backend enforces (BundleService, BundleInspector), so a too-big project fails before the upload. */
export const LIMITS = {
  maxBundleBytes: 100 * 1024 * 1024,
  maxEntries: 20_000,
  maxManifestBytes: 256 * 1024,
};

export type Limits = typeof LIMITS;

export interface PackedBundle {
  bytes: Uint8Array;
  sha256: string;
  entries: string[];
}

const ALWAYS_IGNORED = [".git/", "/agentstore.yaml"];

/** Relative POSIX paths of the files that go into the bundle, sorted. Symlinks are skipped, never followed. */
export function listFiles(dir: string): string[] {
  const rules = ignore().add(ALWAYS_IGNORED);
  const ignoreFile = join(dir, IGNORE_FILE);
  if (existsSync(ignoreFile)) {
    rules.add(readFileSync(ignoreFile, "utf8"));
  }
  const found: string[] = [];
  const walk = (relative: string): void => {
    for (const entry of readdirSync(join(dir, relative), { withFileTypes: true })) {
      const path = relative ? `${relative}/${entry.name}` : entry.name;
      if (entry.isSymbolicLink()) {
        continue;
      }
      if (entry.isDirectory()) {
        if (!rules.ignores(`${path}/`)) {
          walk(path);
        }
      } else if (entry.isFile() && !rules.ignores(path)) {
        found.push(path);
      }
    }
  };
  walk("");
  return found.sort();
}

/** A zip with a fixed timestamp, so the same project always packs to the same bytes and the same sha256. */
export function pack(dir: string, files: string[] = listFiles(dir)): PackedBundle {
  const data: Record<string, Uint8Array> = {};
  for (const path of files) {
    data[path] = readFileSync(join(dir, path));
  }
  const bytes = zipSync(data, { level: 6, mtime: new Date("2000-01-01T00:00:00Z") });
  return { bytes, sha256: createHash("sha256").update(bytes).digest("hex"), entries: files };
}
