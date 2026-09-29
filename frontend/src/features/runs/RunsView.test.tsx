import { render, screen } from "@testing-library/react";
import { vi } from "vitest";

vi.mock("./useRunPolling", () => ({ useRunPolling: vi.fn() }));
import { useRunPolling } from "./useRunPolling";
import { RunsView } from "./RunsView";

const counts = { needs_you: 1, running: 0, finished: 2, stopped: 0 };
const item = { id: "i", displayId: "R-2291", agent: "Bank Reconciliation", version: "1.3.0", status: "AWAITING_APPROVAL" as const, terminalReason: null, reachedBlock: "gate:post", startedAt: null, completedAt: null, charged: 0, held: 500, decidedBy: null };
const initial = { items: [item], counts, page: 0, size: 25, total: 1 };

it("shows filter chips with counts and rows with their reached block", () => {
  vi.mocked(useRunPolling).mockReturnValue({ data: initial, error: null, forbidden: false });
  render(<RunsView initial={initial} />);
  expect(screen.getByRole("button", { name: /Needs you 1/ })).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "R-2291" })).toHaveAttribute("href", "/runs/R-2291");
  expect(screen.getByText("Your approval")).toBeInTheDocument();
});

it("shows an empty state when no run matches", () => {
  const empty = { ...initial, items: [], total: 0 };
  vi.mocked(useRunPolling).mockReturnValue({ data: empty, error: null, forbidden: false });
  render(<RunsView initial={empty} />);
  expect(screen.getByText(/no runs match/i)).toBeInTheDocument();
});

it("shows a no-access state, not an empty list, when the backend says 403", () => {
  vi.mocked(useRunPolling).mockReturnValue({ data: initial, error: null, forbidden: true });
  render(<RunsView initial={initial} />);
  expect(screen.getByText(/don't have access to runs/i)).toBeInTheDocument();
  expect(screen.queryByText(/no runs match/i)).toBeNull();
});
