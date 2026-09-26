import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { parse } from "yaml";
import { CliError } from "./errors.js";

export const PROJECT_FILE = "agentstore.yaml";

export interface ProjectConfig {
  agentId: string;
  name?: string;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** The agent id ends up in request paths, so anything that is not a UUID is refused here. */
export function readProject(dir: string): ProjectConfig {
  const file = join(dir, PROJECT_FILE);
  if (!existsSync(file)) {
    throw new CliError(`No ${PROJECT_FILE} in ${dir}. Run \`agentstore init\` in an empty directory first.`);
  }
  const parsed: unknown = parse(readFileSync(file, "utf8"));
  const agentId = parsed && typeof parsed === "object" ? (parsed as { agentId?: unknown }).agentId : undefined;
  if (typeof agentId !== "string" || agentId === "") {
    throw new CliError(`${PROJECT_FILE} has no agentId.`);
  }
  if (!UUID.test(agentId)) {
    throw new CliError(`${PROJECT_FILE} has "${agentId}", which is not a valid agent id.`);
  }
  const name = (parsed as { name?: unknown }).name;
  return { agentId, name: typeof name === "string" ? name : undefined };
}
