import { describe, expect, it } from "vitest";
import { emptyManifestDraft, toParsedManifest } from "./manifestDraft";

describe("toParsedManifest", () => {
  it("flattens network.allowlist into networkAllowlist for the preview renderer", () => {
    const draft = emptyManifestDraft("Test Agent");
    draft.network.allowlist = ["gst.gov.in"];
    const manifest = toParsedManifest(draft);
    expect(manifest.networkAllowlist).toEqual(["gst.gov.in"]);
    expect(manifest.name).toBe("Test Agent");
  });
});
