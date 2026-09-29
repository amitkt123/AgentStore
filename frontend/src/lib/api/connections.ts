import { api, json } from "./http";
import type { Binding, ConnectionSummary } from "./types";

export const listMyConnections = () => api<ConnectionSummary[]>("/api/v1/connections/mine");
export const createConnection = (b: { agentId: string; grantedScopes: string[]; spendCap: number }) =>
  api<{ id: string; agentId: string; grantedScopes: string[]; spendCap: number; status: string }>("/api/v1/connections", { method: "POST", body: json(b) });
export const bindResource = (connectionId: string, resource: string, datasetId: string) =>
  api<Binding>(`/api/v1/connections/${connectionId}/bindings/${encodeURIComponent(resource)}`, { method: "PUT", body: json({ datasetId }) });
export const unbindResource = (connectionId: string, resource: string) =>
  api<void>(`/api/v1/connections/${connectionId}/bindings/${encodeURIComponent(resource)}`, { method: "DELETE" });
export const listBindings = (connectionId: string) => api<Binding[]>(`/api/v1/connections/${connectionId}/bindings`);
