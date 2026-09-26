import { describe, expect, it } from "vitest";
import { ApiClient } from "../src/api.js";
import { CliError } from "../src/errors.js";
import { startServer } from "./helpers.js";

const client = (apiUrl: string) => new ApiClient({ apiKey: "ask_live_secret", apiUrl }, globalThis.fetch);

describe("ApiClient", () => {
  it("sends the key as a bearer token and parses JSON", async () => {
    const server = await startServer([{ method: "GET", path: "/api/v1/agents/mine", reply: { json: [{ agentId: "a1" }] } }]);
    expect(await client(server.url).get("/api/v1/agents/mine")).toEqual([{ agentId: "a1" }]);
    expect(server.requests[0].headers.authorization).toBe("Bearer ask_live_secret");
  });

  it("posts a JSON body", async () => {
    const server = await startServer([{ method: "POST", path: "/x", reply: { json: {} } }]);
    await client(server.url).post("/x", { a: 1 });
    expect(JSON.parse(server.requests[0].body.toString())).toEqual({ a: 1 });
    expect(server.requests[0].headers["content-type"]).toContain("application/json");
  });

  it("turns a 401 into a hint to log in again", async () => {
    const server = await startServer([{ method: "GET", path: "/x", reply: { status: 401 } }]);
    await expect(client(server.url).get("/x")).rejects.toThrow(/rejected or revoked.*agentstore login/s);
  });

  it("shows the backend's message verbatim for other failures", async () => {
    const server = await startServer([
      { method: "POST", path: "/x", reply: { status: 422, json: { code: "INVALID_BUNDLE", message: "Entrypoint a.py is not in the bundle" } } },
    ]);
    const error = (await client(server.url).post("/x").catch((e) => e)) as CliError;
    expect(error).toBeInstanceOf(CliError);
    expect(error.message).toContain("422");
    expect(error.message).toContain("Entrypoint a.py is not in the bundle");
    expect(error.exitCode).toBe(2);
  });

  it("joins an array of messages and tolerates a non-JSON body", async () => {
    const server = await startServer([
      { method: "GET", path: "/list", reply: { status: 400, json: ["a is bad", "b is bad"] } },
      { method: "GET", path: "/text", reply: { status: 500, text: "boom" } },
      { method: "GET", path: "/empty", reply: { status: 403 } },
    ]);
    await expect(client(server.url).get("/list")).rejects.toThrow("a is bad; b is bad");
    await expect(client(server.url).get("/text")).rejects.toThrow("boom");
    await expect(client(server.url).get("/empty")).rejects.toThrow(/403/);
  });

  it("reports an unreachable server in one line that names the URL", async () => {
    const error = (await client("http://127.0.0.1:1").get("/x").catch((e) => e)) as CliError;
    expect(error).toBeInstanceOf(CliError);
    expect(error.message).toContain("Could not reach http://127.0.0.1:1");
    expect(error.message).not.toContain("\n");
    expect(error.exitCode).toBe(2);
  });

  it("uploads to a signed URL without the API key and never echoes the URL", async () => {
    const server = await startServer([
      { method: "PUT", path: /^\/ok/, reply: { status: 200 } },
      { method: "PUT", path: /^\/denied/, reply: { status: 403 } },
    ]);
    await client(server.url).putBytes(`${server.url}/ok?X-Amz-Signature=topsecret`, new Uint8Array([1, 2, 3]));
    expect(server.requests[0].headers.authorization).toBeUndefined();
    expect([...server.requests[0].body]).toEqual([1, 2, 3]);

    const error = await client(server.url).putBytes(`${server.url}/denied?X-Amz-Signature=topsecret`, new Uint8Array([1])).catch((e) => e);
    expect(error.message).toContain("403");
    expect(error.message).not.toContain("topsecret");
  });

  it("reads text from a signed URL, or null when it is not there yet", async () => {
    const server = await startServer([{ method: "GET", path: "/log", reply: { text: "line one\n" } }]);
    expect(await client(server.url).getText(`${server.url}/log`)).toBe("line one\n");
    expect(await client(server.url).getText(`${server.url}/missing`)).toBeNull();
  });
});
