import { parseArgs } from "node:util";
import { init } from "./commands/init.js";
import { login } from "./commands/login.js";
import { DEFAULT_TIMEOUT_SECONDS, push } from "./commands/push.js";
import { validateCommand } from "./commands/validate.js";
import type { Context } from "./context.js";
import { CliError } from "./errors.js";

const USAGE = `Usage: agentstore <command> [options]

Commands:
  login [--api-url URL]                 Save an API key (verified against the API first)
  init [--python|--node] [--name N] [--category C] [--description D]
                                        Create an agent and scaffold a project in an empty directory
  validate                              Check the project the way the backend will
  push [--wait] [--timeout SECONDS]     Upload the project and start a build
`;

type Values = Record<string, string | boolean | undefined>;

function parse(args: string[], options: Record<string, { type: "string" | "boolean" }>): Values {
  try {
    return parseArgs({ args, options, strict: true, allowPositionals: false }).values as Values;
  } catch (e) {
    throw new CliError(`${(e as Error).message}\n\n${USAGE}`);
  }
}

export const str = (values: Values, key: string): string | undefined =>
  typeof values[key] === "string" ? (values[key] as string) : undefined;

export const flag = (values: Values, key: string): boolean => values[key] === true;

export async function main(argv: string[], ctx: Context): Promise<number> {
  const [command, ...args] = argv;
  try {
    switch (command) {
      case "login": {
        const values = parse(args, { "api-url": { type: "string" } });
        return await login(ctx, { apiUrl: str(values, "api-url") });
      }
      case "init": {
        const values = parse(args, {
          python: { type: "boolean" }, node: { type: "boolean" }, typescript: { type: "boolean" },
          name: { type: "string" }, category: { type: "string" }, description: { type: "string" },
        });
        const chosen = [flag(values, "python"), flag(values, "node"), flag(values, "typescript")].filter(Boolean).length;
        if (chosen > 1) {
          throw new CliError(`Choose one of --python, --node or --typescript.\n\n${USAGE}`);
        }
        return await init(ctx, {
          runtime: flag(values, "node") ? "node" : flag(values, "typescript") ? "typescript" : "python",
          name: str(values, "name"),
          category: str(values, "category"),
          description: str(values, "description"),
        });
      }
      case "validate":
        parse(args, {});
        return await validateCommand(ctx);
      case "push": {
        const values = parse(args, { wait: { type: "boolean" }, timeout: { type: "string" } });
        const raw = str(values, "timeout");
        const timeoutSeconds = raw === undefined ? DEFAULT_TIMEOUT_SECONDS : Number(raw);
        if (!Number.isInteger(timeoutSeconds) || timeoutSeconds <= 0) {
          throw new CliError(`--timeout must be a whole number of seconds, got "${raw}".\n\n${USAGE}`);
        }
        return await push(ctx, { wait: flag(values, "wait"), timeoutSeconds });
      }
      case "help":
      case "--help":
      case "-h":
        ctx.out(USAGE);
        return 0;
      case undefined:
        ctx.err(USAGE);
        return 2;
      default:
        ctx.err(`Unknown command "${command}"\n\n${USAGE}`);
        return 2;
    }
  } catch (e) {
    if (e instanceof CliError) {
      ctx.err(e.message);
      return e.exitCode;
    }
    throw e;
  }
}
