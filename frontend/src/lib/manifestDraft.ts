import type { Manifest, ManifestDraft } from "./types";

export function emptyManifestDraft(agentName: string): ManifestDraft {
  return {
    name: agentName,
    version: "1.0.0",
    scopes: [],
    network: { allowlist: [] },
    costEnvelope: { currency: "INR", maxPerRun: 0, pricingUnit: "OUTCOME", unitPrice: 0, unitLabel: "" },
    modelRequirements: { provider: "anthropic", model: "claude-sonnet-5" },
    approvalGates: [],
    dataClasses: [],
  };
}

export function toParsedManifest(draft: ManifestDraft): Manifest {
  return {
    name: draft.name,
    version: draft.version,
    scopes: draft.scopes,
    networkAllowlist: draft.network.allowlist,
    costEnvelope: draft.costEnvelope,
    approvalGates: draft.approvalGates,
    dataClasses: draft.dataClasses,
  };
}
