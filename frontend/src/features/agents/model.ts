import type { Tone } from "@/ui/Pill";
import type { ApiError } from "@/lib/api/http";
import type { Blocker, BlockerCode, ManifestPatchRequest, VersionStatus } from "@/lib/api/types";

export interface Failure { text: string; field?: "name" | "category" | "versionNumber" | "manifest"; verify?: boolean }

export const statusLabel: Record<VersionStatus, string> = { DRAFT: "Draft", IN_REVIEW: "In review", LIVE: "Live", REJECTED: "Rejected" };
export const statusTone: Record<VersionStatus, Tone> = { DRAFT: "neutral", IN_REVIEW: "warn", LIVE: "ok", REJECTED: "bad" };

// The platform hides the reason on plain HTTP errors and http.ts then falls back to "Request failed (N)". That is never worth showing.
const reason = (e: ApiError): string | null => (/^Request failed \(\d+\)$/.test(e.message) ? null : e.message);

export function createAgentFailure(e: ApiError): Failure {
  if (e.status === 400) return { text: "Give the agent a name and a category." };
  return { text: reason(e) ?? "Couldn't create the agent. Try again." };
}

export function createVersionFailure(e: ApiError): Failure {
  if (e.status === 400) return { text: "Karya couldn't accept this manifest. Check that it has every required block and try again.", field: "manifest" };
  if (e.status === 403) return { text: "That agent belongs to another organisation." };
  if (e.status === 404) return { text: "That agent no longer exists." };
  return { text: reason(e) ?? "Couldn't create the version. Try again." };
}

export function patchFailure(e: ApiError): Failure {
  if (e.code === "VERSION_NOT_EDITABLE") return { text: "This version can no longer be edited." };
  if (e.code) return { text: e.message };
  if (e.status === 403 || e.status === 404) return { text: "You can't edit this version." };
  return { text: reason(e) ?? "That change didn't go through. Try again." };
}

export function submitFailure(e: ApiError): Failure {
  if (e.code === "BYOK_NOT_VERIFIED") return { text: "Only verified developers can submit an agent that uses its own LLM key. Get verified first.", verify: true };
  if (e.code === "VERIFICATION_REQUIRED") return { text: "Get verified before you submit an agent for listing.", verify: true };
  if (e.status === 409) return { text: "This version can't be submitted yet. Check that it has a successful build and a valid manifest, then try again." };
  if (e.status === 403 || e.status === 404) return { text: "You can't submit this version." };
  return { text: reason(e) ?? "Couldn't submit this version. Try again." };
}

// A read-only agent goes straight to LIVE; one with write scopes or its own LLM key waits for review.
export function submitMessage(status: VersionStatus): string {
  if (status === "LIVE") return "Submitted and published. This version is live.";
  if (status === "IN_REVIEW") return "Submitted. Karya will review this version.";
  return "Submitted.";
}

// Built from what the developer asked for: the response diff is against the LIVE version, not against this edit.
export function patchConfirmation(req: ManifestPatchRequest): string {
  switch (req.op) {
    case "add_read_scope": return `Added read scope ${req.resource}`;
    case "add_resource": return `Added resource ${req.name}`;
    case "add_allowlist_host": return `Added host ${req.host}`;
    case "remove_scope": return `Removed ${(req.type ?? "").toLowerCase()} scope ${req.resource}`;
  }
}

export interface BlockerRow { code: BlockerCode; message: string; runId: string | null; count: number }

// The backend adds one identical blocker per sandbox issue; show each distinct one once with a count.
export function groupBlockers(blockers: Blocker[]): BlockerRow[] {
  const rows = new Map<string, BlockerRow>();
  for (const b of blockers) {
    const key = `${b.code}|${b.runId ?? ""}|${b.message}`;
    const row = rows.get(key);
    if (row) row.count += 1;
    else rows.set(key, { code: b.code, message: b.message, runId: b.runId, count: 1 });
  }
  return [...rows.values()];
}

export function parseManifestInput(text: string): { ok: true; text: string } | { ok: false; error: string } {
  const trimmed = text.trim();
  if (!trimmed) return { ok: false, error: "Paste the manifest JSON." };
  let parsed: unknown;
  try { parsed = JSON.parse(trimmed); } catch { return { ok: false, error: "That isn't valid JSON." }; }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return { ok: false, error: "The manifest must be a JSON object." };
  return { ok: true, text: trimmed };
}
