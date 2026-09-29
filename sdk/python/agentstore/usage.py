from __future__ import annotations

from dataclasses import dataclass


def _num(value) -> float | None:
    return None if value is None else float(value)


@dataclass(frozen=True)
class UsageResult:
    charged: float
    total_charged: float
    cost_cap_remaining: float | None


class Usage:
    def __init__(self, api):
        self._api = api

    def report(self, quantity: float, unit: str, outcome_ref: str | None = None) -> UsageResult:
        r = self._api.usage(quantity, unit, outcome_ref)
        return UsageResult(_num(r.get("charged")) or 0.0, _num(r.get("totalCharged")) or 0.0,
                           _num(r.get("costCapRemaining")))
