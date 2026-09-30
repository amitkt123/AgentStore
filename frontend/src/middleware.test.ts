import { NextRequest } from "next/server";
import { vi } from "vitest";
import { middleware } from "./middleware";

function jwt(exp: number) {
  const b64 = (o: object) => Buffer.from(JSON.stringify(o)).toString("base64url");
  return `${b64({ alg: "HS256" })}.${b64({ sub: "u", orgId: "o", role: "BUYER_ADMIN", exp })}.sig`;
}
const nowSec = () => Math.floor(Date.now() / 1000);
const req = (path: string, cookies: Record<string, string> = {}) => {
  const r = new NextRequest(`http://app${path}`);
  Object.entries(cookies).forEach(([k, v]) => r.cookies.set(k, v));
  return r;
};

beforeEach(() => { process.env.BACKEND_URL = "http://be"; });

it("lets public routes through without a session", async () => {
  const res = await middleware(req("/login"));
  expect(res.status).toBe(200);
});

it("redirects to login with next when there is no session", async () => {
  const res = await middleware(req("/runs"));
  expect(res.status).toBe(307);
  expect(res.headers.get("location")).toBe("http://app/login?next=%2Fruns");
});

it("refreshes an expired access token using the refresh cookie", async () => {
  const fresh = jwt(nowSec() + 900);
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ token: fresh, refreshToken: "r2" }), { status: 200 })));
  const res = await middleware(req("/runs", { karya_session: jwt(nowSec() - 5), karya_refresh: "r1" }));
  expect(res.status).toBe(200);
  expect(res.cookies.get("karya_session")?.value).toBe(fresh);
  expect(res.cookies.get("karya_refresh")?.value).toBe("r2");
});

it("sends the user to login and clears cookies when refresh fails", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 401 })));
  const res = await middleware(req("/runs", { karya_session: jwt(nowSec() - 5), karya_refresh: "bad" }));
  expect(res.status).toBe(307);
  expect(res.cookies.get("karya_session")?.value).toBe("");
});

it("lets an emailed approval link through without a session, but not the signed-in gate route", async () => {
  expect((await middleware(req("/approve/abc123"))).status).toBe(200);
  const gated = await middleware(req("/approve/R-1/g1"));
  expect(gated.status).toBe(307);
});
