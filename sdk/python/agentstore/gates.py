from __future__ import annotations

from dataclasses import dataclass

from .errors import GateRejectedError


@dataclass(frozen=True)
class Decision:
    gate_id: str
    status: str
    note: str | None = None

    @property
    def approved(self) -> bool:
        return self.status == "APPROVED"


class Gates:
    def __init__(self, api, wait: int = 25):
        self._api = api
        self._wait = wait                                   # server caps a single wait at 30s

    def request(self, action: str, summary: str, payload: dict, affects: list[str] | None = None, *,
                estimated_units: float | None = None, raise_on_reject: bool = False) -> Decision:
        body = {"action": action, "summary": summary, "payload": payload, "affectsResources": list(affects or [])}
        if estimated_units is not None:
            body["estimatedUnits"] = estimated_units      # the platform turns this into the buyer's "cost if approved"
        created = self._api.create_gate(body)
        gate_id, view = created["gateId"], created
        while view["status"] == "PENDING":
            view = self._api.get_gate(gate_id, self._wait)
        decision = Decision(gate_id, view["status"], view.get("note"))
        if raise_on_reject and not decision.approved:
            raise GateRejectedError(gate_id, decision.status, decision.note)
        return decision
