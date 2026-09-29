import { api } from "./http";
import type { RunListResponse, RunQuery } from "./types";

export function runQueryString(q: RunQuery = {}): string {
  const p = new URLSearchParams();
  Object.entries(q).forEach(([k, v]) => { if (v !== undefined && v !== "") p.set(k, String(v)); });
  const s = p.toString();
  return s ? `?${s}` : "";
}

export const listRuns = (q: RunQuery = {}) => api<RunListResponse>(`/api/v1/runs${runQueryString(q)}`);
