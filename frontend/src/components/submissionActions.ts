"use server";

import { backend, BackendError } from "@/lib/backend";
import type { AgentResponse, AgentVersionResponse } from "@/lib/types";

function errorMessage(e: unknown, fallback: string): string {
  if (e instanceof BackendError) {
    if (Array.isArray(e.body)) return e.body.join("; ");
    if (typeof e.body === "object" && e.body && "message" in e.body) return String((e.body as { message: unknown }).message);
  }
  return fallback;
}

export async function createAgentAction(input: { name: string; category: string; description: string }) {
  try {
    const agent = await backend.post<AgentResponse>("/api/v1/agents", input);
    return { ok: true as const, agentId: agent.id };
  } catch (e) {
    return { ok: false as const, error: errorMessage(e, "Could not create agent") };
  }
}

export async function createVersionAction(
  agentId: string,
  input: { versionNumber: string; manifestJson: string; imageRef: string; changelog: string },
) {
  try {
    const version = await backend.post<AgentVersionResponse>(`/api/v1/agents/${agentId}/versions`, input);
    return { ok: true as const, versionId: version.id };
  } catch (e) {
    return { ok: false as const, error: errorMessage(e, "Could not create version — check the manifest fields") };
  }
}

export async function submitVersionAction(agentId: string, versionId: string) {
  try {
    await backend.post<AgentVersionResponse>(`/api/v1/agents/${agentId}/versions/${versionId}/submit`);
    return { ok: true as const };
  } catch (e) {
    return { ok: false as const, error: errorMessage(e, "Could not submit for review") };
  }
}
