"use client";

import { useQuery } from "@tanstack/react-query";
import type { RunDetailResponse, RunStatus } from "@/lib/types";

const TERMINAL_STATUSES: RunStatus[] = ["COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT", "EXPIRED"];

export function isTerminalRunStatus(status: RunStatus): boolean {
  return TERMINAL_STATUSES.includes(status);
}

export function useRunPolling(runId: string, initialData: RunDetailResponse) {
  return useQuery({
    queryKey: ["run", runId],
    initialData,
    refetchInterval: (query) => (isTerminalRunStatus(query.state.data?.status ?? "PENDING") ? false : 2000),
    queryFn: async () => {
      const res = await fetch(`/api/runs/${runId}`);
      if (!res.ok) throw new Error("Failed to load run");
      return (await res.json()) as RunDetailResponse;
    },
  });
}
