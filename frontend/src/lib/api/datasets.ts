import { api, json } from "./http";
import type { CheckResult, Dataset, Storage } from "./types";

export const listDatasets = () => api<Dataset[]>("/api/v1/datasets?include=usage");
// The backend rejects an upload without a displayName, so default it to the file name.
export function uploadDataset(fd: FormData) {
  const file = fd.get("file");
  if (!fd.get("displayName") && file instanceof File) fd.set("displayName", file.name);
  return api<Dataset>("/api/v1/datasets", { method: "POST", body: fd });
}
export const deleteDataset = (id: string) => api<void>(`/api/v1/datasets/${id}`, { method: "DELETE" });
export const checkDataset = (id: string, agentVersionId: string, resource: string) =>
  api<CheckResult>(`/api/v1/datasets/${id}/check`, { method: "POST", body: json({ agentVersionId, resource }) });
export const getStorage = (orgId: string) => api<Storage>(`/api/v1/orgs/${orgId}/storage`);
