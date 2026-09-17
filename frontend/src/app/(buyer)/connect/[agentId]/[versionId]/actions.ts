"use server";

import { backend, BackendError } from "@/lib/backend";
import type { ConnectionResponse } from "@/lib/types";

interface CreateConnectionInput {
  agentId: string;
  grantedScopes: string[];
  spendCap: number;
}

export async function createConnectionAction(input: CreateConnectionInput) {
  try {
    const connection = await backend.post<ConnectionResponse>("/api/v1/connections", input);
    return { ok: true as const, connectionId: connection.id };
  } catch (e) {
    if (e instanceof BackendError) {
      const message =
        typeof e.body === "object" && e.body && "message" in e.body
          ? String((e.body as { message: unknown }).message)
          : "Could not create connection";
      return { ok: false as const, error: message };
    }
    throw e;
  }
}
