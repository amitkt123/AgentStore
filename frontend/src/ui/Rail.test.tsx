import { render, screen } from "@testing-library/react";
import { Rail, buyerRailItems, developerRailItems, railItemsFor } from "./Rail";

const items = [
  { href: "/home", label: "Workspace", icon: "home" as const },
  { href: "/runs", label: "Runs", icon: "runs" as const, badge: 2 },
];

it("marks the active item and shows the badge as text", () => {
  render(<Rail items={items} activePath="/runs/R-2291" />);
  expect(screen.getByRole("link", { name: /Runs, 2 need you/i })).toHaveAttribute("aria-current", "page");
  expect(screen.getByRole("link", { name: /Workspace/ })).not.toHaveAttribute("aria-current");
});

it("gives developers verification and API keys and leaves the buyer rail alone", () => {
  expect(developerRailItems().map((i) => i.href)).toEqual(["/developer/agents", "/developer/earnings", "/developer/verification", "/developer/api-keys"]);
  expect(railItemsFor("DEVELOPER", 3)).toEqual(developerRailItems());
  expect(railItemsFor("BUYER_ADMIN", 3)).toEqual(buyerRailItems(3));
});

it("hides Billing from buyer approvers", () => {
  expect(railItemsFor("BUYER_APPROVER", 1).map((i) => i.href)).toEqual(["/home", "/store", "/runs", "/files"]);
  expect(railItemsFor("BUYER_APPROVER", 1)[2].badge).toBe(1);
});

it("keeps Agents active inside a Studio route", () => {
  render(<Rail items={developerRailItems()} activePath="/developer/agents/a1/versions/v1" />);
  expect(screen.getByRole("link", { name: "Agents" })).toHaveAttribute("aria-current", "page");
});

it("gives buyers a Billing item after Files", () => {
  expect(buyerRailItems(0).map((i) => i.href)).toEqual(["/home", "/store", "/runs", "/files", "/billing"]);
});

it("gives ADMIN a Money rail", async () => {
  const { adminRailItems } = await import("./Rail");
  expect(adminRailItems()).toEqual([{ href: "/admin/money", label: "Money", icon: "wallet" }]);
  expect(railItemsFor("ADMIN", 0)).toEqual(adminRailItems());
});
