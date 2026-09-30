import { blockKind, blocksFromManifest, toBlockViews } from "./blocks";
import type { Manifest } from "@/lib/api/types";

const manifest: Manifest = {
  name: "Bank Reconciliation", version: "1.3.0",
  scopes: [
    { type: "READ", resource: "bank_statement", description: "Your statement lines, CSV", requiresApproval: false },
    { type: "WRITE", resource: "ledger", description: "Post to your ledger", requiresApproval: true },
  ],
  networkAllowlist: [], dataClasses: ["FINANCIAL"], resources: null, llm: {},
  costEnvelope: { currency: "INR", maxPerRun: 500, pricingUnit: "METERED", unitPrice: 2, unitLabel: "match" },
  approvalGates: [{ trigger: "post_entries", description: "Before anything is posted", timeoutMinutes: 30, preview: null }],
};

it("derives block ids in the backend's order", () => {
  expect(blocksFromManifest(manifest).map((b) => b.id)).toEqual(["read:bank_statement", "draft:ledger", "gate:post_entries", "llm", "charge"]);
});

it("maps ids to kinds", () => {
  expect(blockKind("read:x")).toBe("Reads");
  expect(blockKind("write:x")).toBe("Writes");
  expect(blockKind("gate:x")).toBe("Asks you");
  expect(blockKind("llm")).toBe("Thinks");
  expect(blockKind("charge")).toBe("Charges");
});

it("builds titles, bodies and metrics", () => {
  const views = toBlockViews(
    [{ id: "read:bank_statement", state: "DONE", metrics: { count: 412 } }, { id: "charge", state: "IDLE" }],
    manifest,
  );
  expect(views[0]).toMatchObject({ kind: "Reads", title: "Bank statement", body: "Your statement lines, CSV", metric: "412 rows" });
  expect(views[1]).toMatchObject({ kind: "Charges", body: "₹2 per match. Never more than ₹500 a run." });
});

it("flags a block the manifest did not declare", () => {
  const [v] = toBlockViews([{ id: "write:secret", state: "FAILED", flag: "UNDECLARED" }], manifest);
  expect(v.undeclared).toBe(true);
  expect(v.body).toBe("This agent did not say it would do this.");
});
