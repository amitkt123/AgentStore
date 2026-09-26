import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { createServer, type IncomingMessage } from "node:http";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { afterEach } from "vitest";
import type { Context } from "../src/context.js";

export const AGENT_ID = "11111111-2222-4333-8444-555555555555";

const cleanups: Array<() => Promise<void> | void> = [];

afterEach(async () => {
  while (cleanups.length > 0) {
    await cleanups.pop()!();
  }
});

export function tempDir(): string {
  const dir = mkdtempSync(join(tmpdir(), "agentstore-cli-"));
  cleanups.push(() => rmSync(dir, { recursive: true, force: true }));
  return dir;
}

export function writeFiles(dir: string, files: Record<string, string>): void {
  for (const [path, content] of Object.entries(files)) {
    const full = join(dir, path);
    mkdirSync(dirname(full), { recursive: true });
    writeFileSync(full, content);
  }
}

export interface Recorded {
  method: string;
  path: string;
  headers: IncomingMessage["headers"];
  body: Buffer;
}

export interface Reply {
  status?: number;
  json?: unknown;
  text?: string;
}

export interface Route {
  method: string;
  path: string | RegExp;
  reply: Reply | ((request: Recorded) => Reply);
}

/** A real HTTP server on a random port. Unmatched requests get 404. Every request is recorded. */
export async function startServer(routes: Route[]): Promise<{ url: string; requests: Recorded[] }> {
  const requests: Recorded[] = [];
  const server = createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => {
      const recorded: Recorded = {
        method: req.method ?? "",
        path: req.url ?? "",
        headers: req.headers,
        body: Buffer.concat(chunks),
      };
      requests.push(recorded);
      const route = routes.find((r) =>
        r.method === recorded.method && (typeof r.path === "string" ? r.path === recorded.path : r.path.test(recorded.path)));
      if (!route) {
        res.writeHead(404).end();
        return;
      }
      const reply = typeof route.reply === "function" ? route.reply(recorded) : route.reply;
      res.writeHead(reply.status ?? 200, { "Content-Type": reply.json !== undefined ? "application/json" : "text/plain" });
      res.end(reply.json !== undefined ? JSON.stringify(reply.json) : (reply.text ?? ""));
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  cleanups.push(() => new Promise<void>((resolve) => server.close(() => resolve())));
  return { url: `http://127.0.0.1:${(server.address() as AddressInfo).port}`, requests };
}

export interface TestContext extends Context {
  outLines: string[];
  errLines: string[];
  sleeps: number[];
  answers: string[];
  secrets: string[];
}

/** A Context with a fake clock (sleep advances it), scripted answers, and an empty environment. */
export function makeContext(opts: {
  cwd?: string;
  home?: string;
  env?: Record<string, string>;
  answers?: string[];
  secrets?: string[];
} = {}): TestContext {
  let clock = 0;
  const ctx: TestContext = {
    cwd: opts.cwd ?? tempDir(),
    home: opts.home ?? tempDir(),
    env: opts.env ?? {},
    out: (line) => { ctx.outLines.push(line); },
    err: (line) => { ctx.errLines.push(line); },
    ask: async (_question, fallback) => ctx.answers.shift() || fallback || "",   // like the real prompt: empty means the default
    readSecret: async () => ctx.secrets.shift() ?? "",
    sleep: async (ms) => { clock += ms; ctx.sleeps.push(ms); },
    now: () => clock,
    fetch: globalThis.fetch,
    outLines: [],
    errLines: [],
    sleeps: [],
    answers: opts.answers ?? [],
    secrets: opts.secrets ?? [],
  };
  return ctx;
}
