import { describe, expect, it } from "vitest";
import { decodeSessionToken, roleHome } from "./session";

function fakeJwt(payload: Record<string, unknown>): string {
  const base64url = (obj: unknown) =>
    Buffer.from(JSON.stringify(obj)).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `${base64url({ alg: "HS256" })}.${base64url(payload)}.fake-signature`;
}

describe("decodeSessionToken", () => {
  it("reads userId from the standard sub claim, plus orgId and role", () => {
    const token = fakeJwt({ sub: "user-1", orgId: "org-1", role: "BUYER_ADMIN" });
    expect(decodeSessionToken(token)).toEqual({ userId: "user-1", orgId: "org-1", role: "BUYER_ADMIN" });
  });

  it("returns null for a malformed token", () => {
    expect(decodeSessionToken("not-a-jwt")).toBeNull();
  });

  it("returns null for an expired token", () => {
    const token = fakeJwt({ sub: "user-1", orgId: "org-1", role: "BUYER_ADMIN", exp: Math.floor(Date.now() / 1000) - 60 });
    expect(decodeSessionToken(token)).toBeNull();
  });

  it("accepts a token with a future exp claim", () => {
    const token = fakeJwt({ sub: "user-1", orgId: "org-1", role: "BUYER_ADMIN", exp: Math.floor(Date.now() / 1000) + 3600 });
    expect(decodeSessionToken(token)).toEqual({ userId: "user-1", orgId: "org-1", role: "BUYER_ADMIN" });
  });
});

describe("roleHome", () => {
  it("routes each role to its console root", () => {
    expect(roleHome("DEVELOPER")).toBe("/dashboard");
    expect(roleHome("ADMIN")).toBe("/review");
    expect(roleHome("BUYER_ADMIN")).toBe("/catalog");
    expect(roleHome("BUYER_APPROVER")).toBe("/catalog");
  });
});
