import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import { Segmented } from "./Segmented";

const options = [{ value: "one", label: "An individual" }, { value: "two", label: "A company" }];

it("shows which option is pressed and reports changes", async () => {
  const onChange = vi.fn();
  render(<Segmented label="Entity type" value="one" options={options} onChange={onChange} />);
  expect(screen.getByRole("group", { name: "Entity type" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "An individual" })).toHaveAttribute("aria-pressed", "true");
  expect(screen.getByRole("button", { name: "A company" })).toHaveAttribute("aria-pressed", "false");
  await userEvent.click(screen.getByRole("button", { name: "A company" }));
  expect(onChange).toHaveBeenCalledWith("two");
});

it("does not report changes when disabled", async () => {
  const onChange = vi.fn();
  render(<Segmented label="Entity type" value="one" options={options} onChange={onChange} disabled />);
  await userEvent.click(screen.getByRole("button", { name: "A company" }));
  expect(onChange).not.toHaveBeenCalled();
});
