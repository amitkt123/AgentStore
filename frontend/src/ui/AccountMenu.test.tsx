import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AccountMenu } from "./AccountMenu";

vi.mock("@/app/(auth)/actions", () => ({ logoutAction: vi.fn() }));

it("shows who is signed in and offers sign out", async () => {
  render(<AccountMenu fullName="Demo Buyer" email="b@example.com" orgName="Demo buyer Org" />);
  await userEvent.click(screen.getByText("DB"));
  expect(screen.getByText("b@example.com")).toBeVisible();
  expect(screen.getByText("Demo buyer Org")).toBeVisible();
  expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
});
