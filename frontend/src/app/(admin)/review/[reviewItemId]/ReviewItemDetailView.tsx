"use client";

import { useState } from "react";
import { PermissionStatementDoc } from "@/components/PermissionStatementDoc";
import type { ReviewItemDetailResponse } from "@/lib/types";
import { approveReviewItemAction, rejectReviewItemAction } from "./actions";

export function ReviewItemDetailView({ item }: { item: ReviewItemDetailResponse }) {
  const [notes, setNotes] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function decide(approve: boolean) {
    setSubmitting(true);
    setError(null);
    try {
      if (approve) await approveReviewItemAction(item.id, notes);
      else await rejectReviewItemAction(item.id, notes);
    } catch {
      setError("Could not record your decision. Please try again.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div>
      <h1 className="font-serif text-2xl mb-1">
        {item.agentName} — v{item.versionNumber}
      </h1>
      <p className="text-ink2 mb-6">Submitted {new Date(item.createdAt).toLocaleString()}</p>
      <PermissionStatementDoc manifest={item.manifest} mode="listing" />
      <label className="block text-sm font-medium mt-6 mb-1">Notes</label>
      <textarea value={notes} onChange={(e) => setNotes(e.target.value)} className="border border-line rounded-lg px-3 py-2 w-full mb-4" />
      {error && <p className="text-red mb-3 text-sm">{error}</p>}
      <div className="flex gap-2">
        <button disabled={submitting} onClick={() => decide(true)} className="bg-green text-white rounded-md px-4 py-2">
          Approve
        </button>
        <button disabled={submitting} onClick={() => decide(false)} className="border border-line rounded-md px-4 py-2">
          Reject
        </button>
      </div>
    </div>
  );
}
