"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import type { RunSummaryResponse } from "@/lib/types";

export function ApprovalsBadge() {
  const { data } = useQuery({
    queryKey: ["runs", "awaiting-approval"],
    refetchInterval: 10000,
    queryFn: async () => {
      const res = await fetch("/api/runs/mine?status=AWAITING_APPROVAL");
      if (!res.ok) throw new Error("Failed to load approvals");
      return (await res.json()) as RunSummaryResponse[];
    },
  });
  const count = data?.length ?? 0;
  return (
    <Link href="/runs" className="flex justify-between px-3 py-2 rounded-md text-ink2 hover:bg-paper2">
      <span>Approvals</span>
      {count > 0 && <span className="text-xs text-muted">{count} waiting</span>}
    </Link>
  );
}
