import json

import httpx
import pytest

from agentstore.errors import (
    AgentStoreApiError, BudgetExceededError, RateLimitedError, RunTerminatedError, ScopeNotGrantedError,
)
from conftest import make_api


def problem(status, code, headers=None):
    return httpx.Response(status, json={"status": status, "code": code, "detail": "d"},
                          headers={"content-type": "application/problem+json", **(headers or {})})


def test_sends_bearer_token_and_decodes_json():
    seen = {}

    def handler(request):
        seen["auth"] = request.headers["authorization"]
        seen["path"] = request.url.path
        return httpx.Response(200, json={"runId": "r1", "mode": "SANDBOX"})

    ctx = make_api(handler).context()
    assert ctx["runId"] == "r1"
    assert seen == {"auth": "Bearer tok-123", "path": "/runtime/v1/context"}


def test_retries_429_then_succeeds():
    calls = []

    def handler(request):
        calls.append(1)
        return problem(429, "RATE_LIMITED") if len(calls) < 3 else httpx.Response(204)

    make_api(handler).heartbeat()
    assert len(calls) == 3


def test_retries_5xx_up_to_max_then_raises_generic_api_error():
    calls = []

    def handler(request):
        calls.append(1)
        return httpx.Response(503, text="upstream down")

    with pytest.raises(AgentStoreApiError) as exc:
        make_api(handler).heartbeat()
    assert len(calls) == 4          # 1 attempt + 3 retries
    assert exc.value.status == 503 and exc.value.code == ""


def test_exhausted_429_raises_rate_limited_with_retry_after():
    def handler(request):
        return problem(429, "RATE_LIMITED", {"retry-after": "2"})

    with pytest.raises(RateLimitedError) as exc:
        make_api(handler).heartbeat()
    assert exc.value.retry_after == 2.0


def test_honours_retry_after_when_sleeping():
    slept = []
    calls = []

    def handler(request):
        calls.append(1)
        return problem(429, "RATE_LIMITED", {"retry-after": "2"}) if len(calls) == 1 else httpx.Response(204)

    make_api(handler, sleep=slept.append).heartbeat()
    assert slept == [2.0]


def test_501_is_not_retried():
    calls = []

    def handler(request):
        calls.append(1)
        return problem(501, "NOT_IMPLEMENTED")

    with pytest.raises(AgentStoreApiError):
        make_api(handler).heartbeat()
    assert len(calls) == 1


@pytest.mark.parametrize("status,code,exc_type", [
    (403, "SCOPE_NOT_GRANTED", ScopeNotGrantedError),
    (409, "RUN_TERMINATED", RunTerminatedError),
    (402, "BUDGET_EXCEEDED", BudgetExceededError),
])
def test_problem_code_maps_to_typed_exception(status, code, exc_type):
    with pytest.raises(exc_type) as exc:
        make_api(lambda r: problem(status, code)).records("payroll")
    assert exc.value.code == code and exc.value.status == status
    assert isinstance(exc.value, AgentStoreApiError)


def test_write_and_usage_send_camel_case_bodies():
    bodies = {}

    def handler(request):
        bodies[request.url.path] = json.loads(request.content)
        return httpx.Response(200, json={"accepted": 1, "staged": False, "charged": 1})

    api = make_api(handler)
    api.write("journal_entries", [{"account": "1000"}])
    api.usage(2, "reconciled_transaction", outcome_ref="ref-1")
    assert bodies["/runtime/v1/resources/journal_entries/records"] == {"records": [{"account": "1000"}]}
    assert bodies["/runtime/v1/usage"] == {"quantity": 2, "unit": "reconciled_transaction", "outcomeRef": "ref-1"}


def test_complete_without_outputs_and_fail_and_chat():
    seen = {}

    def handler(request):
        seen[request.url.path] = request.content
        if request.url.path.endswith("/llm/chat"):
            return httpx.Response(200, json={"content": [{"type": "text", "text": "hi"}]})
        return httpx.Response(200, json={"status": "COMPLETED"})

    api = make_api(handler)
    assert api.complete()["status"] == "COMPLETED"
    api.fail("boom", retriable=False)
    assert json.loads(seen["/runtime/v1/fail"]) == {"error": "boom", "retriable": False}
    assert api.llm_chat({"model": "m", "messages": [], "maxTokens": 5})["content"][0]["text"] == "hi"


def test_file_content_returns_bytes():
    api = make_api(lambda r: httpx.Response(200, content=b"a,b\n1,2\n"))
    assert api.file_content("bank_statement") == b"a,b\n1,2\n"
