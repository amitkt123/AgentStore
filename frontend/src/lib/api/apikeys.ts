import { api, json } from "./http";
import type { CreatedApiKey, DeveloperApiKey } from "./types";

const base = "/api/v1/developer/api-keys";

export const listApiKeys = () => api<DeveloperApiKey[]>(base);
export const createApiKey = (name: string) => api<CreatedApiKey>(base, { method: "POST", body: json({ name }) });
export const revokeApiKey = (id: string) => api<void>(`${base}/${id}`, { method: "DELETE" });
