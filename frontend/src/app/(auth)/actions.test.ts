import { vi } from "vitest";

const set = vi.hoisted(() => vi.fn());
vi.mock("next/headers", () => ({ cookies: () => ({ set, get: () => undefined }) }));
const redirect = vi.hoisted(() => vi.fn((url: string) => { throw new Error(`NEXT_REDIRECT:${url}`); }));
vi.mock("next/navigation", () => ({ redirect }));

import { loginAction } from "./actions";
import { safeNext } from "@/lib/safeNext";

function form(values: Record<string, string>) {
  const fd = new FormData();
  Object.entries(values).forEach(([k, v]) => fd.set(k, v));
  return fd;
}

beforeEach(() => { set.mockClear(); redirect.mockClear(); process.env.BACKEND_URL = "http://be"; });

it("accepts only same-site next paths", () => {
  expect(safeNext("/runs")).toBe("/runs");
  expect(safeNext("//evil.com")).toBe("/home");
  expect(safeNext("https://evil.com")).toBe("/home");
  expect(safeNext(null)).toBe("/home");
});

it("stores the session and redirects to next on success", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ token: "T", refreshToken: "R" }), { status: 200 })));
  await expect(loginAction({}, form({ email: "a@b.co", password: "pw", remember: "on", next: "/runs" }))).rejects.toThrow("NEXT_REDIRECT:/runs");
  expect(set).toHaveBeenCalledWith("karya_session", "T", expect.any(Object));
  expect(set).toHaveBeenCalledWith("karya_refresh", "R", expect.any(Object));
});

it("returns a friendly error on 401", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 401 })));
  await expect(loginAction({}, form({ email: "a@b.co", password: "bad" }))).resolves.toEqual({ error: "That email and password don't match." });
});

it("shows the backend message for a sign-in problem", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: "NO_WORKSPACE", message: "You have no workspace" }), { status: 422 })));
  await expect(loginAction({}, form({ email: "a@b.co", password: "pw" }))).resolves.toEqual({ error: "You have no workspace" });
});
