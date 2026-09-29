import datetime
import decimal
import time

import pytest

from agentstore.errors import GateRejectedError
from agentstore.gates import Gates
from agentstore.heartbeat import Heartbeat
from agentstore.logs import Logger
from conftest import FakeApi


def gate_api(*poll):
    return FakeApi(create_gate={"gateId": "g-1", "status": "PENDING"}, get_gate=list(poll))


def test_gate_polls_through_pending_until_approved():
    api = gate_api({"status": "PENDING"}, {"status": "PENDING"}, {"status": "APPROVED", "note": "ok"})
    d = Gates(api).request("post_journal_entries", "Post 2", {"rows": 2}, affects=["journal_entries"])
    assert d.approved and d.status == "APPROVED" and d.note == "ok" and d.gate_id == "g-1"
    assert api.names() == ["create_gate", "get_gate", "get_gate", "get_gate"]
    assert api.calls[0][1][0] == {"action": "post_journal_entries", "summary": "Post 2", "payload": {"rows": 2},
                                  "affectsResources": ["journal_entries"]}
    assert api.calls[1][1] == ("g-1", 25)                   # server-side wait=25


def test_gate_decided_at_creation_does_not_poll():
    api = FakeApi(create_gate={"gateId": "g-1", "status": "APPROVED"})
    assert Gates(api).request("a", "s", {}).approved
    assert api.names() == ["create_gate"]


def test_rejection_returns_decision_by_default_and_raises_on_request():
    d = Gates(gate_api({"status": "REJECTED", "note": "no"})).request("a", "s", {})
    assert not d.approved and d.status == "REJECTED"
    with pytest.raises(GateRejectedError) as exc:
        Gates(gate_api({"status": "REJECTED", "note": "no"})).request("a", "s", {}, raise_on_reject=True)
    assert exc.value.note == "no" and exc.value.status == "REJECTED"


def test_expired_counts_as_not_approved():
    with pytest.raises(GateRejectedError):
        Gates(gate_api({"status": "EXPIRED"})).request("a", "s", {}, raise_on_reject=True)


def test_logger_buffers_and_flushes_on_size_threshold():
    api = FakeApi()
    log = Logger(api, flush_size=3, flush_interval=999)
    log.info("a"); log.warn("b")
    assert api.calls == []
    log.error("c", code=7)
    assert api.names() == ["logs"]
    assert api.calls[0][1][0] == [{"level": "info", "message": "a"}, {"level": "warn", "message": "b"},
                                  {"level": "error", "message": "c", "fields": {"code": 7}}]


def test_logger_flushes_on_time_threshold_and_explicit_flush():
    now = [0.0]
    api = FakeApi()
    log = Logger(api, flush_size=100, flush_interval=2.0, clock=lambda: now[0])
    log.info("a")
    now[0] = 5.0
    log.info("b")                                           # interval elapsed -> flush both
    assert api.names() == ["logs"]
    log.info("c")
    log.flush()
    assert api.names() == ["logs", "logs"]
    log.flush()                                             # empty -> no call
    assert len(api.calls) == 2


def test_logger_truncates_long_messages_and_stringifies_odd_fields():
    api = FakeApi()
    log = Logger(api)
    log.error("x" * 9000, at=datetime.datetime(2026, 1, 1), amount=decimal.Decimal("1.50"))
    log.flush()
    entry = api.calls[0][1][0][0]
    assert len(entry["message"]) == 4000
    assert entry["fields"] == {"at": "2026-01-01 00:00:00", "amount": "1.50"}


def test_logger_swallows_a_failed_flush():
    log = Logger(FakeApi(logs=[RuntimeError("down")]))
    log.info("a")
    log.flush()                                             # must not raise


def test_heartbeat_beats_until_stopped_and_survives_errors():
    api = FakeApi(heartbeat=[RuntimeError("blip")])         # first beat fails, later beats return None
    hb = Heartbeat(api, interval=0.01)
    hb.start()
    time.sleep(0.12)
    hb.stop()
    n = len(api.calls)
    assert n >= 3
    time.sleep(0.05)
    assert len(api.calls) == n                              # nothing after stop


def test_heartbeat_stop_is_safe_before_start_and_twice():
    hb = Heartbeat(FakeApi(), interval=0.01)
    hb.stop()
    hb.start()
    hb.stop()
    hb.stop()
