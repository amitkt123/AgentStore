export type UserRole = "BUYER_ADMIN" | "BUYER_MEMBER" | "DEVELOPER" | "ADMIN";

export function railFor(role: UserRole): "buyer" | "developer" | "admin" {
  if (role === "DEVELOPER") return "developer";
  if (role === "ADMIN") return "admin";
  return "buyer";
}
export const isBuyer = (role: UserRole) => role === "BUYER_ADMIN" || role === "BUYER_MEMBER";
export const canDecideGates = isBuyer;
export const canCreateConnections = (role: UserRole) => role === "BUYER_ADMIN";
