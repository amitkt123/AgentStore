import { api, json } from "./http";
import type { DeveloperVerification, VerificationStep } from "./types";

const base = "/api/v1/developer/verification";

export const getVerification = () => api<DeveloperVerification>(base);
export const patchVerification = (step: VerificationStep, fields: Record<string, string>) =>
  api<DeveloperVerification>(base, { method: "PATCH", body: json({ step, fields }) });
export const uploadPanDocument = (fd: FormData) => api<DeveloperVerification>(`${base}/pan-document`, { method: "POST", body: fd });
export const submitVerification = () => api<DeveloperVerification>(`${base}/submit`, { method: "POST" });
