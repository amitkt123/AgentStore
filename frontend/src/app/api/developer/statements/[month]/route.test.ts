import { beforeEach, expect, it, vi } from "vitest";
vi.mock("next/headers", () => ({ cookies: () => ({ get: () => ({ value: "tok" }) }) }));
import { GET } from "./route";

beforeEach(() => { process.env.BACKEND_URL = "http://be"; });

it("rejects a malformed month without calling the backend", async () => {
  const f = vi.fn();
  vi.stubGlobal("fetch", f);
  const res = await GET(new Request("http://x"), { params: { month: "2026-13" } });
  expect(res.status).toBe(400);
  expect(f).not.toHaveBeenCalled();
});

it("streams the CSV with the session bearer token and backend headers", async () => {
  const f = vi.fn().mockResolvedValue(new Response("a,b\n1,2\n", {
    status: 200,
    headers: { "Content-Type": "text/csv", "Content-Disposition": 'attachment; filename="statement-2026-09.csv"' },
  }));
  vi.stubGlobal("fetch", f);
  const res = await GET(new Request("http://x"), { params: { month: "2026-09" } });
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/developer/statements/2026-09/download");
  expect(f.mock.calls[0][1].headers.Authorization).toBe("Bearer tok");
  expect(res.status).toBe(200);
  expect(res.headers.get("Content-Type")).toBe("text/csv");
  expect(res.headers.get("Content-Disposition")).toBe('attachment; filename="statement-2026-09.csv"');
  expect(await res.text()).toBe("a,b\n1,2\n");
});

it("passes backend errors through with their status", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: "Forbidden" }), { status: 403 })));
  const res = await GET(new Request("http://x"), { params: { month: "2026-09" } });
  expect(res.status).toBe(403);
  expect(await res.json()).toEqual({ message: "Forbidden" });
});
