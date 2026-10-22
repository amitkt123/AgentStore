import type { UserRole } from "../roles";
import type { BuildStatus, LlmMode, VersionStatus } from "./enums";
export type { BuildStatus, LlmMode, VersionStatus } from "./enums";

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
export interface LlmDecl { mode: LlmMode; models: string[]; maxTokensPerRun: number; provider: string | null }
export interface Manifest { name: string; version: string; scopes: Scope[]; networkAllowlist: string[]; costEnvelope: CostEnvelope; approvalGates: ApprovalGateDecl[]; dataClasses: string[]; resources: ResourceDecl[] | null; llm: LlmDecl | null; runtime?: string | null; entrypoint?: string | null }
export interface BuildSummary { buildId: string; status: BuildStatus; failureCode: string | null; failureReason: string | null; logUrl: string | null }
export interface VersionDetail { id: string; agentId: string; agentName: string; versionNumber: string; status: VersionStatus; manifest: Manifest; build?: BuildSummary | null }
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
export interface VersionSummary { id: string; versionNumber: string; status: VersionStatus }
export interface MyAgent { agentId: string; agentName: string; category: string; versions: VersionSummary[] }
export interface CreatedAgent { id: string; name: string; category: string; description: string | null }
export type BlockerCode = "BUILD_NOT_READY" | "UNDECLARED_ACCESS" | "COST_ENVELOPE_EXCEEDED";
export interface Blocker { code: BlockerCode; message: string; runId: string | null }
export interface Readiness { ready: boolean; blockers: Blocker[] }
export type ManifestPatchOp = "add_read_scope" | "add_resource" | "add_allowlist_host" | "remove_scope";
export interface ManifestPatchRequest { op: ManifestPatchOp; resource?: string; description?: string; requiresApproval?: boolean; name?: string; format?: string; schema?: unknown; host?: string; type?: string }
export interface ManifestPatchResult { manifestRevision: number; diff: { addedScopes: string[]; removedScopes: string[]; addedHosts: string[] } }

// Money (WS10a). BigDecimal amounts arrive as JSON numbers, like the other money fields above.
export type BillingMode = "PREPAID" | "POSTPAID";
export type LedgerAccountKind = string;

export interface Wallet {
  mode: BillingMode; balance: number; held: number; available: number; spentThisMonth: number;
  monthlyLimit: number | null; creditLimit: number | null; owed: number; cap: number | null; reserved: number;
}
export interface BillingLine {
  runDisplayId: string; units: number; unitDisplay: string | null; amount: number;
  terminalReason: TerminalReason | null; decidedBy: string | null; at: string;
}
export interface BillingAgentLines { agentId: string; agent: string; runs: number; total: number; lines: BillingLine[] }
export interface BillingSummary { month: string; total: number; agents: BillingAgentLines[] }
export interface EarningsAgent {
  agentId: string; agent: string; runs: number; buyersPaid: number; fee: number; aiUsage: number; earned: number; perRun: number;
}
export interface Earnings {
  month: string; buyersPaid: number; platformFee: number; feePercent: number; aiUsage: number; tax: number;
  earned: number; available: number; byAgent: EarningsAgent[];
}
export interface OrgMoney {
  orgId: string; name: string; type: OrgType; feePercent: number | null; effectiveFeePercent: number;
  billingMode: BillingMode | null; effectiveBillingMode: BillingMode; creditLimit: number | null;
  walletBalance: number; payableBalance: number; moneyFrozenAt: string | null; moneyFrozenReason: string | null;
}
export interface OrgMoneyPatch { feePercent?: number; billingMode?: BillingMode; creditLimit?: number }
export interface PlatformAccounts {
  accounts: { kind: LedgerAccountKind; balance: number }[];
  totalsCheck: boolean;
  balancesMatchPostings: boolean;
  walletsMatchLedger: boolean;
}
export interface AdjustmentBody { requestId: string; amount: number; note: string }

export type PaymentMethod = "UPI" | "CARD" | "NETBANKING" | "BANK_TRANSFER";
export interface PaymentOptions {
  provider: string; methods: PaymentMethod[]; topUpMin: number; topUpDailyLimit: number; topUpRemainingToday: number;
}
export interface PaymentOrder {
  id: string; purpose: "TOPUP" | "BILL"; method: PaymentMethod; amount: number;
  status: "CREATED" | "PAID" | "FAILED" | "EXPIRED"; refundedAmount: number; createdAt: string; paidAt: string | null;
}
export interface Checkout {
  order: PaymentOrder; provider: string; providerOrderId: string; checkout: Record<string, unknown>;
}
export interface BankTransferDetails { accountNumber: string; ifsc: string; beneficiaryName: string }
export interface RefundPlanPart { paymentOrderId: string; method: PaymentMethod; paidAt: string | null; amount: number }
export interface Refundable { refundable: number; parts: RefundPlanPart[] }
export interface RefundPart {
  id: string; paymentOrderId: string; method: PaymentMethod; amount: number;
  status: "PENDING" | "PROCESSING" | "DONE" | "FAILED"; failureReason: string | null;
}
export interface Refund {
  id: string; amount: number; status: "REQUESTED" | "PROCESSING" | "DONE" | "PARTLY_FAILED" | "FAILED";
  parts: RefundPart[]; createdAt: string;
}
export interface Bill {
  id: string; period: string; amount: number; status: "OPEN" | "PAID" | "OVERDUE"; dueAt: string; paidAt: string | null;
}
export type SimulateOutcome = "PAID" | "FAILED";

export type PayoutAccountType = "BANK" | "VPA";
export type PayoutAccountStatus = "PENDING" | "VERIFIED" | "FAILED";
export interface PayoutAccount {
  id: string; type: PayoutAccountType; accountLast4: string | null; ifsc: string | null; vpa: string | null;
  holderName: string; validatedName: string | null; nameMatch: boolean | null; status: PayoutAccountStatus;
  failureReason: string | null; createdAt: string; verifiedAt: string | null;
}
export interface PayoutAccountBody {
  type: PayoutAccountType; accountNumber?: string; ifsc?: string; vpa?: string; holderName: string;
}
export interface PayoutsSummary {
  account: PayoutAccount | null; available: number; minimum: number; dailyLimit: number; withdrawnToday: number;
  canWithdraw: boolean; blockedReason: string | null; validationAttemptsLeft: number; provider: string; verifiedName: string | null;
}
export type WithdrawalStatus = "REQUESTED" | "PROCESSING" | "PROCESSED" | "REVERSED" | "FAILED";
export interface Withdrawal {
  id: string; amount: number; mode: string; status: WithdrawalStatus; utr: string | null; accountLabel: string | null;
  failureReason: string | null; createdAt: string; processedAt: string | null;
}
export interface AdminWithdrawal extends Withdrawal { orgId: string; orgName: string | null }
export interface AdminPayoutAccount extends PayoutAccount { orgId: string; orgName: string | null; legalName: string | null }
export interface ReconciliationIssue {
  id: string; day: string; kind: string; providerRef: string | null; detail: Record<string, unknown> | null;
  createdAt: string; resolvedAt: string | null;
}
export type PayoutSimulateOutcome = "PROCESSED" | "REVERSED" | "FAILED";
