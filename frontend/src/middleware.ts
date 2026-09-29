import { NextRequest, NextResponse } from "next/server";
import { REFRESH_COOKIE, SESSION_COOKIE, cookieOptions, needsRefresh } from "@/lib/session";

const PUBLIC = ["/login", "/signup", "/verify", "/forgot", "/reset"];

export async function middleware(req: NextRequest) {
  const path = req.nextUrl.pathname;
  if (PUBLIC.some((p) => path === p || path.startsWith(p + "/"))) return NextResponse.next();

  const token = req.cookies.get(SESSION_COOKIE)?.value;
  const refresh = req.cookies.get(REFRESH_COOKIE)?.value;
  const now = Math.floor(Date.now() / 1000);

  if (!needsRefresh(token, now)) return NextResponse.next();

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
  const res = NextResponse.next({ request: { headers } });
  res.cookies.set(SESSION_COOKIE, next.token, cookieOptions());
  if (next.refreshToken) res.cookies.set(REFRESH_COOKIE, next.refreshToken, cookieOptions(60 * 60 * 24 * 30));
  return res;
}

export const config = { matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"] };
