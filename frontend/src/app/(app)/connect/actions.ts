"use server";
import { ApiError } from "@/lib/api/http";
import { bindResource, createConnection } from "@/lib/api/connections";
import { checkDataset, uploadDataset } from "@/lib/api/datasets";
import { triggerRun } from "@/lib/api/runs";
import type { CheckResult, Dataset } from "@/lib/api/types";

async function guard<T extends object>(fn: () => Promise<T>): Promise<T | { error: string }> {
  try { return await fn(); } catch (e) {
    if (e instanceof ApiError) return { error: e.message };
    throw e;
  }
}

export const uploadForSocketAction = (fd: FormData) => guard<{ dataset: Dataset }>(async () => ({ dataset: await uploadDataset(fd) }));
export const checkSocketAction = (datasetId: string, versionId: string, resource: string) =>
  guard<{ result: CheckResult }>(async () => ({ result: await checkDataset(datasetId, versionId, resource) }));
export const createConnectionAction = (agentId: string, grantedScopes: string[], spendCap: number) =>
  guard<{ connectionId: string }>(async () => ({ connectionId: (await createConnection({ agentId, grantedScopes, spendCap })).id }));
export const bindSocketAction = (connectionId: string, resource: string, datasetId: string) =>
  guard<{ ok: true }>(async () => { await bindResource(connectionId, resource, datasetId); return { ok: true as const }; });
export const startRunAction = (connectionId: string) => guard<{ displayId: string }>(async () => ({ displayId: (await triggerRun(connectionId)).displayId }));
