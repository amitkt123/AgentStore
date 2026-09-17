import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useRunPolling } from "./useRunPolling";
import type { RunDetailResponse } from "@/lib/types";

const runningRun: RunDetailResponse = { id: "r1", status: "RUNNING", costCap: 100, totalCharged: 10, steps: [] };
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
});
