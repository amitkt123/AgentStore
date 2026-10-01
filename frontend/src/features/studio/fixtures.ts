import type { Manifest } from "@/lib/api/types";

export const manifest = (over: Partial<Manifest> = {}): Manifest => ({
  name: "Bank Reconciliation",
  version: "1.3.0",
  scopes: [
    { type: "READ", resource: "bank_statement", description: "Read the statement", requiresApproval: false },
    { type: "WRITE", resource: "journal_entries", description: "Post entries", requiresApproval: true },
  ],
  networkAllowlist: ["api.example.com"],
  costEnvelope: { currency: "INR", maxPerRun: 500, pricingUnit: "METERED", unitPrice: 2, unitLabel: "row" },
  approvalGates: [{ trigger: "post_entries", description: "Approve before posting", timeoutMinutes: 60, preview: null }],
  dataClasses: ["FINANCIAL"],
  resources: [{ name: "vendor_master", format: "CSV", schema: null }],
  llm: null,
  runtime: "python-3.12",
  entrypoint: "main.py",
  ...over,
});

export const empty = manifest({ scopes: [], networkAllowlist: [], approvalGates: [], dataClasses: [], resources: null, llm: null, runtime: null, entrypoint: null });
