import { api, json } from "./http";
import type { CreatedAgent, ManifestPatchRequest, ManifestPatchResult, MyAgent, Readiness, VersionSummary } from "./types";

export { getVersion } from "./catalog";

const base = "/api/v1/agents";
const versionPath = (agentId: string, versionId: string) => `${base}/${agentId}/versions/${versionId}`;

export const listMyAgents = () => api<MyAgent[]>(`${base}/mine`);
export const createAgent = (body: { name: string; category: string; description?: string }) =>
  api<CreatedAgent>(base, { method: "POST", body: json(body) });
export const createVersion = (agentId: string, body: { versionNumber: string; manifestJson: string; changelog?: string }) =>
  api<VersionSummary>(`${base}/${agentId}/versions`, { method: "POST", body: json(body) });
export const getReadiness = (agentId: string, versionId: string) => api<Readiness>(`${versionPath(agentId, versionId)}/readiness`);
export const patchManifest = (agentId: string, versionId: string, body: ManifestPatchRequest) =>
  api<ManifestPatchResult>(`${versionPath(agentId, versionId)}/manifest/patches`, { method: "POST", body: json(body) });
export const submitVersion = (agentId: string, versionId: string) =>
  api<VersionSummary>(`${versionPath(agentId, versionId)}/submit`, { method: "POST" });
