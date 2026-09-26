import { homedir } from "node:os";
import { createInterface } from "node:readline/promises";
import { CliError } from "./errors.js";

export interface Context {
  cwd: string;
  env: NodeJS.ProcessEnv;
  home: string;
  out(line: string): void;
  err(line: string): void;
  ask(question: string, fallback?: string): Promise<string>;
  readSecret(question: string): Promise<string>;
  sleep(ms: number): Promise<void>;
  now(): number;
  fetch: typeof fetch;
}

export function createContext(): Context {
  return {
    cwd: process.cwd(),
    env: process.env,
    home: homedir(),
    out: (line) => { process.stdout.write(line + "\n"); },
    err: (line) => { process.stderr.write(line + "\n"); },
    ask,
    readSecret,
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
    now: () => Date.now(),
    fetch: globalThis.fetch,
  };
}

async function ask(question: string, fallback?: string): Promise<string> {
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  try {
    const answer = (await rl.question(fallback ? `${question} [${fallback}] ` : `${question} `)).trim();
    return answer || fallback || "";
  } finally {
    rl.close();
  }
}

/** Reads a line without echoing it. When stdin is not a terminal (a pipe), it reads one plain line. */
async function readSecret(question: string): Promise<string> {
  process.stdout.write(question + " ");
  const stdin = process.stdin;
  if (!stdin.isTTY) {
    const rl = createInterface({ input: stdin });
    try {
      for await (const line of rl) {
        return line.trim();
      }
      return "";
    } finally {
      rl.close();
    }
  }
  return new Promise((resolve, reject) => {
    let value = "";
    const finish = () => {
      stdin.off("data", onData);
      stdin.setRawMode(false);
      stdin.pause();
      process.stdout.write("\n");
    };
    const onData = (chunk: string) => {
      for (const ch of chunk) {
        if (ch === "\r" || ch === "\n") {
          finish();
          resolve(value.trim());
          return;
        }
        if (ch === "\u0003") {
          finish();
          reject(new CliError("Cancelled."));
          return;
        }
        value = ch === "\u007f" || ch === "\b" ? value.slice(0, -1) : value + ch;
      }
    };
    stdin.setRawMode(true);
    stdin.setEncoding("utf8");
    stdin.resume();
    stdin.on("data", onData);
  });
}
