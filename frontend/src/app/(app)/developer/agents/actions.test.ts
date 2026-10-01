import { vi } from "vitest";

const api = vi.hoisted(() => ({ createAgent: vi.fn(), createVersion: vi.fn(), patchManifest: vi.fn(), submitVersion: vi.fn() }));
const revalidate = vi.hoisted(() => vi.fn());
vi.mock("@/lib/api/agents", () => api);
vi.mock("next/cache", () => ({ revalidatePath: (...a: unknown[]) => revalidate(...a) }));
import { ApiError } from "@/lib/api/http";
import { createAgentAction, createVersionAction, patchAction, submitAction } from "./actions";

beforeEach(() => { Object.values(api).forEach((m) => m.mockReset()); revalidate.mockReset(); });

it("creates an agent with trimmed fields, leaves a blank description out, and refreshes the list", async () => {
  api.createAgent.mockResolvedValue({ id: "a1" });
  expect(await createAgentAction({ name: " Bot ", category: " Finance ", description: "  " })).toEqual({ ok: true });
  expect(api.createAgent).toHaveBeenCalledWith({ name: "Bot", category: "Finance" });
  expect(revalidate).toHaveBeenCalledWith("/developer/agents");
});

it("sends a description when there is one", async () => {
  api.createAgent.mockResolvedValue({ id: "a1" });
  await createAgentAction({ name: "Bot", category: "Finance", description: "Matches invoices" });
  expect(api.createAgent).toHaveBeenCalledWith({ name: "Bot", category: "Finance", description: "Matches invoices" });
});

it("creates a version and leaves a blank changelog out", async () => {
  api.createVersion.mockResolvedValue({ id: "v1" });
  expect(await createVersionAction("a1", { versionNumber: " 1.0.0 ", manifestJson: "{}", changelog: "" })).toEqual({ ok: true });
  expect(api.createVersion).toHaveBeenCalledWith("a1", { versionNumber: "1.0.0", manifestJson: "{}" });
  expect(revalidate).toHaveBeenCalledWith("/developer/agents");
});

it("turns a hidden 400 on create version into a sentence under the manifest field, with no refresh", async () => {
  api.createVersion.mockRejectedValue(new ApiError(400, null, "Request failed (400)"));
  const res = await createVersionAction("a1", { versionNumber: "1.0.0", manifestJson: "{}", changelog: "" });
  expect(res).toMatchObject({ ok: false, failure: { field: "manifest" } });
  expect(JSON.stringify(res)).not.toMatch(/Request failed/);
  expect(revalidate).not.toHaveBeenCalled();
});

it("patches, confirms in plain words, and refreshes that Studio", async () => {
  api.patchManifest.mockResolvedValue({ manifestRevision: 2, diff: { addedScopes: [], removedScopes: [], addedHosts: [] } });
  const req = { op: "add_allowlist_host" as const, host: "api.example.com" };
  expect(await patchAction("a1", "v1", req)).toEqual({ ok: true, message: "Added host api.example.com" });
  expect(api.patchManifest).toHaveBeenCalledWith("a1", "v1", req);
  expect(revalidate).toHaveBeenCalledWith("/developer/agents/a1/versions/v1");
});

it("shows a patch conflict's own message", async () => {
  api.patchManifest.mockRejectedValue(new ApiError(409, "SCOPE_ALREADY_DECLARED", "A READ scope for 'x' is already declared"));
  expect(await patchAction("a1", "v1", { op: "add_read_scope", resource: "x" })).toEqual({ ok: false, failure: { text: "A READ scope for 'x' is already declared" } });
});

it("submits and reports the status that came back, refreshing the Studio and the list", async () => {
  api.submitVersion.mockResolvedValue({ id: "v1", versionNumber: "1.0.0", status: "LIVE" });
  expect(await submitAction("a1", "v1")).toEqual({ ok: true, message: "Submitted and published. This version is live.", status: "LIVE" });
  expect(revalidate).toHaveBeenCalledWith("/developer/agents/a1/versions/v1");
  expect(revalidate).toHaveBeenCalledWith("/developer/agents");
});

it("returns the verify flag when the backend says the org is not verified", async () => {
  api.submitVersion.mockRejectedValue(new ApiError(403, "BYOK_NOT_VERIFIED", "m"));
  expect(await submitAction("a1", "v1")).toMatchObject({ ok: false, failure: { verify: true } });
});

it("rethrows anything that is not an ApiError", async () => {
  api.submitVersion.mockRejectedValue(new Error("boom"));
  await expect(submitAction("a1", "v1")).rejects.toThrow("boom");
});
