"use client";

import { useState } from "react";
import { useRunPolling } from "@/components/useRunPolling";
import { RunTraceStep } from "@/components/RunTraceStep";
import { formatInr } from "@/lib/format";
import type { RunDetailResponse } from "@/lib/types";
import { approveRunAction, rejectRunAction } from "./actions";

const TERMINAL_LABEL: Partial<Record<RunDetailResponse["status"], string>> = {
  FAILED: "Stopped — spend limit reached",
  CANCELLED: "Rejected",
  COMPLETED: "Completed",
};

export function RunConsole({ runId, initial }: { runId: string; initial: RunDetailResponse }) {
  const { data } = useRunPolling(runId, initial);
  const [deciding, setDeciding] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = data ?? initial;

  async function decide(approve: boolean) {
    setDeciding(true);
    setError(null);
    try {
      if (approve) await approveRunAction(runId);
      else await rejectRunAction(runId);
    } catch {
      setError("Could not record your decision. Please try again.");
    } finally {
      setDeciding(false);
    }
  }

  return (
    <div className="grid grid-cols-[1fr_320px] gap-8 items-start">
      <div>
        <h1 className="font-serif text-2xl mb-1">Run {runId.slice(0, 8)}</h1>
        <p className="text-sm text-ink2 mb-6">{TERMINAL_LABEL[run.status] ?? run.status}</p>
        <div>
          {run.steps.map((step, i) => (
            <RunTraceStep key={i} step={step} />
          ))}
        </div>
        {run.status === "AWAITING_APPROVAL" && (
          <div className="mt-4 border border-amber bg-amberSoft rounded-lg p-4">
            <p className="mb-3 text-sm">This run needs your decision before it can continue.</p>
            {error && <p className="text-red mb-3 text-sm">{error}</p>}
            <button disabled={deciding} onClick={() => decide(true)} className="bg-green text-white rounded-md px-4 py-2 mr-2">
              Approve
            </button>
            <button disabled={deciding} onClick={() => decide(false)} className="border border-line rounded-md px-4 py-2">
              Reject
            </button>
          </div>
        )}
      </div>
      <div className="border border-line rounded-lg p-4">
        <h4 className="font-semibold mb-3 text-sm">Spend on this run</h4>
        <div className="flex justify-between text-sm py-1">
          <span>Charged so far</span>
          <span>{formatInr(run.totalCharged)}</span>
        </div>
        <div className="flex justify-between text-sm py-1">
          <span>Limit for this run</span>
          <span>{formatInr(run.costCap)}</span>
        </div>
      </div>
    </div>
  );
}
