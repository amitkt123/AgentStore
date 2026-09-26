import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ApiKeysView } from "./ApiKeysView";
import { createApiKeyAction, revokeApiKeyAction } from "./actions";
import type { ApiKeySummary } from "@/lib/types";

vi.mock("./actions", () => ({
  createApiKeyAction: vi.fn(),
  revokeApiKeyAction: vi.fn(),
}));

const keys: ApiKeySummary[] = [
  { id: "k1", name: "laptop", prefix: "ask_live_abc", createdAt: "2026-09-01T10:00:00Z", lastUsedAt: null, revokedAt: null },
  { id: "k2", name: "old ci", prefix: "ask_live_xyz", createdAt: "2026-08-01T10:00:00Z", lastUsedAt: "2026-08-15T10:00:00Z", revokedAt: "2026-08-20T10:00:00Z" },
];

describe("ApiKeysView", () => {
  it("shows a message when there are no keys yet", () => {
    render(<ApiKeysView keys={[]} />);
    expect(screen.getByText(/haven't created any API keys yet/i)).toBeInTheDocument();
  });

  it("lists each key by name and prefix, never the key itself", () => {
    render(<ApiKeysView keys={keys} />);
    expect(screen.getByText("laptop")).toBeInTheDocument();
    expect(screen.getByText("ask_live_abc…")).toBeInTheDocument();
    expect(screen.getByText(/Last used never/)).toBeInTheDocument();
  });

  it("offers Revoke only for active keys and shows revoked ones as revoked", () => {
    render(<ApiKeysView keys={keys} />);
    expect(screen.getByRole("button", { name: "Revoke laptop" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Revoke old ci" })).not.toBeInTheDocument();
    expect(screen.getByText("revoked")).toBeInTheDocument();
  });

  it("revokes a key by id", async () => {
    vi.mocked(revokeApiKeyAction).mockResolvedValueOnce({ ok: true });
    render(<ApiKeysView keys={keys} />);
    await userEvent.click(screen.getByRole("button", { name: "Revoke laptop" }));
    expect(revokeApiKeyAction).toHaveBeenCalledWith("k1");
  });

  it("shows why a revoke failed", async () => {
    vi.mocked(revokeApiKeyAction).mockResolvedValueOnce({ ok: false, error: "Could not revoke the key" });
    render(<ApiKeysView keys={keys} />);
    await userEvent.click(screen.getByRole("button", { name: "Revoke laptop" }));
    expect(screen.getByText("Could not revoke the key")).toBeInTheDocument();
  });

  it("cannot create a key without a name", () => {
    render(<ApiKeysView keys={[]} />);
    expect(screen.getByRole("button", { name: "Create key" })).toBeDisabled();
  });

  it("shows a new key once, with a warning, and hides it when done", async () => {
    vi.mocked(createApiKeyAction).mockResolvedValueOnce({ ok: true, key: "ask_live_ONLYSHOWNONCE" });
    render(<ApiKeysView keys={[]} />);

    await userEvent.type(screen.getByLabelText("Key name"), "  build server ");
    await userEvent.click(screen.getByRole("button", { name: "Create key" }));

    expect(createApiKeyAction).toHaveBeenCalledWith("build server");
    expect(screen.getByText("ask_live_ONLYSHOWNONCE")).toBeInTheDocument();
    expect(screen.getByText(/won't be able to see it again/i)).toBeInTheDocument();
    expect(screen.getByLabelText("Key name")).toHaveValue("");

    await userEvent.click(screen.getByRole("button", { name: "Done" }));
    expect(screen.queryByText("ask_live_ONLYSHOWNONCE")).not.toBeInTheDocument();
  });

  it("copies the new key to the clipboard", async () => {
    const user = userEvent.setup();
    vi.mocked(createApiKeyAction).mockResolvedValueOnce({ ok: true, key: "ask_live_COPYME" });
    render(<ApiKeysView keys={[]} />);
    await user.type(screen.getByLabelText("Key name"), "ci");
    await user.click(screen.getByRole("button", { name: "Create key" }));

    await user.click(screen.getByRole("button", { name: "Copy" }));

    expect(await navigator.clipboard.readText()).toBe("ask_live_COPYME");
    expect(screen.getByRole("button", { name: "Copied" })).toBeInTheDocument();
  });

  it("shows an error and no key when creating fails", async () => {
    vi.mocked(createApiKeyAction).mockResolvedValueOnce({ ok: false, error: "Could not create the key" });
    render(<ApiKeysView keys={[]} />);
    await userEvent.type(screen.getByLabelText("Key name"), "ci");
    await userEvent.click(screen.getByRole("button", { name: "Create key" }));
    expect(screen.getByText("Could not create the key")).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "New API key" })).not.toBeInTheDocument();
  });
});
