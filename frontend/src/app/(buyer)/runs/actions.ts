"use server";

import { redirect } from "next/navigation";
import { backend, BackendError } from "@/lib/backend";
import type { RunResponse } from "@/lib/types";

export async function triggerRunAction(connectionId: string) {
  let run: RunResponse;
  try {
    run = await backend.post<RunResponse>("/api/v1/runs", { connectionId });
  } catch (err) {
    if (err instanceof BackendError && err.status === 402) {
      redirect("/runs?error=insufficient-balance");
    }
    throw err;
  }
  redirect(`/runs/${run.id}`);
}
