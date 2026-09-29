import { render, screen } from "@testing-library/react";
import { LoginForm } from "./LoginForm";
import { vi } from "vitest";

// Stable react-dom has no form hooks (Next bundles its own React), so stub them.
vi.mock("react-dom", async (orig) => ({
  ...(await orig<object>()),
  useFormState: (action: unknown, state: unknown) => [state, action],
  useFormStatus: () => ({ pending: false }),
}));
vi.mock("./actions", () => ({ loginAction: vi.fn() }));

it("renders labelled fields and a remember checkbox", () => {
  render(<LoginForm next="/runs" />);
  expect(screen.getByLabelText("Email")).toBeInTheDocument();
  expect(screen.getByLabelText("Password")).toBeInTheDocument();
  expect(screen.getByLabelText(/keep me signed in/i)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /sign in/i })).toBeInTheDocument();
  expect(screen.getByRole("link", { name: /create an account/i })).toHaveAttribute("href", "/signup");
});
