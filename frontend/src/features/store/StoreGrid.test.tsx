import { render, screen } from "@testing-library/react";
import { StoreGrid } from "./StoreGrid";

const listing = { agentId: "a", agentName: "Bank Reconciliation", category: "reconciliation", versionId: "v", versionNumber: "0.2.0" };

it("marks an agent the workspace already added and anchors each card by agent", () => {
  const { container } = render(<StoreGrid listings={[listing]} addedAgentIds={new Set(["a"])} />);
  expect(screen.getByText("Added")).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Bank Reconciliation" })).toHaveAttribute("href", "/store/a/v");
  expect(container.querySelector("#agent-a")).not.toBeNull();
});

it("shows no Added label for an agent not added", () => {
  render(<StoreGrid listings={[listing]} addedAgentIds={new Set()} />);
  expect(screen.queryByText("Added")).toBeNull();
});
