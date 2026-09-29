import pytest

from agentstore import AgentContext, run
from agentstore.errors import GateRejectedError, RunTerminatedError
from conftest import FakeApi

ENV = {"AGENTSTORE_API_URL": "http://x", "AGENTSTORE_RUN_TOKEN": "t", "AGENTSTORE_RUN_ID": "r1",
       "AGENTSTORE_MODE": "SANDBOX"}
CONTEXT = {
    "runId": "r1", "mode": "SANDBOX", "input": {"month": "2026-01"},
    "grantedScopes": [{"type": "READ", "resource": "bank_statement", "requiresApproval": False}],
    "limits": {"deadlineAt": "2026-01-01T00:10:00Z", "costCapRemaining": 500, "currency": "USD",
               "llm": {"mode": "PLATFORM", "models": ["m1"], "tokensRemaining": 1000}},
}


def api(**extra):
    return FakeApi(context=dict(CONTEXT), **extra)


def test_context_exposes_run_input_and_limits():
    ctx = AgentContext(FakeApi(), CONTEXT)
    assert (ctx.run_id, ctx.mode, ctx.input) == ("r1", "SANDBOX", {"month": "2026-01"})
    assert ctx.limits.deadline_at == "2026-01-01T00:10:00Z" and ctx.limits.cost_cap_remaining == 500
    assert ctx.limits.llm.models == ["m1"] and ctx.limits.llm.tokens_remaining == 1000
    assert ctx.granted_scopes[0].resource == "bank_statement"


def test_context_tolerates_missing_optional_sections():
    ctx = AgentContext(FakeApi(), {"runId": "r", "mode": "TRIAL"})
    assert ctx.input == {} and ctx.limits.llm is None and ctx.granted_scopes == []


def test_success_flushes_logs_then_completes_with_outputs():
    fake = api(complete={"status": "COMPLETED"})

    def handler(ctx):
        ctx.log.info("working")
        return {"matched": 3}

    run(handler, env=ENV, api=fake, heartbeat_interval=60)
    assert fake.names() == ["context", "logs", "complete"]
    assert fake.calls[-1][2]["outputs"] == {"matched": 3}


def test_none_return_completes_without_outputs():
    fake = api(complete={"status": "COMPLETED"})
    run(lambda ctx: None, env=ENV, api=fake, heartbeat_interval=60)
    assert fake.calls[-1] == ("complete", (), {"outputs": None})


def test_exception_logs_traceback_fails_and_reraises():
    fake = api(fail={"status": "FAILED"})

    def handler(ctx):
        raise ValueError("boom")

    with pytest.raises(ValueError, match="boom"):
        run(handler, env=ENV, api=fake, heartbeat_interval=60)
    assert fake.names() == ["context", "logs", "fail"]
    logged = fake.calls[1][1][0]
    assert logged[-1]["level"] == "error" and "ValueError" in logged[-1]["fields"]["traceback"]
    assert "ValueError: boom" in fake.calls[-1][2]["error"] and fake.calls[-1][2]["retriable"] is False
    assert "complete" not in fake.names()


def test_non_object_return_value_fails_the_run_with_a_clear_message():
    fake = api(fail={"status": "FAILED"})
    with pytest.raises(TypeError, match="dict"):
        run(lambda ctx: [1, 2, 3], env=ENV, api=fake, heartbeat_interval=60)
    assert "complete" not in fake.names() and "dict" in fake.calls[-1][2]["error"]


def test_a_409_from_fail_does_not_mask_the_handlers_exception():
    fake = api(fail=[RunTerminatedError(409, "RUN_TERMINATED", "already stopped")])

    def handler(ctx):
        raise ValueError("original")

    with pytest.raises(ValueError, match="original"):
        run(handler, env=ENV, api=fake, heartbeat_interval=60)


def test_gate_rejection_propagates_and_fails_the_run():
    fake = api(fail={"status": "FAILED"})

    def handler(ctx):
        raise GateRejectedError("g", "REJECTED", "no")

    with pytest.raises(GateRejectedError):
        run(handler, env=ENV, api=fake, heartbeat_interval=60)
    assert "fail" in fake.names()


@pytest.mark.parametrize("missing", ["AGENTSTORE_API_URL", "AGENTSTORE_RUN_TOKEN"])
def test_missing_env_fails_fast_naming_the_variable(missing):
    env = {k: v for k, v in ENV.items() if k != missing}
    fake = FakeApi()
    with pytest.raises(RuntimeError, match=missing):
        run(lambda ctx: None, env=env, api=fake)
    assert fake.calls == []
