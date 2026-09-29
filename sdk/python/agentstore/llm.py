from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class TokenUsage:
    input_tokens: int
    output_tokens: int


@dataclass(frozen=True)
class ChatResponse:
    content: list[dict]
    stop_reason: str
    usage: TokenUsage
    model: str | None = None

    @property
    def text(self) -> str:
        for block in self.content:
            if block.get("type") == "text":
                return block.get("text", "")
        return ""


class Llm:
    def __init__(self, api):
        self._api = api

    def chat(self, model: str, messages: list[dict], max_tokens: int, system: str | None = None,
             tools: list[dict] | None = None, temperature: float | None = None) -> ChatResponse:
        body: dict = {"model": model, "messages": list(messages), "maxTokens": max_tokens}
        if system is not None:
            body["system"] = system
        if tools:
            body["tools"] = tools
        if temperature is not None:
            body["temperature"] = temperature
        r = self._api.llm_chat(body)
        u = r.get("usage") or {}
        return ChatResponse(list(r.get("content") or []), r.get("stopReason") or "end_turn",
                            TokenUsage(int(u.get("inputTokens", 0)), int(u.get("outputTokens", 0))), r.get("model"))
