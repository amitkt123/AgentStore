import { describe, expect, it } from "vitest";
import { Data } from "../src/data.js";
import { AgentStoreError } from "../src/errors.js";
import { Llm } from "../src/llm.js";
import { Usage } from "../src/usage.js";
import { fakeApi } from "./helpers.js";

const collect = async <T>(it: AsyncIterable<T>) => { const out: T[] = []; for await (const x of it) out.push(x); return out; };

describe("Data", () => {
  it("pages lazily until there is no cursor", async () => {
    const f = fakeApi({ records: [{ records: [{ a: 1 }, { a: 2 }], nextCursor: "c1" }, { records: [{ a: 3 }] }] });
    const it = new Data(f.api).records("bank_statement");
    expect(f.calls).toEqual([]);
    expect(await collect(it)).toEqual([{ a: 1 }, { a: 2 }, { a: 3 }]);
    expect(f.calls[1][1][1]).toBe("c1");
  });

  it("yields nothing for an empty resource", async () => {
    expect(await collect(new Data(fakeApi({ records: [{ records: [] }] }).api).records("e"))).toEqual([]);
  });

  it("throws instead of looping when the cursor never advances", async () => {
    const page = () => ({ records: [{ a: 1 }], nextCursor: "same" });
    const f = fakeApi({ records: [page(), page(), page()] });
    await expect(collect(new Data(f.api).records("stuck"))).rejects.toThrow(AgentStoreError);
  });

  it("reports accepted/staged and makes no call for an empty write", async () => {
    const f = fakeApi({ write: { accepted: 2, staged: true } });
    expect(await new Data(f.api).write("j", [{ x: 1 }, { x: 2 }])).toEqual({ accepted: 2, staged: true });
    const g = fakeApi();
    expect(await new Data(g.api).write("j", [])).toEqual({ accepted: 0, staged: false });
    expect(g.calls).toEqual([]);
  });

  it("file handle exposes the link and reads bytes", async () => {
    const f = fakeApi({ fileLink: { url: "https://s/f", expiresAt: "2026-01-01T00:00:00Z" }, fileContent: new Uint8Array([1, 2]) });
    const h = await new Data(f.api).file("bank_statement");
    expect([h.url, h.expiresAt]).toEqual(["https://s/f", "2026-01-01T00:00:00Z"]);
    expect(Array.from(await h.read())).toEqual([1, 2]);
  });
});

describe("Usage", () => {
  it("maps the response", async () => {
    const f = fakeApi({ usage: { charged: 2, totalCharged: 4, costCapRemaining: 96 } });
    expect(await new Usage(f.api).report(2, "u", "o1")).toEqual({ charged: 2, totalCharged: 4, costCapRemaining: 96 });
    expect(f.calls[0][1]).toEqual([2, "u", "o1"]);
  });
});

describe("Llm", () => {
  it("builds a camelCase body and wraps the response; text is the first text block", async () => {
    const f = fakeApi({ llmChat: {
      model: "m", stopReason: "end_turn", usage: { inputTokens: 3, outputTokens: 5 },
      content: [{ type: "tool_use", id: "t", name: "n", input: {} }, { type: "text", text: "hello" }],
    } });
    const res = await new Llm(f.api).chat({ model: "m", messages: [{ role: "user", content: "hi" }], maxTokens: 50, system: "s", temperature: 0.2 });
    expect(f.calls[0][1][0]).toEqual({ model: "m", messages: [{ role: "user", content: "hi" }], maxTokens: 50, system: "s", temperature: 0.2 });
    expect(res.text).toBe("hello");
    expect(res.stopReason).toBe("end_turn");
    expect(res.usage).toEqual({ inputTokens: 3, outputTokens: 5 });
  });

  it("text is empty when there is no text block", async () => {
    const f = fakeApi({ llmChat: { content: [{ type: "tool_use", id: "t", name: "n", input: {} }] } });
    expect((await new Llm(f.api).chat({ model: "m", messages: [{ role: "user", content: "x" }], maxTokens: 5 })).text).toBe("");
  });
});
