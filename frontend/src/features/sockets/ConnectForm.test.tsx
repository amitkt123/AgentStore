import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

const a = vi.hoisted(() => ({ upload: vi.fn(), check: vi.fn(), create: vi.fn(), bind: vi.fn(), run: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock("@/app/(app)/connect/actions", () => ({
  uploadForSocketAction: (...x: unknown[]) => a.upload(...x),
  checkSocketAction: (...x: unknown[]) => a.check(...x),
  createConnectionAction: (...x: unknown[]) => a.create(...x),
  bindSocketAction: (...x: unknown[]) => a.bind(...x),
  startRunAction: (...x: unknown[]) => a.run(...x),
}));
import { ConnectForm } from "./ConnectForm";
import type { Dataset, Manifest } from "@/lib/api/types";

const manifest: Manifest = {
  name: "Bank Reconciliation", version: "1.3.0", networkAllowlist: [], dataClasses: [], llm: null, approvalGates: [],
  costEnvelope: { currency: "INR", maxPerRun: 500, pricingUnit: "METERED", unitPrice: 2, unitLabel: "match" },
  scopes: [{ type: "READ", resource: "bank_statement", description: "Statement lines", requiresApproval: false }],
  resources: [{ name: "bank_statement", format: "CSV", schema: { required: ["date"], properties: {} } }],
};
const ds: Dataset = { id: "d1", displayName: "Aug.csv", sourceLabel: null, format: "CSV", rowCount: 412, sizeBytes: 1, columns: ["date"], sha256: "x", uploadedBy: "P", createdAt: "2026-09-01T00:00:00Z", piiScan: null, usage: [] };

beforeEach(() => { Object.values(a).forEach((m) => m.mockReset()); });

it("blocks 'Add and run now' until every socket has a checked file", async () => {
  a.check.mockResolvedValue({ result: { ok: true, rowCount: 412, columnsMatch: true, missingColumns: [], rowErrorCount: 0, rowErrors: [] } });
  render(<ConnectForm agentId="a1" versionId="v1" agentName="Bank Reconciliation" manifest={manifest} datasets={[ds]} canConnect />);
  expect(screen.getByRole("button", { name: /add and run now/i })).toBeDisabled();
  await userEvent.selectOptions(screen.getByLabelText(/bank statement/i), "d1");
  expect(await screen.findByText(/412 rows, columns match/i)).toBeInTheDocument();
  expect(a.check).toHaveBeenCalledWith("d1", "v1", "bank_statement");
  expect(screen.getByRole("button", { name: /add and run now/i })).toBeEnabled();
});

it("shows what is missing when the columns do not match", async () => {
  a.check.mockResolvedValue({ result: { ok: false, rowCount: 10, columnsMatch: false, missingColumns: ["amount"], rowErrorCount: 0, rowErrors: [] } });
  render(<ConnectForm agentId="a1" versionId="v1" agentName="A" manifest={manifest} datasets={[ds]} canConnect />);
  await userEvent.selectOptions(screen.getByLabelText(/bank statement/i), "d1");
  expect(await screen.findByText(/missing: amount/i)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /add and run now/i })).toBeDisabled();
});

it("creates the connection once, binds, runs, and reuses the connection on retry", async () => {
  a.check.mockResolvedValue({ result: { ok: true, rowCount: 1, columnsMatch: true, missingColumns: [], rowErrorCount: 0, rowErrors: [] } });
  a.create.mockResolvedValue({ connectionId: "c1" });
  a.bind.mockResolvedValue({});
  a.run.mockResolvedValueOnce({ error: "Insufficient funds" }).mockResolvedValueOnce({ displayId: "R-1" });
  render(<ConnectForm agentId="a1" versionId="v1" agentName="A" manifest={manifest} datasets={[ds]} canConnect />);
  await userEvent.selectOptions(screen.getByLabelText(/bank statement/i), "d1");
  await screen.findByText(/columns match/i);
  await userEvent.click(screen.getByRole("button", { name: /add and run now/i }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Insufficient funds");
  await userEvent.click(screen.getByRole("button", { name: /add and run now/i }));
  expect(a.create).toHaveBeenCalledTimes(1);
  expect(a.bind).toHaveBeenCalledWith("c1", "bank_statement", "d1");
  expect(a.run).toHaveBeenCalledTimes(2);
});

it("works for an agent whose manifest declares no resources", async () => {
  a.create.mockResolvedValue({ connectionId: "c1" });
  a.run.mockResolvedValue({ displayId: "R-1" });
  render(<ConnectForm agentId="a1" versionId="v1" agentName="A" manifest={{ ...manifest, resources: null, scopes: [{ type: "WRITE", resource: "ledger", description: "x", requiresApproval: true }] }} datasets={[]} canConnect />);
  expect(screen.queryByRole("combobox")).toBeNull();
  await userEvent.click(screen.getByRole("button", { name: /add and run now/i }));
  expect(a.bind).not.toHaveBeenCalled();
  expect(a.run).toHaveBeenCalledWith("c1");
});

it("tells someone who is not a buyer admin why they cannot connect", () => {
  render(<ConnectForm agentId="a1" versionId="v1" agentName="A" manifest={manifest} datasets={[ds]} canConnect={false} />);
  expect(screen.getByText(/only a workspace admin can add an agent/i)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /add and run now/i })).toBeNull();
});
