"use server";

import { revalidatePath } from "next/cache";
import { backend, BackendError } from "@/lib/backend";
import type { CreatedApiKey } from "@/lib/types";

export async function createApiKeyAction(name: string) {
  try {
    const created = await backend.post<CreatedApiKey>("/api/v1/developer/api-keys", { name });
    revalidatePath("/dashboard/api-keys");
    return { ok: true as const, key: created.key };
  } catch (e) {
    const rejected = e instanceof BackendError && e.status === 400;
    return { ok: false as const, error: rejected ? "Give the key a name of up to 100 characters" : "Could not create the key" };
  }
}

export async function revokeApiKeyAction(id: string) {
  try {
    await backend.delete<void>(`/api/v1/developer/api-keys/${id}`);
    revalidatePath("/dashboard/api-keys");
    return { ok: true as const };
  } catch {
    return { ok: false as const, error: "Could not revoke the key" };
  }
}
