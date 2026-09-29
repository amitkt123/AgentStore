import { act, renderHook } from "@testing-library/react";
import { vi } from "vitest";
import { useRunPolling } from "./useRunPolling";

const ok = (b: unknown) => new Response(JSON.stringify(b), { status: 200 });

beforeEach(() => vi.useFakeTimers());
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

it("polls on the interval and stops when done", async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(ok({ n: 1 }))
    .mockResolvedValueOnce(ok({ n: 2, done: true }))
    .mockResolvedValue(ok({ n: 3 }));
  vi.stubGlobal("fetch", fetchMock);
  const { result } = renderHook(() => useRunPolling<{ n: number; done?: boolean }>("/api/x", { intervalMs: 1000, initial: { n: 0 }, isDone: (d) => !!d.done }));
  await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
  expect(result.current.data.n).toBe(1);
  await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
  expect(result.current.data.n).toBe(2);
  await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
  expect(fetchMock).toHaveBeenCalledTimes(2);
});

it("stops and flags forbidden on 403", async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response("", { status: 403 }));
  vi.stubGlobal("fetch", fetchMock);
  const { result } = renderHook(() => useRunPolling("/api/x", { intervalMs: 1000, initial: {} }));
  await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
  expect(result.current.forbidden).toBe(true);
  await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

it("does not poll while the tab is hidden", async () => {
  const fetchMock = vi.fn().mockResolvedValue(ok({}));
  vi.stubGlobal("fetch", fetchMock);
  Object.defineProperty(document, "hidden", { configurable: true, get: () => true });
  renderHook(() => useRunPolling("/api/x", { intervalMs: 1000, initial: {} }));
  await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
  expect(fetchMock).not.toHaveBeenCalled();
  Object.defineProperty(document, "hidden", { configurable: true, get: () => false });
});
