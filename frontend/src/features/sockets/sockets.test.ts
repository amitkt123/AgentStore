import { defaultCap, grantedScopesOf, socketsOf } from "./sockets";
import type { Manifest } from "@/lib/api/types";

const base: Manifest = {
  name: "A", version: "1", networkAllowlist: [], dataClasses: [], llm: null, approvalGates: [],
  costEnvelope: { currency: "INR", maxPerRun: 500, pricingUnit: "METERED", unitPrice: 2, unitLabel: "match" },
  scopes: [
    { type: "READ", resource: "bank_statement", description: "Statement lines", requiresApproval: false },
    { type: "WRITE", resource: "ledger", description: "Post", requiresApproval: true },
  ],
  resources: [{ name: "bank_statement", format: "CSV", schema: { required: ["date", "amount"], properties: {} } }],
};

it("makes one socket per READ scope with its required columns", () => {
  expect(socketsOf(base)).toEqual([{ resource: "bank_statement", label: "Bank statement", description: "Statement lines", requiredColumns: ["date", "amount"] }]);
});
it("still lists sockets when resources are untyped (null)", () => {
  expect(socketsOf({ ...base, resources: null })[0].requiredColumns).toEqual([]);
});
it("grants every declared scope and defaults the cap to the declared maximum", () => {
  expect(grantedScopesOf(base)).toEqual(["bank_statement", "ledger"]);
  expect(defaultCap(base)).toBe(500);
});
