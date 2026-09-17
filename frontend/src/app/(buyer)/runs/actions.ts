"use server";

import { redirect } from "next/navigation";
import { backend } from "@/lib/backend";
import type { RunResponse } from "@/lib/types";

export async function triggerRunAction(connectionId: string) {
  const run = await backend.post<RunResponse>("/api/v1/runs", { connectionId });
  redirect(`/runs/${run.id}`);
}
