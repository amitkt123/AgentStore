import httpx
import pytest

from agentstore._api import Api


def make_api(handler, **retry):
    opts = {"sleep": lambda s: None, "jitter": lambda: 0.0, **retry}
    return Api("http://runtime.test", "tok-123", transport=httpx.MockTransport(handler), retry=opts)


class FakeApi:
    """Stand-in for Api. FakeApi(records=[page1, page2], fail=[SomeError()]): each named method consumes its list
    (raising if the item is an Exception), returns a plain value as-is, or calls a callable. Unnamed -> None."""

    def __init__(self, **scripted):
        self.calls = []
        self._scripted = scripted

    def __getattr__(self, name):
        def call(*args, **kwargs):
            self.calls.append((name, args, kwargs))
            r = self._scripted.get(name)
            if callable(r):
                return r(*args, **kwargs)
            if isinstance(r, list):
                if not r:
                    return None
                item = r.pop(0)
                if isinstance(item, Exception):
                    raise item
                return item
            return r
        return call

    def names(self):
        return [c[0] for c in self.calls]


@pytest.fixture
def fake_api():
    return FakeApi
