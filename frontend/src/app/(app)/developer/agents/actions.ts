"use server";
import { revalidatePath } from "next/cache";
import { ApiError } from "@/lib/api/http";
import { createAgent, createVersion, patchManifest, submitVersion } from "@/lib/api/agents";
import { createAgentFailure, createVersionFailure, patchConfirmation, patchFailure, submitFailure, submitMessage, type Failure } from "@/features/agents/model";
import type { ManifestPatchRequest, VersionStatus } from "@/lib/api/types";

export type Outcome = { ok: true; message?: string; status?: VersionStatus } | { ok: false; failure: Failure };

const list = "/developer/agents";
const studio = (agentId: string, versionId: string) => `${list}/${agentId}/versions/${versionId}`;

async function run(fn: () => Promise<{ message?: string; status?: VersionStatus }>, toFailure: (e: ApiError) => Failure, paths: string[]): Promise<Outcome> {
  try {
    const rest = await fn();
    paths.forEach((p) => revalidatePath(p));
    return { ok: true, ...rest };
  } catch (e) {
    if (e instanceof ApiError) return { ok: false, failure: toFailure(e) };
    throw e;
  }
}

export async function createAgentAction(input: { name: string; category: string; description: string }): Promise<Outcome> {
  const description = input.description.trim();
  return run(async () => {
    await createAgent({ name: input.name.trim(), category: input.category.trim(), ...(description ? { description } : {}) });
    return {};
  }, createAgentFailure, [list]);
}

export async function createVersionAction(agentId: string, input: { versionNumber: string; manifestJson: string; changelog: string }): Promise<Outcome> {
  const changelog = input.changelog.trim();
  return run(async () => {
    await createVersion(agentId, { versionNumber: input.versionNumber.trim(), manifestJson: input.manifestJson, ...(changelog ? { changelog } : {}) });
    return {};
  }, createVersionFailure, [list]);
}

export async function patchAction(agentId: string, versionId: string, req: ManifestPatchRequest): Promise<Outcome> {
  return run(async () => {
    await patchManifest(agentId, versionId, req);
    return { message: patchConfirmation(req) };
  }, patchFailure, [studio(agentId, versionId)]);
}

export async function submitAction(agentId: string, versionId: string): Promise<Outcome> {
  return run(async () => {
    const version = await submitVersion(agentId, versionId);
    return { message: submitMessage(version.status), status: version.status };
  }, submitFailure, [studio(agentId, versionId), list]);
}
