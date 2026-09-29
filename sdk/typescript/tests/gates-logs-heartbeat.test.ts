import { describe, expect, it } from "vitest";
import { GateRejectedError } from "../src/errors.js";
import { Gates } from "../src/gates.js";
import { Heartbeat } from "../src/heartbeat.js";
import { Logger } from "../src/logs.js";
import { fakeApi } from "./helpers.js";

const gateApi = (...polls: unknown[]) => fakeApi({ createGate: { gateId: "g-1", status: "PENDING" }, getGate: polls });

describe("Gates", () => {
  it("polls through PENDING until approved", async () => {
    const f = gateApi({ status: "PENDING" }, { status: "PENDING" }, { status: "APPROVED", note: "ok" });
    const d = await new Gates(f.api).request({ action: "a", summary: "s", payload: { rows: 2 }, affects: ["journal_entries"] });
    expect(d).toMatchObject({ approved: true, status: "APPROVED", note: "ok", gateId: "g-1" });
    expect(f.names()).toEqual(["createGate", "getGate", "getGate", "getGate"]);
    expect(f.calls[0][1][0]).toEqual({ action: "a", summary: "s", payload: { rows: 2 }, affectsResources: ["journal_entries"] });
    expect(f.calls[1][1]).toEqual(["g-1", 25]);
  });

  it("does not poll when decided at creation", async () => {
    const f = fakeApi({ createGate: { gateId: "g", status: "APPROVED" } });
    expect((await new Gates(f.api).request({ action: "a", summary: "s", payload: {} })).approved).toBe(true);
    expect(f.names()).toEqual(["createGate"]);
  });

  it("returns a rejected decision by default and throws with raiseOnReject", async () => {
    const d = await new Gates(gateApi({ status: "REJECTED", note: "no" }).api).request({ action: "a", summary: "s", payload: {} });
    expect(d.approved).toBe(false);
    await expect(new Gates(gateApi({ status: "REJECTED", note: "no" }).api).request({ action: "a", summary: "s", payload: {}, raiseOnReject: true }))
      .rejects.toMatchObject({ note: "no", status: "REJECTED" });
    await expect(new Gates(gateApi({ status: "EXPIRED" }).api).request({ action: "a", summary: "s", payload: {}, raiseOnReject: true }))
      .rejects.toBeInstanceOf(GateRejectedError);
  });
});

describe("Logger", () => {
  it("buffers and flushes on size", async () => {
    const f = fakeApi();
    const log = new Logger(f.api, { flushSize: 3, flushIntervalMs: 1e9 });
    log.info("a"); log.warn("b");
    expect(f.calls).toEqual([]);
    log.error("c", { code: 7 });
    await log.flush();
    expect(f.names()).toEqual(["logs"]);
    expect(f.calls[0][1][0]).toEqual([{ level: "info", message: "a" }, { level: "warn", message: "b" }, { level: "error", message: "c", fields: { code: 7 } }]);
  });

  it("flushes on the time threshold and on explicit flush; empty flush is a no-op", async () => {
    let now = 0;
    const f = fakeApi();
    const log = new Logger(f.api, { flushSize: 100, flushIntervalMs: 2000, clock: () => now });
    log.info("a"); now = 5000; log.info("b");
    await log.flush();
    expect(f.names()).toEqual(["logs"]);
    log.info("c"); await log.flush(); await log.flush();
    expect(f.names()).toEqual(["logs", "logs"]);
  });

  it("truncates long messages and stringifies non-JSON fields", async () => {
    const f = fakeApi();
    const log = new Logger(f.api);
    log.error("x".repeat(9000), { at: new Date("2026-01-01T00:00:00Z"), big: 10n });
    await log.flush();
    const entry = (f.calls[0][1][0] as any[])[0];
    expect(entry.message).toHaveLength(4000);
    expect(entry.fields).toEqual({ at: "2026-01-01T00:00:00.000Z", big: "10" });
  });

  it("swallows a failed flush", async () => {
    const log = new Logger(fakeApi({ logs: [new Error("down")] }).api);
    log.info("a");
    await expect(log.flush()).resolves.toBeUndefined();
  });
});

describe("Heartbeat", () => {
  it("beats until stopped and survives errors", async () => {
    const f = fakeApi({ heartbeat: [new Error("blip")] });
    const hb = new Heartbeat(f.api, 10);
    hb.start();
    await new Promise((r) => setTimeout(r, 120));
    hb.stop();
    const n = f.calls.length;
    expect(n).toBeGreaterThanOrEqual(3);
    await new Promise((r) => setTimeout(r, 50));
    expect(f.calls.length).toBe(n);
  });

  it("stop is safe before start and twice", () => {
    const hb = new Heartbeat(fakeApi().api, 10);
    hb.stop(); hb.start(); hb.stop(); hb.stop();
  });
});
