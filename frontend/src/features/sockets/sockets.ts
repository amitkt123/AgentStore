import { humanize } from "@/lib/format";
import type { Manifest } from "@/lib/api/types";

export interface Socket { resource: string; label: string; description: string; requiredColumns: string[] }

export function socketsOf(m: Manifest): Socket[] {
  return m.scopes
    .filter((s) => s.type === "READ")
    .map((s) => ({
      resource: s.resource,
      label: humanize(s.resource),
      description: s.description,
      requiredColumns: m.resources?.find((r) => r.name === s.resource)?.schema?.required ?? [],
    }));
}

export interface Write { resource: string; label: string; description: string; requiresApproval: boolean }

export const writesOf = (m: Manifest): Write[] =>
  m.scopes
    .filter((s) => s.type === "WRITE")
    .map((s) => ({ resource: s.resource, label: humanize(s.resource), description: s.description, requiresApproval: s.requiresApproval }));

export const grantedScopesOf = (m: Manifest): string[] => m.scopes.map((s) => s.resource);
export const defaultCap = (m: Manifest): number => m.costEnvelope.maxPerRun;
