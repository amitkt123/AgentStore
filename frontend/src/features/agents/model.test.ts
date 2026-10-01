import { ApiError } from "@/lib/api/http";
import { createAgentFailure, createVersionFailure, groupBlockers, parseManifestInput, patchConfirmation, patchFailure, statusLabel, statusTone, submitFailure, submitMessage } from "./model";

const hidden = (status: number) => new ApiError(status, null, `Request failed (${status})`);

it("labels and tones every status", () => {
  expect(statusLabel).toEqual({ DRAFT: "Draft", IN_REVIEW: "In review", LIVE: "Live", REJECTED: "Rejected" });
  expect(statusTone).toEqual({ DRAFT: "neutral", IN_REVIEW: "warn", LIVE: "ok", REJECTED: "bad" });
});

it("never shows the hidden-reason fallback for a create-version 400", () => {
  const f = createVersionFailure(hidden(400));
  expect(f.field).toBe("manifest");
  expect(f.text).not.toMatch(/Request failed/);
  expect(f.text).toMatch(/couldn't accept this manifest/);
});

it("maps create-version ownership and missing-agent errors", () => {
  expect(createVersionFailure(hidden(403)).text).toBe("That agent belongs to another organisation.");
  expect(createVersionFailure(hidden(404)).text).toBe("That agent no longer exists.");
  expect(createVersionFailure(hidden(500)).text).not.toMatch(/Request failed/);
});

it("maps create-agent errors", () => {
  expect(createAgentFailure(hidden(400)).text).toBe("Give the agent a name and a category.");
  expect(createAgentFailure(hidden(500)).text).toBe("Couldn't create the agent. Try again.");
});

it("shows a patch error's own message when the backend sent a code", () => {
  expect(patchFailure(new ApiError(409, "SCOPE_ALREADY_DECLARED", "A READ scope for 'x' is already declared")).text).toBe("A READ scope for 'x' is already declared");
  expect(patchFailure(new ApiError(409, "VERSION_NOT_EDITABLE", "Only a DRAFT version's manifest can be patched")).text).toBe("This version can no longer be edited.");
  expect(patchFailure(hidden(403)).text).toBe("You can't edit this version.");
  expect(patchFailure(hidden(500)).text).not.toMatch(/Request failed/);
});

it("sends an unverified submit to Get verified, for both codes", () => {
  const byok = submitFailure(new ApiError(403, "BYOK_NOT_VERIFIED", "Only verified developer organisations can use llm.mode byok_proxy"));
  expect(byok.verify).toBe(true);
  expect(byok.text).toMatch(/own LLM key/);
  const generic = submitFailure(new ApiError(403, "VERIFICATION_REQUIRED", "m"));
  expect(generic.verify).toBe(true);
  expect(generic.text).toBe("Get verified before you submit an agent for listing.");
});

it("never shows the hidden-reason fallback for a submit 409", () => {
  const f = submitFailure(hidden(409));
  expect(f.verify).toBeUndefined();
  expect(f.text).toMatch(/can't be submitted yet/);
  expect(f.text).not.toMatch(/Request failed/);
});

it("words the submit result from the status the backend returned", () => {
  expect(submitMessage("LIVE")).toBe("Submitted and published. This version is live.");
  expect(submitMessage("IN_REVIEW")).toBe("Submitted. Karya will review this version.");
  expect(submitMessage("DRAFT")).toBe("Submitted.");
});

it("builds the patch confirmation from the request", () => {
  expect(patchConfirmation({ op: "add_allowlist_host", host: "api.example.com" })).toBe("Added host api.example.com");
  expect(patchConfirmation({ op: "add_read_scope", resource: "vendor_master" })).toBe("Added read scope vendor_master");
  expect(patchConfirmation({ op: "add_resource", name: "vendors", format: "csv" })).toBe("Added resource vendors");
  expect(patchConfirmation({ op: "remove_scope", type: "WRITE", resource: "journal_entries" })).toBe("Removed write scope journal_entries");
});

it("collapses identical blockers into one row with a count, keeping order", () => {
  const u = { code: "UNDECLARED_ACCESS" as const, message: "outside manifest", runId: "run-1" };
  const rows = groupBlockers([{ code: "BUILD_NOT_READY", message: "no build", runId: null }, u, u, u, { ...u, runId: "run-2" }]);
  expect(rows.map((r) => [r.code, r.runId, r.count])).toEqual([["BUILD_NOT_READY", null, 1], ["UNDECLARED_ACCESS", "run-1", 3], ["UNDECLARED_ACCESS", "run-2", 1]]);
});

it("accepts a JSON object and sends it trimmed", () => {
  expect(parseManifestInput('  {"name":"x"}\n')).toEqual({ ok: true, text: '{"name":"x"}' });
});

it.each([
  ["", "Paste the manifest JSON."],
  ["   ", "Paste the manifest JSON."],
  ["{not json", "That isn't valid JSON."],
  ["[1,2]", "The manifest must be a JSON object."],
  ["null", "The manifest must be a JSON object."],
  ["42", "The manifest must be a JSON object."],
])("rejects manifest input %j", (text, error) => {
  expect(parseManifestInput(text)).toEqual({ ok: false, error });
});
