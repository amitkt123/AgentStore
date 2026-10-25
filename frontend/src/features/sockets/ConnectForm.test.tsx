import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

const a = vi.hoisted(() => ({ upload: vi.fn(), check: vi.fn(), create: vi.fn(), bind: vi.fn(), run: vi.fn() }));
const push = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));
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

beforeEach(() => { Object.values(a).forEach((m) => m.mockReset()); push.mockReset(); });

it("blocks 'Add to workspace' until every socket has a checked file", async () => {
  a.check.mockResolvedValue({ result: { ok: true, rowCount: 412, columnsMatch: true, missingColumns: [], rowErrorCount: 0, rowErrors: [] } });
  render(<ConnectForm agentId="a1" versionId="v1" agentName="Bank Reconciliation" manifest={manifest} datasets={[ds]} canConnect />);
  expect(screen.getByRole("button", { name: /add to workspace/i })).toBeDisabled();
  await userEvent.selectOptions(screen.getByLabelText(/bank statement/i), "d1");
  expect(await screen.findByText(/412 rows, columns match/i)).toBeInTheDocument();
  expect(a.check).toHaveBeenCalledWith("d1", "v1", "bank_statement");
  expect(screen.getByRole("button", { name: /add to workspace/i })).toBeEnabled();
});

it("shows what is missing when the columns do not match", async () => {
  a.check.mockResolvedValue({ result: { ok: false, rowCount: 10, columnsMatch: false, missingColumns: ["amount"], rowErrorCount: 0, rowErrors: [] } });
  render(<ConnectForm agentId="a1" versionId="v1" agentName="A" manifest={manifest} datasets={[ds]} canConnect />);
  await userEvent.selectOptions(screen.getByLabelText(/bank statement/i), "d1");
  expect(await screen.findByText(/missing: amount/i)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /add to workspace/i })).toBeDisabled();
});

it("adds the agent without starting a run, and reuses the connection on retry", async () => {
  a.check.mockResolvedValue({ result: { ok: true, rowCount: 1, columnsMatch: true, missingColumns: [], rowErrorCount: 0, rowErrors: [] } });
  a.create.mockResolvedValue({ connectionId: "c1" });
  a.bind.mockResolvedValueOnce({ error: "Storage is busy" }).mockResolvedValueOnce({});
  render(<ConnectForm agentId="a1" versionId="v1" agentName="A" manifest={manifest} datasets={[ds]} canConnect />);
  await userEvent.selectOptions(screen.getByLabelText(/bank statement/i), "d1");
  await screen.findByText(/columns match/i);
  await userEvent.click(screen.getByRole("button", { name: /add to workspace/i }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Storage is busy");
  await userEvent.click(screen.getByRole("button", { name: /add to workspace/i }));
  expect(a.create).toHaveBeenCalledTimes(1);
  expect(a.bind).toHaveBeenCalledWith("c1", "bank_statement", "d1");
  expect(a.run).not.toHaveBeenCalled();
  expect(push).toHaveBeenCalledWith("/home?added=a1");
});

it("says what the agent can write and that it asks first", () => {
  const m: Manifest = { ...manifest, scopes: [...manifest.scopes, { type: "WRITE", resource: "ledger", description: "Journal entries", requiresApproval: true }] };
  render(<ConnectForm agentId="a1" versionId="v1" agentName="A" manifest={m} datasets={[ds]} canConnect />);
  expect(screen.getByText("Can write: Ledger, only after your approval")).toBeInTheDocument();
});

it("works for an agent whose manifest declares no resources", async () => {
  a.create.mockResolvedValue({ connectionId: "c1" });
  a.run.mockResolvedValue({ displayId: "R-1" });
  render(<ConnectForm agentId="a1" versionId="v1" agentName="A" manifest={{ ...manifest, resources: null, scopes: [{ type: "WRITE", resource: "ledger", description: "x", requiresApproval: true }] }} datasets={[]} canConnect />);
  expect(screen.queryByRole("combobox")).toBeNull();
  await userEvent.click(screen.getByRole("button", { name: /add to workspace/i }));
  expect(a.bind).not.toHaveBeenCalled();
  expect(a.run).not.toHaveBeenCalled();
  expect(push).toHaveBeenCalledWith("/home?added=a1");
});

it("tells someone who is not a buyer admin why they cannot connect", () => {
  render(<ConnectForm agentId="a1" versionId="v1" agentName="A" manifest={manifest} datasets={[ds]} canConnect={false} />);
  expect(screen.getByText(/only a workspace admin can add an agent/i)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /add to workspace/i })).toBeNull();
});
