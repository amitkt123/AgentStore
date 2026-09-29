from __future__ import annotations

import json
import sys
import threading
import time

_MAX_MESSAGE = 4000


class Logger:
    def __init__(self, api, *, flush_size: int = 50, flush_interval: float = 2.0, clock=time.monotonic):
        self._api = api
        self._size = flush_size
        self._interval = flush_interval
        self._clock = clock
        self._buffer: list[dict] = []
        self._last_flush = clock()
        self._lock = threading.Lock()

    def info(self, message, **fields):
        self._add("info", message, fields)

    def warn(self, message, **fields):
        self._add("warn", message, fields)

    def error(self, message, **fields):
        self._add("error", message, fields)

    def _add(self, level: str, message, fields: dict) -> None:
        entry: dict = {"level": level, "message": str(message)[:_MAX_MESSAGE]}
        if fields:
            entry["fields"] = json.loads(json.dumps(fields, default=str))
        with self._lock:
            self._buffer.append(entry)
            due = len(self._buffer) >= self._size or self._clock() - self._last_flush >= self._interval
        if due:
            self.flush()

    def flush(self) -> None:
        with self._lock:
            batch, self._buffer = self._buffer, []
            self._last_flush = self._clock()
        if not batch:
            return
        try:
            self._api.logs(batch)
        except Exception as exc:  # logging must never fail the run
            print(f"agentstore: dropped {len(batch)} log entries: {exc}", file=sys.stderr)
