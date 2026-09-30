"use server";
import { revalidatePath } from "next/cache";
import { ApiError } from "@/lib/api/http";
import { unbindResource } from "@/lib/api/connections";
import { deleteDataset, uploadDataset } from "@/lib/api/datasets";

const fail = (e: unknown): { error: string } => {
  if (e instanceof ApiError) return { error: e.message };
  throw e;
};

export async function uploadFileAction(fd: FormData): Promise<{ error?: string }> {
  try {
    await uploadDataset(fd);
    revalidatePath("/files");
    return {};
  } catch (e) { return fail(e); }
}

export async function deleteFileAction(datasetId: string, usage: { connectionId: string; resource: string }[]): Promise<{ error?: string }> {
  try {
    for (const u of usage) await unbindResource(u.connectionId, u.resource);
    await deleteDataset(datasetId);
    revalidatePath("/files");
    return {};
  } catch (e) { return fail(e); }
}
