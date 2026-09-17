import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PermissionStatementDoc } from "./PermissionStatementDoc";
import type { Manifest } from "@/lib/types";

const manifest: Manifest = {
  name: "GSTR-2B Reconciler",
  version: "4.2",
  scopes: [{ type: "READ", resource: "purchase_register", description: "Tally purchase register" }],
  networkAllowlist: ["gst.gov.in"],
  costEnvelope: { currency: "INR", maxPerRun: 25000, pricingUnit: "OUTCOME", unitPrice: 18, unitLabel: "matched_invoice" },
  approvalGates: [{ trigger: "MARK_UNCLAIMABLE_ABOVE_THRESHOLD", description: "Marking a credit unclaimable above ₹50,000" }],
  dataClasses: [],
};

describe("PermissionStatementDoc", () => {
  it("renders read-only scope rows in listing mode, with no interactive toggle", () => {
    render(<PermissionStatementDoc manifest={manifest} mode="listing" />);
    expect(screen.getByText("purchase_register")).toBeInTheDocument();
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
  });

  it("renders interactive toggles in grant mode", () => {
    render(<PermissionStatementDoc manifest={manifest} mode="grant" onGrantChange={() => {}} />);
    expect(screen.getByRole("switch", { name: /purchase_register/i })).toBeInTheDocument();
  });

  it("shows the approval gate and the per-run spend cap", () => {
    render(<PermissionStatementDoc manifest={manifest} mode="listing" />);
    expect(screen.getByText(/Marking a credit unclaimable/)).toBeInTheDocument();
    expect(screen.getByText("₹25,000.00")).toBeInTheDocument();
  });
});
