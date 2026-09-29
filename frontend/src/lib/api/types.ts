import type { UserRole } from "../roles";

export type OrgType = "BUYER" | "DEVELOPER";
export type SignupSide = "BUYER" | "DEVELOPER";

export interface AuthResponse { token: string; refreshToken: string | null }
export interface Membership { orgId: string; orgName: string; orgType: OrgType; role: UserRole; title: string | null }
export interface MeResponse {
  userId: string; email: string; fullName: string; emailVerified: boolean; activeOrgId: string; memberships: Membership[];
}

export type RunStatus =
  | "PENDING" | "PENDING_BUILD" | "RUNNING" | "AWAITING_APPROVAL" | "COMPLETED" | "FAILED" | "CANCELLED" | "TIMED_OUT" | "EXPIRED";
export type RunGroup = "needs_you" | "running" | "finished" | "stopped";
export type TerminalReason =
  | "FINISHED" | "GATE_REJECTED" | "GATE_EXPIRED" | "COST_CAP_REACHED" | "TIMED_OUT" | "SCOPE_VIOLATIONS"
  | "AGENT_FAILED" | "CANCELLED" | "SANDBOX_LOST";

export interface RunListItem {
  id: string; displayId: string; agent: string; version: string; status: RunStatus;
  terminalReason: TerminalReason | null; reachedBlock: string | null;
  startedAt: string | null; completedAt: string | null; charged: number; held: number;
  decidedBy: { name: string } | null;
}
export interface RunListResponse {
  items: RunListItem[]; counts: Record<RunGroup, number>; page: number; size: number; total: number;
}
export interface RunQuery { group?: RunGroup; agentId?: string; q?: string; from?: string; to?: string; page?: number; size?: number }

export interface ConnectionSummary {
  id: string; agentId: string; agentName: string; grantedScopes: string[]; spendCap: number; status: "ACTIVE" | "REVOKED";
}
