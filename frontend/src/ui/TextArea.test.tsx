import { render, screen } from "@testing-library/react";
import { TextArea } from "./TextArea";

it("labels the box and ties its error to it", () => {
  render(<TextArea label="Manifest JSON" error="That isn't valid JSON." />);
  const box = screen.getByLabelText("Manifest JSON");
  expect(box).toHaveAttribute("aria-invalid", "true");
  expect(box).toHaveAttribute("aria-describedby");
  expect(screen.getByText("That isn't valid JSON.")).toBeInTheDocument();
});

it("is valid when there is no error", () => {
  render(<TextArea label="Notes" hint="Optional" />);
  expect(screen.getByLabelText("Notes")).toHaveAttribute("aria-invalid", "false");
});
