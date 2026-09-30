import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

const create = vi.hoisted(() => vi.fn());
const revoke = vi.hoisted(() => vi.fn());
vi.mock("@/app/(app)/developer/api-keys/actions", () => ({ createKeyAction: (...a: unknown[]) => create(...a), revokeKeyAction: (...a: unknown[]) => revoke(...a) }));
import { ApiKeysView } from "./ApiKeysView";
import type { DeveloperApiKey } from "@/lib/api/types";

const key = (over: Partial<DeveloperApiKey> = {}): DeveloperApiKey => ({ id: "k1", name: "Laptop", prefix: "ask_live_ab12", createdAt: "2026-09-01T12:00:00Z", lastUsedAt: null, revokedAt: null, ...over });
const created = { id: "k2", name: "CI", prefix: "ask_live_cd34", key: "ask_live_cd34SECRETVALUE", createdAt: "2026-09-30T12:00:00Z" };

beforeEach(() => { create.mockReset(); revoke.mockReset(); localStorage.clear(); sessionStorage.clear(); });

it("points at karya login when there are no keys", () => {
  render(<ApiKeysView keys={[]} />);
  expect(screen.getByText(/karya login/)).toBeInTheDocument();
});

it("lists keys with prefix, last used and status, and offers revoke only on active ones", () => {
  render(<ApiKeysView keys={[key(), key({ id: "k3", name: "Old", revokedAt: "2026-09-10T00:00:00Z", lastUsedAt: "2026-09-05T00:00:00Z" })]} />);
  expect(screen.getByText("Laptop")).toBeInTheDocument();
  expect(screen.getByText("Never")).toBeInTheDocument();
  expect(screen.getByText("Active")).toBeInTheDocument();
  expect(screen.getByText("Revoked")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Revoke Laptop" })).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Revoke Old" })).not.toBeInTheDocument();
});

it("shows the new key once, keeps it out of storage, and clears it when dismissed", async () => {
  create.mockResolvedValue({ key: created });
  render(<ApiKeysView keys={[]} />);
  await userEvent.type(screen.getByLabelText("Key name"), "CI");
  await userEvent.click(screen.getByRole("button", { name: "Create key" }));
  expect(create).toHaveBeenCalledWith("CI");
  expect(await screen.findByText("ask_live_cd34SECRETVALUE")).toBeInTheDocument();
  expect(screen.getByText(/You won't see this again/)).toBeInTheDocument();
  expect(localStorage.length).toBe(0);
  expect(sessionStorage.length).toBe(0);
  await userEvent.click(screen.getByRole("button", { name: "Done" }));
  expect(screen.queryByText("ask_live_cd34SECRETVALUE")).not.toBeInTheDocument();
});

it("keeps the key visible when the list refreshes, and will not make a second key over it", async () => {
  create.mockResolvedValue({ key: created });
  const { rerender } = render(<ApiKeysView keys={[]} />);
  await userEvent.type(screen.getByLabelText("Key name"), "CI");
  await userEvent.click(screen.getByRole("button", { name: "Create key" }));
  await screen.findByText("ask_live_cd34SECRETVALUE");
  rerender(<ApiKeysView keys={[key({ id: "k2", name: "CI", prefix: "ask_live_cd34" })]} />);
  expect(screen.getByText("ask_live_cd34SECRETVALUE")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Create key" })).toBeDisabled();
});

it("does not create two keys on a double click", async () => {
  let resolve!: (v: unknown) => void;
  create.mockReturnValue(new Promise((r) => { resolve = r; }));
  render(<ApiKeysView keys={[]} />);
  await userEvent.type(screen.getByLabelText("Key name"), "CI");
  await userEvent.dblClick(screen.getByRole("button", { name: "Create key" }));
  expect(create).toHaveBeenCalledTimes(1);
  resolve({ key: created });
  await screen.findByText("ask_live_cd34SECRETVALUE");
});

it("does not create a key without a name", async () => {
  render(<ApiKeysView keys={[]} />);
  await userEvent.click(screen.getByRole("button", { name: "Create key" }));
  expect(create).not.toHaveBeenCalled();
});

it("shows the backend's message when creating fails", async () => {
  create.mockResolvedValue({ error: "You have too many keys" });
  render(<ApiKeysView keys={[]} />);
  await userEvent.type(screen.getByLabelText("Key name"), "CI");
  await userEvent.click(screen.getByRole("button", { name: "Create key" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("You have too many keys");
});

it("copies the key to the clipboard", async () => {
  create.mockResolvedValue({ key: created });
  const user = userEvent.setup();
  render(<ApiKeysView keys={[]} />);
  await user.type(screen.getByLabelText("Key name"), "CI");
  await user.click(screen.getByRole("button", { name: "Create key" }));
  await user.click(await screen.findByRole("button", { name: "Copy" }));
  expect(await navigator.clipboard.readText()).toBe("ask_live_cd34SECRETVALUE");
  expect(screen.getByRole("button", { name: "Copied" })).toBeInTheDocument();
});

it("asks before revoking, then revokes by id", async () => {
  revoke.mockResolvedValue({});
  render(<ApiKeysView keys={[key()]} />);
  await userEvent.click(screen.getByRole("button", { name: "Revoke Laptop" }));
  const dialog = screen.getByRole("dialog");
  expect(revoke).not.toHaveBeenCalled();
  await userEvent.click(within(dialog).getByRole("button", { name: "Revoke key" }));
  expect(revoke).toHaveBeenCalledWith("k1");
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
});

it("leaves the key alone when the revoke confirm is dismissed", async () => {
  render(<ApiKeysView keys={[key()]} />);
  await userEvent.click(screen.getByRole("button", { name: "Revoke Laptop" }));
  await userEvent.click(screen.getByRole("button", { name: "Close" }));
  expect(revoke).not.toHaveBeenCalled();
});
