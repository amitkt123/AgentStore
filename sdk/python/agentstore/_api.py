"""The only module that imports generated code. Returns plain dicts/bytes so nothing above depends on model shapes."""
from __future__ import annotations

import json
import uuid

import httpx

from ._generated import AuthenticatedClient
from ._generated.api.runtime_context_controller import context as _context
from ._generated.api.runtime_gate_controller import create as _create_gate
from ._generated.api.runtime_gate_controller import get as _get_gate
from ._generated.api.runtime_lifecycle_controller import complete as _complete
from ._generated.api.runtime_lifecycle_controller import fail as _fail
from ._generated.api.runtime_lifecycle_controller import heartbeat as _heartbeat
from ._generated.api.runtime_lifecycle_controller import logs as _logs
from ._generated.api.runtime_resource_controller import file as _file
from ._generated.api.runtime_resource_controller import file_content as _file_content
from ._generated.api.runtime_resource_controller import records as _records
from ._generated.api.runtime_resource_controller import write as _write
from ._generated.api.runtime_usage_controller import usage as _usage
from ._generated.models import (
    CompleteRequest, CreateGateRequest, FailRequest, LogBatch, UsageRequest, WriteRecordsRequest,
)
from ._generated.types import UNSET
from ._transport import RetryTransport, raise_for_problem


def _json(response):
    return json.loads(response.content) if response.content else None


class Api:
    def __init__(self, base_url: str, token: str, *, transport: httpx.BaseTransport | None = None,
                 timeout: float = 60.0, retry: dict | None = None):
        self._client = AuthenticatedClient(
            base_url=base_url.rstrip("/"), token=token, timeout=httpx.Timeout(timeout),
            httpx_args={"transport": RetryTransport(transport, **(retry or {})),
                        "event_hooks": {"response": [raise_for_problem]}},
        )

    def context(self) -> dict:
        return _json(_context.sync_detailed(client=self._client))

    def records(self, resource: str, cursor: str | None = None, limit: int | None = None) -> dict:
        return _json(_records.sync_detailed(resource, client=self._client, cursor=cursor or UNSET,
                                            limit=limit or UNSET))

    def write(self, resource: str, records: list[dict]) -> dict:
        body = WriteRecordsRequest.from_dict({"records": records})
        return _json(_write.sync_detailed(resource, client=self._client, body=body))

    def file_link(self, resource: str) -> dict:
        return _json(_file.sync_detailed(resource, client=self._client))

    def file_content(self, resource: str) -> bytes:
        return _file_content.sync_detailed(resource, client=self._client).content

    def usage(self, quantity: float, unit: str, outcome_ref: str | None = None) -> dict:
        data = {"quantity": quantity, "unit": unit}
        if outcome_ref:
            data["outcomeRef"] = outcome_ref
        return _json(_usage.sync_detailed(client=self._client, body=UsageRequest.from_dict(data)))

    def llm_chat(self, body: dict) -> dict:
        # Untyped passthrough body in the contract; goes through the same transport (retry + error mapping).
        return self._client.get_httpx_client().post("/runtime/v1/llm/chat", json=body).json()

    def create_gate(self, body: dict) -> dict:
        return _json(_create_gate.sync_detailed(client=self._client, body=CreateGateRequest.from_dict(body)))

    def get_gate(self, gate_id: str, wait: int) -> dict:
        return _json(_get_gate.sync_detailed(uuid.UUID(gate_id), client=self._client, wait=wait))

    def heartbeat(self) -> None:
        _heartbeat.sync_detailed(client=self._client)

    def logs(self, entries: list[dict]) -> None:
        _logs.sync_detailed(client=self._client, body=LogBatch.from_dict({"entries": entries}))

    def complete(self, outputs: dict | None = None) -> dict:
        body = CompleteRequest.from_dict({"outputs": outputs}) if outputs else UNSET
        return _json(_complete.sync_detailed(client=self._client, body=body))

    def fail(self, error: str, retriable: bool = False) -> dict:
        body = FailRequest.from_dict({"error": error[:2000], "retriable": retriable})
        return _json(_fail.sync_detailed(client=self._client, body=body))
