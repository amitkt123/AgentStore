import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

const upload = vi.hoisted(() => vi.fn());
const del = vi.hoisted(() => vi.fn());
vi.mock("@/app/(app)/files/actions", () => ({ uploadFileAction: (...a: unknown[]) => upload(...a), deleteFileAction: (...a: unknown[]) => del(...a) }));
import { FilesView } from "./FilesView";
import type { Dataset } from "@/lib/api/types";

const ds = (over: Partial<Dataset> = {}): Dataset => ({
  id: "d1", displayName: "Aug 2026 statement.csv", sourceLabel: "HDFC current account", format: "CSV", rowCount: 412, sizeBytes: 5000,
  columns: ["date", "narration", "amount"], sha256: "x", uploadedBy: "Priya", createdAt: "2026-09-01T00:00:00Z", piiScan: null,
  usage: [{ connectionId: "c1", resource: "bank_statement", agent: "Bank Reconciliation", version: "1.3.0", access: "READ" }], ...over,
});
const storage = { usedBytes: 5000, limitBytes: 5 * 1024 ** 3 };

beforeEach(() => { upload.mockReset(); del.mockReset(); });

it("lists files with rows and what each is plugged into", () => {
  render(<FilesView datasets={[ds(), ds({ id: "d2", displayName: "Vendor master.xlsx", usage: [], rowCount: null })]} storage={storage} />);
  expect(screen.getByText("Aug 2026 statement.csv")).toBeInTheDocument();
  expect(screen.getByText("412")).toBeInTheDocument();
  expect(screen.getByText("Bank Reconciliation")).toBeInTheDocument();
  expect(screen.getByText("Not plugged in")).toBeInTheDocument();
});

it("shows the empty state when there are no files", () => {
  render(<FilesView datasets={[]} storage={storage} />);
  expect(screen.getByText(/no files yet/i)).toBeInTheDocument();
});

it("shows the backend's message when an upload fails and leaves the list alone", async () => {
  upload.mockResolvedValue({ error: "This file would go over your storage limit" });
  render(<FilesView datasets={[ds()]} storage={{ usedBytes: 5 * 1024 ** 3, limitBytes: 5 * 1024 ** 3 }} />);
  const input = screen.getByLabelText(/upload file/i) as HTMLInputElement;
  await userEvent.upload(input, new File(["a,b\n1,2"], "big.csv", { type: "text/csv" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("This file would go over your storage limit");
  expect(screen.getAllByRole("row").length).toBe(2);
});

it("opens the drawer and unplugs then deletes", async () => {
  del.mockResolvedValue({});
  render(<FilesView datasets={[ds()]} storage={storage} />);
  await userEvent.click(screen.getByRole("button", { name: /where .*Aug 2026 statement/i }));
  const dialog = screen.getByRole("dialog");
  expect(within(dialog).getByText(/Bank Reconciliation/)).toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole("button", { name: /unplug and delete/i }));
  expect(del).toHaveBeenCalledWith("d1", [{ connectionId: "c1", resource: "bank_statement" }]);
});
