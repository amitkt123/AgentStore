import type { UserRole } from "../roles";

export type OrgType = "BUYER" | "DEVELOPER";
export type SignupSide = "BUYER" | "DEVELOPER";

export interface AuthResponse { token: string; refreshToken: string | null }
export interface Membership { orgId: string; orgName: string; orgType: OrgType; role: UserRole; title: string | null }
export interface MeResponse {
  userId: string; email: string; fullName: string; emailVerified: boolean; activeOrgId: string; memberships: Membership[];
}
