import { describe, expect, it } from "vitest";
import { resolveRedirect } from "./routing";
import type { SessionClaims } from "./session";

describe("resolveRedirect", () => {
  it("allows unauthenticated access to auth pages", () => {
    expect(resolveRedirect("/login", null)).toBeNull();
  });

  it("sends an unauthenticated visitor to login", () => {
    expect(resolveRedirect("/catalog", null)).toBe("/login");
  });

  it("bounces a developer away from buyer-only routes to their own home", () => {
    const claims: SessionClaims = { userId: "u1", orgId: "o1", role: "DEVELOPER" };
    expect(resolveRedirect("/catalog", claims)).toBe("/dashboard");
  });

  it("lets a buyer admin through to buyer routes", () => {
    const claims: SessionClaims = { userId: "u1", orgId: "o1", role: "BUYER_ADMIN" };
    expect(resolveRedirect("/catalog", claims)).toBeNull();
  });

  it("lets an admin through to the review queue but not a buyer route", () => {
    const claims: SessionClaims = { userId: "u1", orgId: "o1", role: "ADMIN" };
    expect(resolveRedirect("/review", claims)).toBeNull();
    expect(resolveRedirect("/catalog", claims)).toBe("/review");
  });
});
