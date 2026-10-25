import { canCreateConnections, canDecideGates, isBuyer, railFor } from "./roles";

it("maps roles to rails", () => {
  expect(railFor("BUYER_ADMIN")).toBe("buyer");
  expect(railFor("BUYER_MEMBER")).toBe("buyer");
  expect(railFor("DEVELOPER")).toBe("developer");
  expect(railFor("ADMIN")).toBe("admin");
});

it("both buyer roles decide gates and only buyer admins connect", () => {
  expect(canDecideGates("BUYER_MEMBER")).toBe(true);
  expect(canDecideGates("BUYER_ADMIN")).toBe(true);
  expect(canDecideGates("DEVELOPER")).toBe(false);
  expect(canCreateConnections("BUYER_ADMIN")).toBe(true);
  expect(canCreateConnections("BUYER_MEMBER")).toBe(false);
  expect(isBuyer("DEVELOPER")).toBe(false);
});
