"use server";

import { revalidatePath } from "next/cache";
import { backend } from "@/lib/backend";
import type { RunResponse } from "@/lib/types";

export async function approveRunAction(runId: string) {
  await backend.post<RunResponse>(`/api/v1/runs/${runId}/approve`, { notes: null });
  revalidatePath(`/runs/${runId}`);
}

export async function rejectRunAction(runId: string) {
  await backend.post<RunResponse>(`/api/v1/runs/${runId}/reject`, { notes: null });
  revalidatePath(`/runs/${runId}`);
}
