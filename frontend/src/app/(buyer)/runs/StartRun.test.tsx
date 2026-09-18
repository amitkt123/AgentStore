import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { StartRun } from "./StartRun";
import type { ConnectionSummaryResponse } from "@/lib/types";

vi.mock("./actions", () => ({
  triggerRunAction: vi.fn(),
}));

const connections: ConnectionSummaryResponse[] = [
  {
    id: "c1",
    agentId: "a1",
    agentName: "GSTR-2B Reconciler",
    grantedScopes: ["purchase_register"],
    spendCap: 25000,
    status: "ACTIVE",
  },
];

describe("StartRun", () => {
  it("renders an enabled Run now button when the wallet can cover the spend cap", () => {
    render(<StartRun connections={connections} walletBalance={50000} />);
    const button = screen.getByRole("button", { name: /GSTR-2B Reconciler/i });
    expect(button).not.toBeDisabled();
    expect(screen.getByText("Run now")).toBeInTheDocument();
  });

  it("disables the button and shows the shortfall when the wallet balance is too low", () => {
    render(<StartRun connections={connections} walletBalance={10000} />);
    const button = screen.getByRole("button", { name: /GSTR-2B Reconciler/i });
    expect(button).toBeDisabled();
    expect(screen.getByText(/Insufficient balance — need/i)).toBeInTheDocument();
  });

  it("renders nothing when there are no active connections", () => {
    const { container } = render(<StartRun connections={[]} walletBalance={50000} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("does not gate on balance when the wallet balance is unknown (e.g. a non-BUYER_ADMIN role)", () => {
    render(<StartRun connections={connections} walletBalance={null} />);
    const button = screen.getByRole("button", { name: /GSTR-2B Reconciler/i });
    expect(button).not.toBeDisabled();
    expect(screen.getByText("Run now")).toBeInTheDocument();
  });
});
