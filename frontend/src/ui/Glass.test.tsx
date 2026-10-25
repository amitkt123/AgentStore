import { render, screen } from "@testing-library/react";
import { Glass } from "./Glass";

it("renders the chosen element and level", () => {
  render(<Glass as="section" level={2} aria-label="P">x</Glass>);
  const el = screen.getByRole("region", { name: "P" });
  expect(el.className).toContain("surface");
  expect(el.className).toContain("l2");
});

it("defaults to a level 1 div", () => {
  render(<Glass>x</Glass>);
  const el = screen.getByText("x");
  expect(el.tagName).toBe("DIV");
  expect(el.className).toContain("l1");
});
