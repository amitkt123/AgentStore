"use server";
import { revalidatePath } from "next/cache";
import { ApiError } from "@/lib/api/http";
import { decideGate } from "@/lib/api/runs";

export async function decideGateAction(runRef: string, gateId: string, decision: "APPROVE" | "REJECT", note?: string): Promise<{ error?: string }> {
  try {
    await decideGate(runRef, gateId, decision, note);
    revalidatePath(`/runs/${runRef}`);
    return {};
  } catch (e) {
    if (e instanceof ApiError) return { error: e.message };
    throw e;
  }
}
