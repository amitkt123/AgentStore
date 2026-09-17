import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ConnectWizard } from "./ConnectWizard";
import type { Manifest } from "@/lib/types";

vi.mock("./actions", () => ({
  createConnectionAction: vi.fn().mockResolvedValue({ ok: true, connectionId: "c1" }),
}));
const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));

const manifest: Manifest = {
  name: "GSTR-2B Reconciler",
  version: "4.2",
  scopes: [{ type: "READ", resource: "purchase_register", description: "Tally purchase register" }],
  networkAllowlist: [],
  costEnvelope: { currency: "INR", maxPerRun: 25000, pricingUnit: "OUTCOME", unitPrice: 18, unitLabel: "matched_invoice" },
  approvalGates: [],
  dataClasses: [],
};

describe("ConnectWizard", () => {
  it("walks from grant to spend cap to confirm", async () => {
    render(<ConnectWizard agentId="a1" manifest={manifest} />);
    expect(screen.getByRole("heading", { name: /Grant exactly what/i })).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    expect(screen.getByRole("heading", { name: /Set a monthly spend limit/i })).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    expect(screen.getByRole("heading", { name: /Confirm connection/i })).toBeInTheDocument();
  });

  it("submits the granted resources and spend cap, then redirects on success", async () => {
    const { createConnectionAction } = await import("./actions");
    render(<ConnectWizard agentId="a1" manifest={manifest} />);
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    await userEvent.click(screen.getByRole("button", { name: /Confirm/i }));

    expect(createConnectionAction).toHaveBeenCalledWith({
      agentId: "a1",
      grantedScopes: ["purchase_register"],
      spendCap: 25000,
    });
    expect(push).toHaveBeenCalledWith("/runs?connected=c1");
  });
});
