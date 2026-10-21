import { render, screen } from "@testing-library/react";
import { vi } from "vitest";
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));
vi.mock("@/app/(app)/billing/actions", () => ({}));
import { BillingView } from "./BillingView";
import { formatInr } from "@/lib/format";
import type { BillingSummary, Wallet } from "@/lib/api/types";

const prepaid: Wallet = {
  mode: "PREPAID", balance: 50000, held: 1500.5, available: 48499.5, spentThisMonth: 41220, monthlyLimit: null,
  creditLimit: null, owed: 0, cap: null, reserved: 1500.5,
};
const postpaid: Wallet = { ...prepaid, mode: "POSTPAID", balance: 0, creditLimit: 100000, owed: 25000, available: 74000, held: 1000 };

const summary: BillingSummary = {
  month: "2026-09", total: 600,
  agents: [{
    agentId: "a1", agent: "Invoice Reader", runs: 2, total: 600,
    lines: [
      { runDisplayId: "R-1001", units: 12, unitDisplay: "invoices", amount: 400, terminalReason: "FINISHED", decidedBy: null, at: "2026-09-03T10:00:00Z" },
      { runDisplayId: "R-1002", units: 5, unitDisplay: "invoices", amount: 200, terminalReason: "COST_CAP_REACHED", decidedBy: null, at: "2026-09-04T10:00:00Z" },
    ],
  }],
};
const empty: BillingSummary = { month: "2026-09", total: 0, agents: [] };

it("formats rupees with Indian grouping and two decimals", () => {
  expect(formatInr(100000)).toBe("₹1,00,000.00");
  expect(formatInr(41220)).toBe("₹41,220.00");
});

it("shows prepaid figures", () => {
  render(<BillingView wallet={prepaid} summary={summary} />);
  expect(screen.getByText("Balance")).toBeInTheDocument();
  expect(screen.getByText("On hold")).toBeInTheDocument();
  expect(screen.getByText("Spent this month")).toBeInTheDocument();
  expect(screen.getByText("₹41,220.00")).toBeInTheDocument();
  expect(screen.getByText("₹50,000.00")).toBeInTheDocument();
  expect(screen.queryByText("Credit limit")).not.toBeInTheDocument();
});

it("shows zeros for a never-funded prepaid wallet", () => {
  const unfunded: Wallet = {
    mode: "PREPAID", balance: 0, held: 0, available: 0, spentThisMonth: 0, monthlyLimit: null,
    creditLimit: null, owed: 0, cap: null, reserved: 0,
  };
  render(<BillingView wallet={unfunded} summary={empty} />);
  expect(screen.getByText("Balance")).toBeInTheDocument();
  expect(screen.getAllByText("₹0.00")).toHaveLength(4);
});

it("shows postpaid labels instead", () => {
  render(<BillingView wallet={postpaid} summary={summary} />);
  expect(screen.getByText("Credit limit")).toBeInTheDocument();
  expect(screen.getByText("Owed")).toBeInTheDocument();
  expect(screen.getByText("Available")).toBeInTheDocument();
  expect(screen.getByText("₹1,00,000.00")).toBeInTheDocument();
  expect(screen.queryByText("Balance")).not.toBeInTheDocument();
});

it("lists lines per agent with units and flags the capped run", () => {
  render(<BillingView wallet={prepaid} summary={summary} />);
  expect(screen.getByText("Invoice Reader")).toBeInTheDocument();
  expect(screen.getByText("12 invoices")).toBeInTheDocument();
  expect(screen.getAllByText("Stopped at limit")).toHaveLength(1);
});

it("says so when the month has no charges", () => {
  render(<BillingView wallet={prepaid} summary={empty} />);
  expect(screen.getByText("No charges this month.")).toBeInTheDocument();
});

it("links to the previous and next month", () => {
  render(<BillingView wallet={prepaid} summary={summary} />);
  expect(screen.getByRole("link", { name: /Previous month/ })).toHaveAttribute("href", "/billing?month=2026-08");
  expect(screen.getByRole("link", { name: /Next month/ })).toHaveAttribute("href", "/billing?month=2026-10");
});

it("wraps the year at month boundaries", () => {
  render(<BillingView wallet={prepaid} summary={{ ...empty, month: "2026-01" }} />);
  expect(screen.getByRole("link", { name: /Previous month/ })).toHaveAttribute("href", "/billing?month=2025-12");
});

const options = { provider: "fake", methods: ["UPI" as const], topUpMin: 100, topUpDailyLimit: 1000, topUpRemainingToday: 1000 };
const none = { options: null, topUps: null, refundable: null, refunds: null, bills: null };

it("shows the prepaid payment panels that loaded and hides the rest", () => {
  render(<BillingView wallet={prepaid} summary={empty} payments={{ ...none, options, topUps: [] }} />);
  expect(screen.getByRole("button", { name: "Add money" })).toBeInTheDocument();
  expect(screen.getByText("No top-ups yet.")).toBeInTheDocument();
  expect(screen.queryByText("Get money back")).not.toBeInTheDocument();
});

it("shows bills, not top-ups, for postpaid", () => {
  render(<BillingView wallet={postpaid} summary={empty} payments={{ ...none, options, topUps: [], bills: [] }} />);
  expect(screen.getByText("No bills yet.")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Add money" })).not.toBeInTheDocument();
});
