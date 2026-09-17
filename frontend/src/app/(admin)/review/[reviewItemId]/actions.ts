"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { backend } from "@/lib/backend";
import type { ReviewItemResponse } from "@/lib/types";

export async function approveReviewItemAction(reviewItemId: string, notes: string) {
  await backend.post<ReviewItemResponse>(`/api/v1/review/${reviewItemId}/approve`, { notes });
  revalidatePath("/review");
  redirect("/review");
}

export async function rejectReviewItemAction(reviewItemId: string, notes: string) {
  await backend.post<ReviewItemResponse>(`/api/v1/review/${reviewItemId}/reject`, { notes });
  revalidatePath("/review");
  redirect("/review");
}
