import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { parse, stringify } from "yaml";
import { CliError } from "./errors.js";

export const PROFILES_DIR = "fixtures/profiles";

export interface ProfileYamlBinding {
  resource: string;
  fixture: string;
}

export interface ProfileYamlGatePolicy {
  default: string;
  actions?: Record<string, string>;
}

export interface ProfileYaml {
  name: string;
  description?: string;
  input?: Record<string, unknown>;
  gatePolicy?: ProfileYamlGatePolicy;
  llmMode?: string;
  spendCap?: number;
  bindings?: ProfileYamlBinding[];
}

export interface ProfileSyncEntry extends ProfileYaml {
  contentHash: string;
}

/** Every fixtures/profiles/*.yaml file in the project, parsed and hashed over its raw bytes. */
export function readProfileFiles(dir: string): ProfileSyncEntry[] {
  const profilesDir = join(dir, PROFILES_DIR);
  if (!existsSync(profilesDir)) {
    return [];
  }
  return readdirSync(profilesDir)
    .filter((f) => f.endsWith(".yaml") || f.endsWith(".yml"))
    .map((f) => {
      const raw = readFileSync(join(profilesDir, f));
      const parsed = (parse(raw.toString("utf8")) ?? {}) as ProfileYaml;
      return { ...parsed, contentHash: createHash("sha256").update(raw).digest("hex") };
    });
}

/** Overwrites `fixtures/profiles/<name>.yaml` unconditionally — a developer with a conflicting local edit should
 * check it into git before pulling. */
export function writeProfileFile(dir: string, profile: ProfileYaml): void {
  if (profile.name.includes("/") || profile.name.includes("\\") || profile.name === "." || profile.name === "..") {
    throw new CliError(`Refusing to write a profile whose name is not a plain file name: "${profile.name}"`);
  }
  const profilesDir = join(dir, PROFILES_DIR);
  mkdirSync(profilesDir, { recursive: true });
  writeFileSync(join(profilesDir, `${profile.name}.yaml`), stringify(profile));
}
