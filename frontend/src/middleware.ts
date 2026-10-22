import { NextRequest, NextResponse } from "next/server";
import { REFRESH_COOKIE, SESSION_COOKIE, cookieOptions, decodeToken, needsRefresh } from "@/lib/session";
import { gateTarget } from "@/lib/gate";

const PUBLIC = ["/login", "/signup", "/verify", "/forgot", "/reset"];
// An emailed approval link is /approve/{token}. The signed-in gate page is /approve/{run}/{gate}, which stays gated.
const APPROVAL_LINK = /^\/approve\/[^/]+$/;

export async function middleware(req: NextRequest) {
  const path = req.nextUrl.pathname;
  const token = req.cookies.get(SESSION_COOKIE)?.value;
  const refresh = req.cookies.get(REFRESH_COOKIE)?.value;
  const now = Math.floor(Date.now() / 1000);

  if (APPROVAL_LINK.test(path) || PUBLIC.some((p) => path === p || path.startsWith(p + "/"))) {
    // A signed-in user on /login or /signup goes to their own home; every other public page stays open.
    const to = !needsRefresh(token, now) ? gateTarget(decodeToken(token as string)?.role, path) : null;
    if (!to) return NextResponse.next();
    const url = req.nextUrl.clone();
    url.pathname = to;
    url.search = "";
    return NextResponse.redirect(url);
  }

  const gateFor = (tok: string) => gateTarget(decodeToken(tok)?.role, path);
  const redirectTo = (to: string) => {
    const url = req.nextUrl.clone();
    url.pathname = to;
    url.search = "";
    return NextResponse.redirect(url);
  };

  if (!needsRefresh(token, now)) {
    const to = gateFor(token as string);
    return to ? redirectTo(to) : NextResponse.next();
  }

  const toLogin = () => {
    const url = req.nextUrl.clone();
    url.pathname = "/login";
    url.search = `?next=${encodeURIComponent(path + req.nextUrl.search)}`;
    const res = NextResponse.redirect(url);
    res.cookies.set(SESSION_COOKIE, "", { ...cookieOptions(), maxAge: 0 });
    res.cookies.set(REFRESH_COOKIE, "", { ...cookieOptions(), maxAge: 0 });
    return res;
  };

  if (!refresh) return toLogin();

  const r = await fetch(`${process.env.BACKEND_URL}/api/v1/auth/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken: refresh }),
  }).catch(() => null);
  if (!r || !r.ok) return toLogin();

  const next = (await r.json()) as { token: string; refreshToken?: string | null };
  // Make the new token visible to this request's server components, and persist it for the browser.
  const headers = new Headers(req.headers);
  const jar = new Map(req.cookies.getAll().map((c) => [c.name, c.value]));
  jar.set(SESSION_COOKIE, next.token);
  if (next.refreshToken) jar.set(REFRESH_COOKIE, next.refreshToken);
  headers.set("cookie", [...jar].map(([k, v]) => `${k}=${v}`).join("; "));
  const to = gateFor(next.token);
  const res = to ? redirectTo(to) : NextResponse.next({ request: { headers } });
  res.cookies.set(SESSION_COOKIE, next.token, cookieOptions());
  if (next.refreshToken) res.cookies.set(REFRESH_COOKIE, next.refreshToken, cookieOptions(60 * 60 * 24 * 30));
  return res;
}

export const config = { matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"] };
