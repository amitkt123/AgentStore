export class AgentStoreError extends Error {
  constructor(message: string) { super(message); this.name = new.target.name; }
}

export class AgentStoreApiError extends AgentStoreError {
  constructor(readonly status: number, readonly code: string, readonly detail: string, readonly retryAfter?: number) {
    super(code ? `${status} ${code}: ${detail}` : `${status}: ${detail}`);
  }
}
export class ScopeNotGrantedError extends AgentStoreApiError {}
export class RunTerminatedError extends AgentStoreApiError {}
export class BudgetExceededError extends AgentStoreApiError {}
export class RateLimitedError extends AgentStoreApiError {}

export class GateRejectedError extends AgentStoreError {
  constructor(readonly gateId: string, readonly status: string, readonly note?: string) {
    super(`gate ${gateId} was ${status}${note ? `: ${note}` : ""}`);
  }
}

const byCode: Record<string, typeof AgentStoreApiError> = {
  SCOPE_NOT_GRANTED: ScopeNotGrantedError,
  RUN_TERMINATED: RunTerminatedError,
  BUDGET_EXCEEDED: BudgetExceededError,
  RATE_LIMITED: RateLimitedError,
};

export function parseRetryAfter(value: string | null): number | undefined {
  if (value === null) return undefined;
  const n = Number(value);
  return Number.isFinite(n) ? n : undefined;
}

export function errorFromProblem(status: number, body: any, retryAfter: string | null): AgentStoreApiError {
  const code = String(body?.code ?? "");
  const detail = String(body?.detail ?? body?.title ?? "");
  const cls = byCode[code] ?? (status === 429 ? RateLimitedError : AgentStoreApiError);
  return new cls(status, code, detail, parseRetryAfter(retryAfter));
}
