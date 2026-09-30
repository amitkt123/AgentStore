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

export type BlockState = "DONE" | "WAITING" | "FAILED" | "SKIPPED" | "IDLE";
export interface FlowBlock { block: string; state: BlockState; metrics: Record<string, unknown> | null; gateId: string | null; flag: "UNDECLARED" | null }
export interface FlowView { blocks: FlowBlock[]; reachedBlock: string | null; spend: { charged: number; cap: number }; deadlineAt: string | null; blockedAttempts: number }
export type RunStepKind = "READ" | "WRITE" | "GATE" | "COST" | "COMPLETE" | "FAILED" | "TIMEOUT" | "COST_CAP_REACHED" | "DATA_READ" | "DATA_WRITE" | "WRITE_STAGED" | "GATE_REQUESTED" | "GATE_DECIDED" | "LLM_CALL" | "USAGE" | "SCOPE_VIOLATION" | "AGENT_LOG" | "CANCELLED" | "NETWORK_BLOCKED";
export interface RunStep { kind: RunStepKind; source?: "PLATFORM" | "AGENT"; detail: string; createdAt: string }
export interface RunDetail { id: string; displayId: string; status: RunStatus; terminalReason: TerminalReason | null; costCap: number; totalCharged: number; steps: RunStep[] }
export type GateStatus = "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED";
export interface GateDetail { gateId: string; action: string; summary: string; payload: unknown; status: GateStatus; expiresAt: string; estimatedUnits: number | null; costIfApproved: number | null; preview: { columns?: string[]; rows: Record<string, unknown>[]; totalRows: number; total?: number } | null; decidedVia: "WEB" | "EMAIL_LINK" | "SLACK_LINK" | null; decidedByUserId: string | null; decidedAt: string | null; note: string | null }
export interface RunSnapshot { detail: RunDetail; flow: FlowView; gates: GateDetail[] }
export type DataFormat = string;
export interface DatasetUsage { connectionId: string; resource: string; agent: string; version: string; access: string }
export interface Dataset { id: string; displayName: string; sourceLabel: string | null; format: DataFormat; rowCount: number | null; sizeBytes: number; columns: string[]; sha256: string; uploadedBy: string; createdAt: string; piiScan: unknown; usage?: DatasetUsage[] }
export interface Storage { usedBytes: number; limitBytes: number }
export interface CheckResult { ok: boolean; rowCount: number; columnsMatch: boolean; missingColumns: string[]; rowErrorCount: number; rowErrors: { row: number; column: string | null; message: string }[] }
export interface Binding { resource: string; datasetId: string; displayName: string; format: DataFormat; rowCount: number | null; boundAt: string }
export interface CatalogListing { agentId: string; agentName: string; category: string; versionId: string; versionNumber: string }
export interface Scope { type: "READ" | "WRITE"; resource: string; description: string; requiresApproval: boolean }
export interface ResourceDecl { name: string; format: DataFormat; schema: { required: string[]; properties: Record<string, string> } | null }
export interface CostEnvelope { currency: string; maxPerRun: number; pricingUnit: "METERED" | "OUTCOME" | "SUBSCRIPTION"; unitPrice: number; unitLabel: string }
export interface ApprovalGateDecl { trigger: string; description: string; timeoutMinutes: number; preview: { columns?: string[]; totalField?: string } | null }
export interface Manifest { name: string; version: string; scopes: Scope[]; networkAllowlist: string[]; costEnvelope: CostEnvelope; approvalGates: ApprovalGateDecl[]; dataClasses: string[]; resources: ResourceDecl[] | null; llm: unknown | null }
export interface VersionDetail { id: string; agentId: string; agentName: string; versionNumber: string; status: string; manifest: Manifest }
export interface RunResponse { id: string; displayId: string; status: RunStatus; terminalReason: TerminalReason | null }

export type VerificationStatus = "DRAFT" | "PENDING" | "VERIFIED" | "FIX_REQUESTED" | "REJECTED";
export type VerificationStep = "ABOUT" | "TAX_IDS" | "PHONE" | "AGREEMENT";
export type VerificationEntityType = "INDIVIDUAL" | "LLP" | "PRIVATE_LIMITED" | "PARTNERSHIP" | "OTHER";

// Masked values only: the API never returns the raw PAN, GSTIN or phone.
// decisionNote is stored by the backend but not yet returned (docs/frontend-gaps.md G14), so it is optional.
export interface DeveloperVerification {
  status: VerificationStatus;
  currentStep: VerificationStep;
  entityType: VerificationEntityType | null;
  legalName: string | null;
  displayName: string | null;
  country: string | null;
  city: string | null;
  panMasked: string | null;
  gstinMasked: string | null;
  panDocumentUploaded: boolean;
  phoneMasked: string | null;
  agreementVersion: string | null;
  agreementAcceptedAt: string | null;
  resubmissionCount: number;
  submittedAt: string | null;
  decisionNote?: string | null;
}

export interface DeveloperApiKey { id: string; name: string; prefix: string; createdAt: string; lastUsedAt: string | null; revokedAt: string | null }
export interface CreatedApiKey { id: string; name: string; prefix: string; key: string; createdAt: string }
