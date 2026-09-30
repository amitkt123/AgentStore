import { api } from "./http";
import type { CatalogListing, VersionDetail } from "./types";

export const listCatalog = () => api<CatalogListing[]>("/api/v1/catalog");
export const getVersion = (agentId: string, versionId: string) => api<VersionDetail>(`/api/v1/agents/${agentId}/versions/${versionId}`);
