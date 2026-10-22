import { render, screen } from "@testing-library/react";
import { Topbar } from "./Topbar";

vi.mock("@/app/(auth)/actions", () => ({ logoutAction: vi.fn() }));

it("has no placeholder search and names the workspace", () => {
  render(<Topbar orgName="Demo buyer Org" fullName="Demo Buyer" email="b@example.com" />);
  expect(screen.queryByRole("search")).toBeNull();
  expect(screen.getAllByText("Demo buyer Org").length).toBeGreaterThan(0);
});
