from __future__ import annotations

import random
import time

import httpx

from .errors import _parse_retry_after, error_from_problem


# POST /usage charges on every call and has no idempotency key, so a 5xx whose response was lost after the server
# committed would double-charge on retry. 429 is safe: the rate limiter rejects before the request is handled.
_NO_5XX_RETRY_PATHS = ("/runtime/v1/usage",)


def _retryable(status: int, request: httpx.Request) -> bool:
    if status == 429:
        return True
    return status >= 500 and status != 501 and request.url.path not in _NO_5XX_RETRY_PATHS


class RetryTransport(httpx.BaseTransport):
    """Retries 429/5xx (not 501; 429 only for POST /usage) with exponential backoff + jitter."""

    def __init__(self, inner: httpx.BaseTransport | None = None, max_retries: int = 3, base_delay: float = 0.5,
                 max_delay: float = 8.0, sleep=time.sleep, jitter=random.random):
        self._inner = inner or httpx.HTTPTransport()
        self._max_retries = max_retries
        self._base = base_delay
        self._max = max_delay
        self._sleep = sleep
        self._jitter = jitter

    def _delay(self, attempt: int, retry_after: float | None) -> float:
        if retry_after is not None:
            return min(retry_after, 30.0)
        return min(self._max, self._base * 2 ** (attempt - 1)) * (0.5 + self._jitter() / 2)

    def handle_request(self, request: httpx.Request) -> httpx.Response:
        attempt = 0
        while True:
            response = self._inner.handle_request(request)
            if not _retryable(response.status_code, request) or attempt >= self._max_retries:
                return response
            retry_after = _parse_retry_after(response.headers.get("retry-after"))
            response.close()
            attempt += 1
            self._sleep(self._delay(attempt, retry_after))

    def close(self) -> None:
        self._inner.close()


def raise_for_problem(response: httpx.Response) -> None:
    """httpx response hook: turn any 4xx/5xx into a typed AgentStoreApiError."""
    if response.status_code < 400:
        return
    response.read()
    try:
        body = response.json()
    except ValueError:
        body = None
    if not isinstance(body, dict):
        body = {"detail": response.text[:500]}
    raise error_from_problem(response.status_code, body, response.headers.get("retry-after"))
