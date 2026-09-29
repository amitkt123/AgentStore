import type { Api } from "./api.js";

export class Heartbeat {
  private timer?: ReturnType<typeof setInterval>;

  constructor(private readonly api: Api, private readonly intervalMs = 15_000) {}

  start(): void {
    if (this.timer) return;
    this.timer = setInterval(() => { this.api.heartbeat().catch(() => {}); }, this.intervalMs);
    this.timer.unref();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = undefined;
  }
}
