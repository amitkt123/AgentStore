import { vi } from "vitest";

const store = new Map<string, string>();
vi.mock("next/headers", () => ({ cookies: () => ({ get: (k: string) => (store.has(k) ? { value: store.get(k) } : undefined) }) }));
const redirect = vi.hoisted(() => vi.fn(() => { throw new Error("NEXT_REDIRECT"); }));
vi.mock("next/navigation", () => ({ redirect }));

import { api, ApiError } from "./http";

beforeEach(() => {
  store.clear();
  process.env.BACKEND_URL = "http://be";
  redirect.mockClear();
});

it("sends the bearer token and parses JSON", async () => {
  store.set("karya_session", "tok");
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ ok: 1 }), { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
  await expect(api("/api/v1/x")).resolves.toEqual({ ok: 1 });
  expect(fetchMock.mock.calls[0][0]).toBe("http://be/api/v1/x");
  expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe("Bearer tok");
});

it("throws ApiError with the backend message and code", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: "DATASET_CHECK_FAILED", message: "Columns do not match" }), { status: 422 })));
  await expect(api("/api/v1/x")).rejects.toMatchObject({ status: 422, code: "DATASET_CHECK_FAILED", message: "Columns do not match" });
});

it("falls back to a generic message when the body is empty", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 403 })));
  const err = (await api("/api/v1/x").catch((e) => e)) as Error;
  expect(err).toBeInstanceOf(ApiError);
  expect(err.message).toBe("Request failed (403)");
});

it("redirects to login on 401", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 401 })));
  await expect(api("/api/v1/x")).rejects.toThrow("NEXT_REDIRECT");
  expect(redirect).toHaveBeenCalledWith("/login");
});

it("does not force a JSON content type on FormData", async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response("{}", { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
  const fd = new FormData();
  fd.set("file", new Blob(["a"]), "a.csv");
  await api("/api/v1/datasets", { method: "POST", body: fd });
  expect(fetchMock.mock.calls[0][1].headers["Content-Type"]).toBeUndefined();
});

it("keeps the extra fields of the error body on ApiError.details", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: "VERIFICATION_INCOMPLETE", message: "Complete all steps", missingSteps: ["PHONE"] }), { status: 400 })));
  const err = (await api("/api/v1/x").catch((e) => e)) as ApiError;
  expect(err.details.missingSteps).toEqual(["PHONE"]);
});

it("leaves details empty when the body is not JSON", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 500 })));
  const err = (await api("/api/v1/x").catch((e) => e)) as ApiError;
  expect(err.details).toEqual({});
});
