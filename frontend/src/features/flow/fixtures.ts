// Test fixtures shared by the flow tests.
import type { Manifest } from "@/lib/api/types";
import { blocksFromManifest, toBlockViews, type BlockView } from "./blocks";
import flow from "@/lib/api/fixtures/flow.json";

export const bankManifest: Manifest = {
  name: "Bank Reconciliation", version: "1.3.0",
  scopes: [
    { type: "READ", resource: "bank_statement", description: "Your statement lines, CSV", requiresApproval: false },
    { type: "WRITE", resource: "ledger", description: "Post to your ledger", requiresApproval: true },
  ],
  networkAllowlist: [], dataClasses: ["FINANCIAL"], resources: null, llm: { mode: "PLATFORM", models: ["m"], maxTokensPerRun: 1000, provider: null },
  costEnvelope: { currency: "INR", maxPerRun: 500, pricingUnit: "METERED", unitPrice: 2, unitLabel: "match" },
  approvalGates: [{ trigger: "post_entries", description: "Before anything is posted", timeoutMinutes: 30, preview: { columns: ["date", "debit"] } }],
};

export const manifestViews = (m: Manifest): BlockView[] => toBlockViews(blocksFromManifest(m), m);

export const fixtureViews = (): BlockView[] =>
  toBlockViews(flow.blocks.map((b) => ({ id: b.block, state: b.state as BlockView["state"], metrics: b.metrics, flag: b.flag as "UNDECLARED" | null })), null);
