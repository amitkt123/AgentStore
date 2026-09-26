import { accessSync, constants, readdirSync } from "node:fs";
import { basename } from "node:path";
import { ApiClient } from "../api.js";
import { requireCredentials } from "../config.js";
import type { Context } from "../context.js";
import { CliError } from "../errors.js";
import { PROJECT_FILE } from "../project.js";
import { scaffoldFiles, type ScaffoldRuntime, writeScaffold } from "../scaffold.js";

export interface InitOptions {
  /** "typescript" is an alias of "node": the scaffold is Node 22 JavaScript. */
  runtime: ScaffoldRuntime | "typescript";
  name?: string;
  category?: string;
  description?: string;
}

const HARMLESS = new Set([".git", ".DS_Store"]);

interface AgentResponse {
  id: string;
}

export async function init(
  ctx: Context,
  opts: InitOptions,
  write: (dir: string, files: Record<string, string>) => void = writeScaffold,
): Promise<number> {
  const dir = ctx.cwd;

  // Everything that can be checked without the network is checked first, so a refusal never leaves an orphan agent.
  const leftovers = readdirSync(dir).filter((entry) => !HARMLESS.has(entry));
  if (leftovers.length > 0) {
    throw new CliError(`This directory is not empty${leftovers.includes(PROJECT_FILE) ? ` (it already has ${PROJECT_FILE})` : ""}. Run \`agentstore init\` in a new, empty directory.`);
  }
  try {
    accessSync(dir, constants.W_OK);
  } catch {
    throw new CliError(`${dir} is not writable.`);
  }
  const credentials = requireCredentials(ctx);

  const name = opts.name ?? (await ctx.ask("Agent name:", basename(dir)));
  const category = opts.category ?? (await ctx.ask("Category:", "general"));
  const description = opts.description ?? (await ctx.ask("One-line description:", ""));
  const runtime: ScaffoldRuntime = opts.runtime === "python" ? "python" : "node";
  if (opts.runtime === "typescript") {
    ctx.out("Note: --typescript scaffolds Node 22 JavaScript (.mjs); the build has no TypeScript step yet.");
  }

  const agent = await new ApiClient(credentials, ctx.fetch).post<AgentResponse>("/api/v1/agents", { name, category, description });

  try {
    write(dir, scaffoldFiles(runtime, name, agent.id));
  } catch (e) {
    throw new CliError(
      `Agent ${agent.id} was created, but writing the project failed: ${(e as Error).message}\n`
      + `Do not run init again. Put this line in ${PROJECT_FILE} to use the agent: agentId: ${agent.id}`);
  }
  ctx.out(`Created agent ${agent.id} in ${dir}.`);
  ctx.out(`Next: edit agent/, then run \`agentstore validate\` and \`agentstore push --wait\`.`);
  return 0;
}
