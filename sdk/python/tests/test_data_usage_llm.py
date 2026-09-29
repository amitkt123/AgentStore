import pytest

from agentstore.data import Data, WriteResult
from agentstore.errors import AgentStoreError
from agentstore.llm import Llm
from agentstore.usage import Usage
from conftest import FakeApi


def test_records_pages_lazily_until_no_cursor():
    api = FakeApi(records=[{"records": [{"a": 1}, {"a": 2}], "nextCursor": "c1"}, {"records": [{"a": 3}]}])
    it = Data(api).records("bank_statement")
    assert api.calls == []                                  # lazy: nothing fetched yet
    assert list(it) == [{"a": 1}, {"a": 2}, {"a": 3}]
    assert api.calls[1][2]["cursor"] == "c1"


def test_records_of_an_empty_resource_yields_nothing():
    assert list(Data(FakeApi(records=[{"records": []}])).records("empty")) == []


def test_records_raises_instead_of_looping_when_the_cursor_never_advances():
    page = {"records": [{"a": 1}], "nextCursor": "same"}
    api = FakeApi(records=[dict(page), dict(page), dict(page)])
    with pytest.raises(AgentStoreError, match="cursor"):
        list(Data(api).records("stuck"))


def test_write_reports_accepted_and_staged():
    api = FakeApi(write={"accepted": 2, "staged": True})
    assert Data(api).write("journal_entries", [{"x": 1}, {"x": 2}]) == WriteResult(accepted=2, staged=True)


def test_writing_nothing_makes_no_call():
    api = FakeApi()
    assert Data(api).write("journal_entries", []) == WriteResult(accepted=0, staged=False)
    assert api.calls == []


def test_file_handle_exposes_link_and_reads_bytes():
    api = FakeApi(file_link={"url": "https://s/f", "expiresAt": "2026-01-01T00:00:00Z"}, file_content=b"a,b")
    handle = Data(api).file("bank_statement")
    assert (handle.url, handle.expires_at) == ("https://s/f", "2026-01-01T00:00:00Z")
    assert handle.read() == b"a,b"


def test_usage_report_maps_response():
    api = FakeApi(usage={"charged": 2, "totalCharged": 4, "costCapRemaining": 96})
    r = Usage(api).report(2, "reconciled_transaction", outcome_ref="o1")
    assert (r.charged, r.total_charged, r.cost_cap_remaining) == (2.0, 4.0, 96.0)
    assert api.calls[0][1:] == ((2, "reconciled_transaction", "o1"), {})


def test_llm_chat_builds_camel_case_body_and_wraps_response():
    api = FakeApi(llm_chat={
        "model": "m", "stopReason": "end_turn", "usage": {"inputTokens": 3, "outputTokens": 5},
        "content": [{"type": "tool_use", "id": "t", "name": "n", "input": {}}, {"type": "text", "text": "hello"}],
    })
    res = Llm(api).chat("m", [{"role": "user", "content": "hi"}], max_tokens=50, system="be brief", temperature=0.2)
    body = api.calls[0][1][0]
    assert body == {"model": "m", "messages": [{"role": "user", "content": "hi"}], "maxTokens": 50,
                    "system": "be brief", "temperature": 0.2}
    assert res.text == "hello"                              # first *text* block, skipping tool_use
    assert res.stop_reason == "end_turn"
    assert (res.usage.input_tokens, res.usage.output_tokens) == (3, 5)
    assert res.content[0]["type"] == "tool_use"


def test_llm_text_is_empty_string_when_no_text_block():
    api = FakeApi(llm_chat={"content": [{"type": "tool_use", "id": "t", "name": "n", "input": {}}]})
    assert Llm(api).chat("m", [{"role": "user", "content": "x"}], max_tokens=5).text == ""
