import { render, screen } from "@testing-library/react";
import { Rail } from "./Rail";

const items = [
  { href: "/home", label: "Workspace", icon: "home" as const },
  { href: "/runs", label: "Runs", icon: "runs" as const, badge: 2 },
];

it("marks the active item and shows the badge as text", () => {
  render(<Rail items={items} activePath="/runs/R-2291" />);
  expect(screen.getByRole("link", { name: /Runs, 2 need you/i })).toHaveAttribute("aria-current", "page");
  expect(screen.getByRole("link", { name: /Workspace/ })).not.toHaveAttribute("aria-current");
});
