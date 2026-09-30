"use server";
import { revalidatePath } from "next/cache";
import { ApiError } from "@/lib/api/http";
import { createApiKey, revokeApiKey } from "@/lib/api/apikeys";
import type { CreatedApiKey } from "@/lib/api/types";

const fail = (e: unknown): { error: string } => {
  if (e instanceof ApiError) return { error: e.message };
  throw e;
};

export async function createKeyAction(name: string): Promise<{ key: CreatedApiKey } | { error: string }> {
  try {
    const key = await createApiKey(name);
    revalidatePath("/developer/api-keys");
    return { key };
  } catch (e) { return fail(e); }
}

export async function revokeKeyAction(id: string): Promise<{ error?: string }> {
  try {
    await revokeApiKey(id);
    revalidatePath("/developer/api-keys");
    return {};
  } catch (e) { return fail(e); }
}
