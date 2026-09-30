import type { UserRole } from "./roles";

const BUYER_ROUTES = ["/home", "/store", "/runs", "/files", "/connect"];
const under = (path: string, prefix: string) => path === prefix || path.startsWith(prefix + "/");

// Developers live under /developer, everyone else stays out of it. Returns where to send the request, or null.
export function gateTarget(role: UserRole | undefined, path: string): string | null {
  if (!role) return null;
  if (role === "DEVELOPER" && BUYER_ROUTES.some((p) => under(path, p))) return "/developer/verification";
  if (role !== "DEVELOPER" && under(path, "/developer")) return "/home";
  return null;
}
