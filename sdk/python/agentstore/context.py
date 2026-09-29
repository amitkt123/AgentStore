from __future__ import annotations

from dataclasses import dataclass, field

from .data import Data
from .gates import Gates
from .llm import Llm
from .logs import Logger
from .usage import Usage


@dataclass(frozen=True)
class LlmLimits:
    mode: str | None
    models: list[str]
    tokens_remaining: int | None


@dataclass(frozen=True)
class Limits:
    deadline_at: str | None
    cost_cap_remaining: float | None
    currency: str | None
    llm: LlmLimits | None


@dataclass(frozen=True)
class GrantedScope:
    type: str
    resource: str
    requires_approval: bool


def _limits(raw: dict) -> Limits:
    llm = raw.get("llm")
    return Limits(
        raw.get("deadlineAt"), raw.get("costCapRemaining"), raw.get("currency"),
        LlmLimits(llm.get("mode"), list(llm.get("models") or []), llm.get("tokensRemaining")) if llm else None,
    )


class AgentContext:
    def __init__(self, api, context: dict, *, log_flush_size: int = 50, log_flush_interval: float = 2.0):
        self.run_id: str = context["runId"]
        self.mode: str = context["mode"]
        self.input: dict = context.get("input") or {}
        self.limits: Limits = _limits(context.get("limits") or {})
        self.granted_scopes = [GrantedScope(s["type"], s["resource"], bool(s.get("requiresApproval")))
                               for s in context.get("grantedScopes") or []]
        self.data = Data(api)
        self.llm = Llm(api)
        self.gates = Gates(api)
        self.usage = Usage(api)
        self.log = Logger(api, flush_size=log_flush_size, flush_interval=log_flush_interval)
