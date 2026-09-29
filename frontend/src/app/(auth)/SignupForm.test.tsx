import { fireEvent, render, screen } from "@testing-library/react";
import { SignupForm } from "./SignupForm";
import { vi } from "vitest";

vi.mock("react-dom", async (orig) => ({
  ...(await orig<object>()),
  useFormState: (action: unknown, state: unknown) => [state, action],
  useFormStatus: () => ({ pending: false }),
}));
vi.mock("./actions", () => ({ signupAction: vi.fn() }));

it("defaults to the buyer role and switches copy for developers", () => {
  render(<SignupForm />);
  expect(screen.getByLabelText(/I want to run an Agent/)).toBeChecked();
  expect(screen.getByLabelText("Company")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Create workspace" })).toBeInTheDocument();
  fireEvent.click(screen.getByLabelText(/I build agents/));
  expect(screen.getByLabelText("Your name or studio")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Create developer account" })).toBeInTheDocument();
});
