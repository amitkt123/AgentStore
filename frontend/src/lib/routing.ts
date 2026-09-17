import type { SessionClaims } from "./session";
import { roleHome } from "./session";
import type { UserRole } from "./types";

const ROLE_PREFIXES: [string, UserRole[]][] = [
  ["/dashboard", ["DEVELOPER"]],
  ["/review", ["ADMIN"]],
  ["/catalog", ["BUYER_ADMIN", "BUYER_APPROVER"]],
  ["/agents", ["BUYER_ADMIN", "BUYER_APPROVER"]],
  ["/connect", ["BUYER_ADMIN", "BUYER_APPROVER"]],
  ["/runs", ["BUYER_ADMIN", "BUYER_APPROVER"]],
  ["/billing", ["BUYER_ADMIN"]],
];

export function resolveRedirect(pathname: string, claims: SessionClaims | null): string | null {
  if (pathname.startsWith("/login") || pathname.startsWith("/signup")) return null;
  if (!claims) return "/login";
  const match = ROLE_PREFIXES.find(([prefix]) => pathname.startsWith(prefix));
  if (match && !match[1].includes(claims.role)) return roleHome(claims.role);
  return null;
}
