from __future__ import annotations

import os
import traceback
from typing import Callable

from ._api import Api
from .context import AgentContext
from .heartbeat import Heartbeat

_REQUIRED = ("AGENTSTORE_API_URL", "AGENTSTORE_RUN_TOKEN")


def _outputs(result):
    if result is None:
        return None
    if isinstance(result, dict):
        return result
    raise TypeError(f"handler must return a dict or None, got {type(result).__name__}")


def run(handler: Callable[[AgentContext], dict | None], *, env=None, api=None,
        heartbeat_interval: float = 15.0) -> None:
    env = os.environ if env is None else env
    for name in _REQUIRED:
        if not env.get(name):
            raise RuntimeError(f"{name} is not set; agentstore.run() must be started by the AgentStore sandbox")
    api = api or Api(env["AGENTSTORE_API_URL"], env["AGENTSTORE_RUN_TOKEN"])
    ctx = AgentContext(api, api.context())
    heartbeat = Heartbeat(api, heartbeat_interval)
    heartbeat.start()
    try:
        try:
            outputs = _outputs(handler(ctx))
        except Exception as exc:
            ctx.log.error(f"handler failed: {exc!r}", traceback=traceback.format_exc())
            ctx.log.flush()
            heartbeat.stop()
            try:
                api.fail(error=f"{type(exc).__name__}: {exc}", retriable=False)
            except Exception:
                pass                                        # never mask the handler's own exception
            raise
        ctx.log.flush()
        heartbeat.stop()
        api.complete(outputs=outputs)
    finally:
        heartbeat.stop()
