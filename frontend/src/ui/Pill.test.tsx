import { render, screen } from "@testing-library/react";
import { Pill } from "./Pill";

it("renders its text and a tone class", () => {
  render(<Pill tone="ok">Finished</Pill>);
  const el = screen.getByText("Finished");
  expect(el).toBeInTheDocument();
  expect(el.className).toMatch(/ok/);
});
