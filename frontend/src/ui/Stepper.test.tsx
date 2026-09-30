import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import { Stepper } from "./Stepper";

const items = [
  { key: "a", name: "About you", hint: "Asha, individual", state: "done" as const },
  { key: "b", name: "Tax IDs", hint: "PAN, GSTIN if any", state: "cur" as const },
  { key: "c", name: "Phone", hint: "Mobile number", state: "todo" as const, disabled: true },
];

it("marks done, current and todo steps and disables unreachable ones", () => {
  render(<Stepper items={items} onPick={() => {}} />);
  expect(screen.getByRole("button", { name: /About you/ })).toHaveAttribute("data-state", "done");
  expect(screen.getByRole("button", { name: /Tax IDs/ })).toHaveAttribute("aria-current", "step");
  expect(screen.getByRole("button", { name: /Phone/ })).toBeDisabled();
});

it("reports the picked step's key", async () => {
  const onPick = vi.fn();
  render(<Stepper items={items} onPick={onPick} />);
  await userEvent.click(screen.getByRole("button", { name: /About you/ }));
  expect(onPick).toHaveBeenCalledWith("a");
});

it("renders the dashed note card after the steps", () => {
  render(<Stepper items={items} onPick={() => {}} note={{ title: "Karya checks it", hint: "After you send it" }} />);
  expect(screen.getByText("Karya checks it")).toBeInTheDocument();
  expect(screen.getByText("After you send it")).toBeInTheDocument();
});
