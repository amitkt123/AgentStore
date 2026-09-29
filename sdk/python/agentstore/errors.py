from __future__ import annotations


class AgentStoreError(Exception):
    """Base class for every error the SDK raises on purpose."""


class AgentStoreApiError(AgentStoreError):
    def __init__(self, status: int, code: str, detail: str, retry_after: float | None = None):
        super().__init__(f"{status} {code}: {detail}" if code else f"{status}: {detail}")
        self.status = status
        self.code = code
        self.detail = detail
        self.retry_after = retry_after


class ScopeNotGrantedError(AgentStoreApiError):
    pass


class RunTerminatedError(AgentStoreApiError):
    pass


class BudgetExceededError(AgentStoreApiError):
    pass


class RateLimitedError(AgentStoreApiError):
    pass


class GateRejectedError(AgentStoreError):
    def __init__(self, gate_id: str, status: str, note: str | None = None):
        super().__init__(f"gate {gate_id} was {status}" + (f": {note}" if note else ""))
        self.gate_id = gate_id
        self.status = status
        self.note = note


_BY_CODE = {
    "SCOPE_NOT_GRANTED": ScopeNotGrantedError,
    "RUN_TERMINATED": RunTerminatedError,
    "BUDGET_EXCEEDED": BudgetExceededError,
    "RATE_LIMITED": RateLimitedError,
}


def _parse_retry_after(value: str | None) -> float | None:
    try:
        return float(value) if value is not None else None
    except ValueError:
        return None


def error_from_problem(status: int, body: dict | None, retry_after: str | None) -> AgentStoreApiError:
    body = body or {}
    code = str(body.get("code") or "")
    detail = str(body.get("detail") or body.get("title") or "")
    cls = _BY_CODE.get(code) or (RateLimitedError if status == 429 else AgentStoreApiError)
    return cls(status, code, detail, _parse_retry_after(retry_after))
