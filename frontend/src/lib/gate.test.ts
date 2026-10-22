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

it("lets a developer, and only a developer, into the agents screens", () => {
  expect(gateTarget("DEVELOPER", "/developer/agents")).toBeNull();
  expect(gateTarget("DEVELOPER", "/developer/agents/a1/versions/v1")).toBeNull();
  expect(gateTarget("BUYER_ADMIN", "/developer/agents/a1/versions/v1")).toBe("/home");
  expect(gateTarget("BUYER_APPROVER", "/developer/agents")).toBe("/home");
});

it.each(["BUYER_ADMIN", "BUYER_APPROVER"] as const)("keeps %s out of /admin", (role) => {
  expect(gateTarget(role, "/admin/money")).toBe("/home");
});

it("sends a developer away from /admin to their own area", () => {
  expect(gateTarget("DEVELOPER", "/admin/money")).toBe("/developer/verification");
});

it("lets ADMIN into /admin, and only matches whole segments", () => {
  expect(gateTarget("ADMIN", "/admin/money")).toBeNull();
  expect(gateTarget("BUYER_ADMIN", "/administrators")).toBeNull();
});

it("sends a signed-in user past login and signup to their own home", () => {
  expect(gateTarget("BUYER_ADMIN", "/login")).toBe("/home");
  expect(gateTarget("BUYER_APPROVER", "/signup")).toBe("/home");
  expect(gateTarget("DEVELOPER", "/signup")).toBe("/developer/agents");
  expect(gateTarget("ADMIN", "/login")).toBe("/admin/money");
  expect(gateTarget(undefined, "/login")).toBeNull();
});
