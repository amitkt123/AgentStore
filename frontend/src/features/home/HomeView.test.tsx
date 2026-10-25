import { render, screen } from "@testing-library/react";
import { vi } from "vitest";
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock("@/app/(app)/connect/actions", () => ({ startRunAction: vi.fn() }));
import { HomeView } from "./HomeView";
import type { ConnectionSummary } from "@/lib/api/types";

const waiting = [{ id: "r1", displayId: "R-2291", agent: "Bank Reconciliation", version: "1.3.0", status: "AWAITING_APPROVAL" as const, terminalReason: null, reachedBlock: "gate:post", startedAt: "2026-09-29T04:35:00Z", completedAt: null, charged: 0, held: 500, decidedBy: null }];

it("greets by first name and lists runs that need approval", () => {
  render(<HomeView fullName="Priya Shah" role="BUYER_MEMBER" needsYou={waiting} connections={[]} />);
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

const conn = (over: Partial<ConnectionSummary> = {}): ConnectionSummary => ({ id: "c1", agentId: "a1", agentName: "Bank Reconciliation", grantedScopes: ["bank_statement"], spendCap: 500, status: "ACTIVE", ...over });

it("lists only active agents, links each to its store card and offers Run now", () => {
  render(<HomeView fullName="Priya Shah" role="BUYER_ADMIN" needsYou={[]} connections={[conn(), conn({ id: "c2", agentId: "a2", agentName: "Old Agent", status: "REVOKED" })]} />);
  expect(screen.getByRole("link", { name: "Bank Reconciliation" })).toHaveAttribute("href", "/store#agent-a1");
  expect(screen.queryByText("Old Agent")).toBeNull();
  expect(screen.getByRole("button", { name: "Run now" })).toBeInTheDocument();
});

it("confirms an agent was just added", () => {
  render(<HomeView fullName="Priya Shah" role="BUYER_ADMIN" needsYou={[]} connections={[conn()]} added="a1" />);
  expect(screen.getByRole("status")).toHaveTextContent("Bank Reconciliation is added. Run it when you're ready.");
});

it("offers Run now to a member, who runs agents but cannot add them", () => {
  render(<HomeView fullName="Priya Shah" role="BUYER_MEMBER" needsYou={[]} connections={[conn()]} />);
  expect(screen.getByRole("button", { name: "Run now" })).toBeInTheDocument();
});
