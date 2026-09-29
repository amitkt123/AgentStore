export type UserRole = "BUYER_ADMIN" | "BUYER_APPROVER" | "DEVELOPER" | "ADMIN";
export type OrgType = "BUYER" | "DEVELOPER";
export type VersionStatus = "DRAFT" | "IN_REVIEW" | "LIVE" | "REJECTED";
export type BuildStatus = "QUEUED" | "BUILDING" | "SUCCEEDED" | "FAILED";
export type ScopeType = "READ" | "WRITE";
export type PricingUnit = "METERED" | "OUTCOME" | "SUBSCRIPTION";
export type ConnectionStatus = "ACTIVE" | "REVOKED";
export type RunStatus =
  | "PENDING" | "PENDING_BUILD" | "RUNNING" | "AWAITING_APPROVAL" | "COMPLETED" | "FAILED" | "CANCELLED" | "TIMED_OUT" | "EXPIRED";
export type RunStepKind =
  | "READ" | "WRITE" | "GATE" | "COST" | "COMPLETE" | "FAILED" | "TIMEOUT" | "COST_CAP_REACHED"
  | "DATA_READ" | "DATA_WRITE" | "WRITE_STAGED" | "GATE_REQUESTED" | "GATE_DECIDED"
  | "LLM_CALL" | "USAGE" | "SCOPE_VIOLATION" | "AGENT_LOG" | "CANCELLED" | "NETWORK_BLOCKED";
export type TerminalReason =
  | "FINISHED" | "GATE_REJECTED" | "GATE_EXPIRED" | "COST_CAP_REACHED" | "TIMED_OUT" | "SCOPE_VIOLATIONS"
  | "AGENT_FAILED" | "CANCELLED" | "SANDBOX_LOST";
export type DecidedVia = "WEB" | "EMAIL_LINK" | "SLACK_LINK";
export type GateStatus = "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED";
export type RunGroup = "needs_you" | "running" | "finished" | "stopped";
export type RunStepSource = "PLATFORM" | "AGENT";
export type LedgerEntryType = "CHARGE" | "REVERSAL" | "FEE";
export type ReviewStatus = "PENDING" | "APPROVED" | "REJECTED";

export interface AuthResponse {
  token: string;
}

// Parsed manifest shape (GET responses) — mirrors the backend's Manifest Java record exactly.
export interface Scope {
  type: ScopeType;
  resource: string;
  description: string;
}
export interface CostEnvelope {
  currency: string;
  maxPerRun: number;
  pricingUnit: PricingUnit;
  unitPrice: number;
  unitLabel: string;
}
export interface ApprovalGateDecl {
  trigger: string;
  description: string;
  preview?: { columns?: string[]; totalField?: string };
}
export interface Manifest {
  name: string;
  version: string;
  scopes: Scope[];
  networkAllowlist: string[];
  costEnvelope: CostEnvelope;
  approvalGates: ApprovalGateDecl[];
  dataClasses: string[];
  runtime?: string | null;
  entrypoint?: string | null;
}

// Raw manifest wire format — what POST .../versions expects as manifestJson, built by Task 21's wizard.
export interface ManifestDraft {
  name: string;
  version: string;
  scopes: Scope[];
  network: { allowlist: string[] };
  costEnvelope: CostEnvelope;
  modelRequirements: { provider: string; model: string };
  approvalGates: ApprovalGateDecl[];
  dataClasses: string[];
}

export interface AgentResponse {
  id: string;
  name: string;
  category: string;
  description: string;
}
export interface AgentVersionResponse {
  id: string;
  versionNumber: string;
  status: VersionStatus;
}
export interface CatalogListingResponse {
  agentId: string;
  agentName: string;
  category: string;
  versionId: string;
  versionNumber: string;
}
export interface AgentVersionDetailResponse {
  id: string;
  agentId: string;
  agentName: string;
  versionNumber: string;
  status: VersionStatus;
  manifest: Manifest;
  build?: BuildSummary | null;
}
export interface BuildSummary {
  buildId: string;
  status: BuildStatus;
  failureCode: string | null;
  failureReason: string | null;
  logUrl: string | null;
}
export interface MyAgentsResponse {
  agentId: string;
  agentName: string;
  category: string;
  versions: AgentVersionResponse[];
}

export interface ConnectionResponse {
  id: string;
  agentId: string;
  grantedScopes: string[];
  spendCap: number;
  status: ConnectionStatus;
}
export interface ConnectionSummaryResponse extends ConnectionResponse {
  agentName: string;
}

export interface RunResponse {
  id: string;
  displayId: string;
  status: RunStatus;
  terminalReason: TerminalReason | null;
}
export interface RunStepResponse {
  kind: RunStepKind;
  source?: RunStepSource;
  detail: string;
  createdAt: string;
}
export interface RunDetailResponse {
  id: string;
  displayId: string;
  status: RunStatus;
  terminalReason: TerminalReason | null;
  costCap: number;
  totalCharged: number;
  steps: RunStepResponse[];
}
export interface RunSummaryResponse {
  id: string;
  displayId: string;
  agentName: string;
  status: RunStatus;
  terminalReason: TerminalReason | null;
  totalCharged: number;
  startedAt: string;
}

export interface GateDetail {
  gateId: string;
  action: string;
  summary: string;
  payload: unknown;
  status: GateStatus;
  expiresAt: string;
  estimatedUnits: number | null;
  costIfApproved: number | null;
  preview: { columns?: string[]; rows: Record<string, unknown>[]; totalRows: number; total?: number } | null;
  decidedVia: DecidedVia | null;
  decidedByUserId: string | null;
  decidedAt: string | null;
  note: string | null;
}
export interface RunListItem {
  id: string;
  displayId: string;
  agent: string;
  version: string;
  status: RunStatus;
  terminalReason: TerminalReason | null;
  reachedBlock: string | null;
  startedAt: string | null;
  completedAt: string | null;
  charged: number;
  held: number;
  decidedBy: { name: string } | null;
}
export interface RunListResponse {
  items: RunListItem[];
  counts: Record<RunGroup, number>;
  page: number;
  size: number;
  total: number;
}

export interface StatementLineResponse {
  runId: string;
  agentName: string;
  amount: number;
  type: LedgerEntryType;
  createdAt: string;
}
export interface WalletResponse {
  balance: number;
  cap: number | null;
  reserved: number;
}

export interface ReviewItemResponse {
  id: string;
  agentVersionId: string;
  agentName: string;
  versionNumber: string;
  status: ReviewStatus;
}
export interface ReviewItemDetailResponse extends ReviewItemResponse {
  manifest: Manifest;
  notes: string | null;
  createdAt: string;
  decidedAt: string | null;
}

export interface ApiKeySummary {
  id: string;
  name: string;
  prefix: string;
  createdAt: string;
  lastUsedAt: string | null;
  revokedAt: string | null;
}
export interface CreatedApiKey {
  id: string;
  name: string;
  prefix: string;
  key: string;
  createdAt: string;
}
