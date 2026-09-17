import { cookies } from "next/headers";
import type { UserRole } from "./types";

export const SESSION_COOKIE = "karya_session";

export interface SessionClaims {
  userId: string;
  orgId: string;
  role: UserRole;
}

export function decodeSessionToken(token: string): SessionClaims | null {
  try {
    const payloadSegment = token.split(".")[1];
    const base64 = payloadSegment.replace(/-/g, "+").replace(/_/g, "/");
    const json = JSON.parse(atob(base64)) as { sub: string; orgId: string; role: UserRole };
    if (!json.sub || !json.orgId || !json.role) return null;
    return { userId: json.sub, orgId: json.orgId, role: json.role };
  } catch {
    return null;
  }
}

export async function getSession(): Promise<SessionClaims | null> {
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  if (!token) return null;
  return decodeSessionToken(token);
}

export function roleHome(role: UserRole): string {
  if (role === "DEVELOPER") return "/dashboard";
  if (role === "ADMIN") return "/review";
  return "/catalog";
}
