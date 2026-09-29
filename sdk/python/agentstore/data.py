from __future__ import annotations

from dataclasses import dataclass
from typing import Iterator

from .errors import AgentStoreError


@dataclass(frozen=True)
class WriteResult:
    accepted: int
    staged: bool


class FileHandle:
    def __init__(self, api, resource: str, url: str, expires_at: str | None):
        self._api = api
        self.resource = resource
        self.url = url
        self.expires_at = expires_at

    def read(self) -> bytes:
        return self._api.file_content(self.resource)


class Data:
    def __init__(self, api):
        self._api = api

    def records(self, resource: str, *, page_size: int | None = None) -> Iterator[dict]:
        cursor = None
        while True:
            page = self._api.records(resource, cursor=cursor, limit=page_size)
            yield from page.get("records") or []
            nxt = page.get("nextCursor")
            if not nxt:
                return
            if nxt == cursor:
                raise AgentStoreError(f"pagination cursor for {resource!r} did not advance ({nxt!r})")
            cursor = nxt

    def write(self, resource: str, records: list[dict]) -> WriteResult:
        if not records:
            return WriteResult(0, False)
        result = self._api.write(resource, list(records))
        return WriteResult(int(result.get("accepted", 0)), bool(result.get("staged", False)))

    def file(self, resource: str) -> FileHandle:
        link = self._api.file_link(resource)
        return FileHandle(self._api, resource, link["url"], link.get("expiresAt"))
