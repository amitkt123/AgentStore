import { canCreateConnections, canDecideGates, isBuyer, railFor } from "./roles";

it("maps roles to rails", () => {
  expect(railFor("BUYER_ADMIN")).toBe("buyer");
  expect(railFor("BUYER_APPROVER")).toBe("buyer");
  expect(railFor("DEVELOPER")).toBe("developer");
  expect(railFor("ADMIN")).toBe("admin");
});

it("only approvers decide gates and only buyer admins connect", () => {
  expect(canDecideGates("BUYER_APPROVER")).toBe(true);
  expect(canDecideGates("BUYER_ADMIN")).toBe(false);
  expect(canCreateConnections("BUYER_ADMIN")).toBe(true);
  expect(canCreateConnections("BUYER_APPROVER")).toBe(false);
  expect(isBuyer("DEVELOPER")).toBe(false);
});
