import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { isTerminalRunStatus, useRunPolling } from "./useRunPolling";
import type { RunDetailResponse } from "@/lib/types";

const runningRun: RunDetailResponse = { id: "r1", displayId: "R-1", terminalReason: null, status: "RUNNING", costCap: 100, totalCharged: 10, steps: [] };
const completedRun: RunDetailResponse = { ...runningRun, status: "COMPLETED" };

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient();
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe("useRunPolling", () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => completedRun });
  });

  it("fetches fresh run data from the local proxy route", async () => {
    const { result } = renderHook(() => useRunPolling("r1", runningRun), { wrapper });
    await waitFor(() => expect(result.current.data?.status).toBe("COMPLETED"));
    expect(global.fetch).toHaveBeenCalledWith("/api/runs/r1");
  });

  it("keeps polling every 2s while the run is non-terminal, then stops once it reaches a terminal status", async () => {
    vi.useFakeTimers();
    try {
      let calls = 0;
      const fetchMock = vi.fn().mockImplementation(async () => {
        calls += 1;
        // Stay RUNNING (non-terminal) for the first two resolved fetches, then turn COMPLETED.
        const data = calls < 3 ? runningRun : completedRun;
        return { ok: true, json: async () => data };
      });
      global.fetch = fetchMock;

      const { result } = renderHook(() => useRunPolling("r1", runningRun), { wrapper });

      async function advance(ms: number) {
        await act(async () => {
          await vi.advanceTimersByTimeAsync(ms);
        });
      }

      // Flush the initial refetch-on-mount fetch (TanStack Query's default staleTime is 0).
      await advance(0);
      const callsAfterMount = fetchMock.mock.calls.length;
      expect(callsAfterMount).toBeGreaterThan(0);
      expect(result.current.data?.status).toBe("RUNNING");

      // Advance in small steps until a second, interval-driven fetch fires. This must happen at
      // (or shortly after) the 2s mark while the run is still non-terminal — if refetchInterval
      // were hardcoded to `false` (never polls) or the terminal check were inverted, no further
      // fetch would ever occur here and this loop would exhaust without the count changing.
      for (let elapsed = 0; elapsed < 3000 && fetchMock.mock.calls.length === callsAfterMount; elapsed += 250) {
        await advance(250);
      }
      expect(fetchMock.mock.calls.length).toBeGreaterThan(callsAfterMount);
      expect(result.current.data?.status).toBe("RUNNING");

      // Keep advancing until the run's status actually flips to COMPLETED (the third fetch).
      for (let elapsed = 0; elapsed < 5000 && result.current.data?.status !== "COMPLETED"; elapsed += 250) {
        await advance(250);
      }
      expect(result.current.data?.status).toBe("COMPLETED");
      const callsAtTerminal = fetchMock.mock.calls.length;

      // Advance well past another poll interval: no further fetches should occur once terminal.
      // If refetchInterval always returned 2000 (never stopping), this would pick up 2-3 more calls.
      await advance(6000);
      expect(fetchMock.mock.calls.length).toBe(callsAtTerminal);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("isTerminalRunStatus", () => {
  it.each(["COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT", "EXPIRED"] as const)("treats %s as terminal", (s) => {
    expect(isTerminalRunStatus(s)).toBe(true);
  });
  it.each(["PENDING", "RUNNING", "AWAITING_APPROVAL"] as const)("keeps polling for %s", (s) => {
    expect(isTerminalRunStatus(s)).toBe(false);
  });
});
