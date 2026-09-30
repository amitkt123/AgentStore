import { gateTarget } from "./gate";

it.each(["/home", "/store", "/store/a1/v1", "/runs", "/runs/R-1", "/files", "/connect/a/v"])("sends a developer away from buyer route %s", (path) => {
  expect(gateTarget("DEVELOPER", path)).toBe("/developer/verification");
});

it.each(["BUYER_ADMIN", "BUYER_APPROVER", "ADMIN"] as const)("sends %s away from developer routes", (role) => {
  expect(gateTarget(role, "/developer/verification")).toBe("/home");
  expect(gateTarget(role, "/developer/api-keys")).toBe("/home");
});

it("lets each role through on its own routes", () => {
  expect(gateTarget("DEVELOPER", "/developer/api-keys")).toBeNull();
  expect(gateTarget("BUYER_ADMIN", "/runs")).toBeNull();
});

it("only matches whole path segments", () => {
  expect(gateTarget("DEVELOPER", "/storefront")).toBeNull();
  expect(gateTarget("BUYER_ADMIN", "/developers-guide")).toBeNull();
});

it("does nothing without a role", () => {
  expect(gateTarget(undefined, "/home")).toBeNull();
});
