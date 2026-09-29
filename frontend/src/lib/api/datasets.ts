import { api, json } from "./http";
import type { CheckResult, Dataset, Storage } from "./types";

export const listDatasets = () => api<Dataset[]>("/api/v1/datasets?include=usage");
export const uploadDataset = (fd: FormData) => api<Dataset>("/api/v1/datasets", { method: "POST", body: fd });
export const deleteDataset = (id: string) => api<void>(`/api/v1/datasets/${id}`, { method: "DELETE" });
export const checkDataset = (id: string, agentVersionId: string) =>
  api<CheckResult>(`/api/v1/datasets/${id}/check`, { method: "POST", body: json({ agentVersionId }) });
export const getStorage = (orgId: string) => api<Storage>(`/api/v1/orgs/${orgId}/storage`);
