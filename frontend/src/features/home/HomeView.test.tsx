import { render, screen } from "@testing-library/react";
import { HomeView } from "./HomeView";

const waiting = [{ id: "r1", displayId: "R-2291", agent: "Bank Reconciliation", version: "1.3.0", status: "AWAITING_APPROVAL" as const, terminalReason: null, reachedBlock: "gate:post", startedAt: "2026-09-29T04:35:00Z", completedAt: null, charged: 0, held: 500, decidedBy: null }];

it("greets by first name and lists runs that need approval", () => {
  render(<HomeView fullName="Priya Shah" role="BUYER_APPROVER" needsYou={waiting} connections={[]} />);
  expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Priya");
  expect(screen.getByRole("link", { name: /R-2291/ })).toHaveAttribute("href", "/runs/R-2291");
});

it("shows an empty state that points to the store when nothing is connected", () => {
  render(<HomeView fullName="Priya Shah" role="BUYER_ADMIN" needsYou={[]} connections={[]} />);
  expect(screen.getByText(/nothing is waiting for you/i)).toBeInTheDocument();
  expect(screen.getByRole("link", { name: /browse the agent store/i })).toHaveAttribute("href", "/store");
});

it("points an admin to Money instead of the old holding message", () => {
  render(<HomeView fullName="Ada Admin" role="ADMIN" needsYou={[]} connections={[]} />);
  expect(screen.getByRole("link", { name: /go to money/i })).toHaveAttribute("href", "/admin/money");
  expect(screen.queryByText(/being rebuilt/i)).toBeNull();
});
