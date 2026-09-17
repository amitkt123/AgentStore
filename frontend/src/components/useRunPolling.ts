"use client";

import { useQuery } from "@tanstack/react-query";
import type { RunDetailResponse } from "@/lib/types";

const TERMINAL_STATUSES: RunDetailResponse["status"][] = ["COMPLETED", "FAILED", "CANCELLED"];

export function useRunPolling(runId: string, initialData: RunDetailResponse) {
  return useQuery({
    queryKey: ["run", runId],
    initialData,
    refetchInterval: (query) => (TERMINAL_STATUSES.includes(query.state.data?.status ?? "PENDING") ? false : 2000),
    queryFn: async () => {
      const res = await fetch(`/api/runs/${runId}`);
      if (!res.ok) throw new Error("Failed to load run");
      return (await res.json()) as RunDetailResponse;
    },
  });
}
