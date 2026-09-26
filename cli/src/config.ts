import { chmodSync, existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import type { Context } from "./context.js";
import { CliError } from "./errors.js";

export interface Credentials {
  apiKey: string;
  apiUrl: string;
}

export const DEFAULT_API_URL = "http://localhost:8080";

export function credentialsPath(home: string): string {
  return join(home, ".config", "agentstore", "credentials");
}

export function normalizeUrl(url: string): string {
  return url.replace(/\/+$/, "");
}

/** The environment overrides the file, so CI can supply a key without a login. */
export function loadCredentials(ctx: Context): Credentials | null {
  const file = credentialsPath(ctx.home);
  let stored: Partial<Credentials> = {};
  if (existsSync(file)) {
    if ((statSync(file).mode & 0o077) !== 0) {
      ctx.err(`Warning: ${file} is readable by other users; run: chmod 600 ${file}`);
    }
    try {
      stored = JSON.parse(readFileSync(file, "utf8"));
    } catch {
      throw new CliError(`${file} is not valid JSON; run \`agentstore login\` again.`);
    }
  }
  const apiKey = ctx.env.AGENTSTORE_API_KEY || stored.apiKey;
  if (!apiKey) {
    return null;
  }
  return { apiKey, apiUrl: normalizeUrl(ctx.env.AGENTSTORE_API_URL || stored.apiUrl || DEFAULT_API_URL) };
}

export function requireCredentials(ctx: Context): Credentials {
  const credentials = loadCredentials(ctx);
  if (!credentials) {
    throw new CliError("Not logged in. Run `agentstore login`, or set AGENTSTORE_API_KEY.");
  }
  return credentials;
}

export function saveCredentials(ctx: Context, credentials: Credentials): void {
  const file = credentialsPath(ctx.home);
  mkdirSync(dirname(file), { recursive: true, mode: 0o700 });
  chmodSync(dirname(file), 0o700);
  writeFileSync(file, JSON.stringify({ apiKey: credentials.apiKey, apiUrl: normalizeUrl(credentials.apiUrl) }, null, 2) + "\n",
    { mode: 0o600 });
  chmodSync(file, 0o600);   // the mode above only applies when the file is created
}
