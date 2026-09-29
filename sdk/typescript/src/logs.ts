import type { Api } from "./api.js";

const MAX_MESSAGE = 4000;

export interface LoggerOptions { flushSize?: number; flushIntervalMs?: number; clock?: () => number }

export class Logger {
  private buffer: Array<{ level: string; message: string; fields?: Record<string, unknown> }> = [];
  private lastFlush: number;
  private inflight: Promise<void> = Promise.resolve();
  private readonly flushSize: number;
  private readonly intervalMs: number;
  private readonly clock: () => number;

  constructor(private readonly api: Api, opts: LoggerOptions = {}) {
    this.flushSize = opts.flushSize ?? 50;
    this.intervalMs = opts.flushIntervalMs ?? 2000;
    this.clock = opts.clock ?? Date.now;
    this.lastFlush = this.clock();
  }

  info(message: unknown, fields?: Record<string, unknown>): void { this.add("info", message, fields); }
  warn(message: unknown, fields?: Record<string, unknown>): void { this.add("warn", message, fields); }
  error(message: unknown, fields?: Record<string, unknown>): void { this.add("error", message, fields); }

  private add(level: string, message: unknown, fields?: Record<string, unknown>): void {
    const entry: { level: string; message: string; fields?: Record<string, unknown> } =
      { level, message: String(message).slice(0, MAX_MESSAGE) };
    if (fields && Object.keys(fields).length) {
      entry.fields = JSON.parse(JSON.stringify(fields, (_k, v) => (typeof v === "bigint" ? v.toString() : v)));
    }
    this.buffer.push(entry);
    if (this.buffer.length >= this.flushSize || this.clock() - this.lastFlush >= this.intervalMs) void this.flush();
  }

  /** Sends everything buffered; resolves once all in-flight batches have settled. Never rejects. */
  flush(): Promise<void> {
    const batch = this.buffer;
    this.buffer = [];
    this.lastFlush = this.clock();
    if (batch.length) {
      this.inflight = this.inflight.then(() =>
        this.api.logs(batch).catch((e) => { console.error(`agentstore: dropped ${batch.length} log entries: ${e}`); }));
    }
    return this.inflight;
  }
}
