import type { UserRole } from "./roles";

const BUYER_ROUTES = ["/home", "/store", "/runs", "/files", "/connect"];
const under = (path: string, prefix: string) => path === prefix || path.startsWith(prefix + "/");

// Developers live under /developer, everyone else stays out of it; /admin is for ADMIN only. Returns where to send the request, or null.
export function homeFor(role: UserRole): string {
  if (role === "DEVELOPER") return "/developer/agents";
  if (role === "ADMIN") return "/admin/money";
  return "/home";
}

export function gateTarget(role: UserRole | undefined, path: string): string | null {
  if (!role) return null;
  if (under(path, "/login") || under(path, "/signup")) return homeFor(role);
  if (role === "DEVELOPER" && BUYER_ROUTES.some((p) => under(path, p))) return "/developer/verification";
  if (role !== "DEVELOPER" && under(path, "/developer")) return "/home";
  if (role !== "ADMIN" && under(path, "/admin")) return role === "DEVELOPER" ? "/developer/verification" : "/home";
  return null;
}

/** /login?expired=1 is where a request the backend rejected (401) lands: the session cookies are stale, so clear them and show the form. */
export const isExpiredLogin = (path: string, params: URLSearchParams): boolean => path === "/login" && params.has("expired");
