import { cookies } from "next/headers";
import type { UserRole } from "./roles";

export const SESSION_COOKIE = "karya_session";
export const REFRESH_COOKIE = "karya_refresh";

export interface Claims {
  userId: string;
  orgId: string;
  role: UserRole;
  exp: number;
}

export function decodeToken(token: string): Claims | null {
  try {
    const seg = token.split(".")[1];
    const json = JSON.parse(atob(seg.replace(/-/g, "+").replace(/_/g, "/"))) as {
      sub?: string; orgId?: string; role?: UserRole; exp?: number;
    };
    if (!json.sub || !json.orgId || !json.role) return null;
    return { userId: json.sub, orgId: json.orgId, role: json.role, exp: json.exp ?? 0 };
  } catch {
    return null;
  }
}

export function needsRefresh(token: string | undefined, nowSec: number): boolean {
  if (!token) return true;
  const claims = decodeToken(token);
  if (!claims) return true;
  return claims.exp - nowSec < 30;
}

export async function getSession(): Promise<Claims | null> {
  const token = cookies().get(SESSION_COOKIE)?.value;
  if (!token) return null;
  const claims = decodeToken(token);
  if (!claims || claims.exp <= Date.now() / 1000) return null;
  return claims;
}

export function cookieOptions(maxAgeSec?: number) {
  return {
    httpOnly: true,
    sameSite: "lax" as const,
    secure: process.env.NODE_ENV === "production",
    path: "/",
    ...(maxAgeSec ? { maxAge: maxAgeSec } : {}),
  };
}

type CookieWriter = { set: (name: string, value: string, opts?: object) => unknown };

export function writeSession(store: CookieWriter, s: { token: string; refreshToken?: string | null }) {
  store.set(SESSION_COOKIE, s.token, cookieOptions());
  if (s.refreshToken) store.set(REFRESH_COOKIE, s.refreshToken, cookieOptions(60 * 60 * 24 * 30));
}

export function clearSession(store: CookieWriter) {
  store.set(SESSION_COOKIE, "", { ...cookieOptions(), maxAge: 0 });
  store.set(REFRESH_COOKIE, "", { ...cookieOptions(), maxAge: 0 });
}
