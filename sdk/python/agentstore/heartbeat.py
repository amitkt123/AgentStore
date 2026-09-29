from __future__ import annotations

import threading


class Heartbeat:
    def __init__(self, api, interval: float = 15.0):
        self._api = api
        self._interval = interval
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        if self._thread is not None:
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, name="agentstore-heartbeat", daemon=True)
        self._thread.start()

    def _loop(self) -> None:
        while not self._stop.wait(self._interval):
            try:
                self._api.heartbeat()
            except Exception:
                pass                                        # a missed beat is retried on the next tick

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=5)
            self._thread = None
