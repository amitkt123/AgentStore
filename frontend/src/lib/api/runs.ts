import { api, json } from "./http";
import type { FlowView, GateDetail, RunDetail, RunListResponse, RunQuery, RunResponse, RunSnapshot } from "./types";

export function runQueryString(q: RunQuery = {}): string {
  const p = new URLSearchParams();
  Object.entries(q).forEach(([k, v]) => { if (v !== undefined && v !== "") p.set(k, String(v)); });
  const s = p.toString();
  return s ? `?${s}` : "";
}

const enc = encodeURIComponent;

export const listRuns = (q: RunQuery = {}) => api<RunListResponse>(`/api/v1/runs${runQueryString(q)}`);
export const getRunDetail = (ref: string) => api<RunDetail>(`/api/v1/runs/${enc(ref)}`);
export const getRunFlow = (ref: string) => api<FlowView>(`/api/v1/runs/${enc(ref)}/flow`);
export const getRunGates = (ref: string) => api<GateDetail[]>(`/api/v1/runs/${enc(ref)}/gates`);

export async function getRunSnapshot(ref: string): Promise<RunSnapshot> {
  const [detail, flow, gates] = await Promise.all([getRunDetail(ref), getRunFlow(ref), getRunGates(ref)]);
  return { detail, flow, gates };
}

export const decideGate = (runRef: string, gateId: string, decision: "APPROVE" | "REJECT", note?: string) =>
  api<RunResponse>(`/api/v1/runs/${enc(runRef)}/gates/${gateId}/decision`, { method: "POST", body: json({ decision, note: note ?? null }) });

export const triggerRun = (connectionId: string) =>
  api<RunResponse>("/api/v1/runs", { method: "POST", body: json({ connectionId }) });
