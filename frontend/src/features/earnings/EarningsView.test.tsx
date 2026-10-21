import { render, screen } from "@testing-library/react";
import { EarningsView } from "./EarningsView";
import type { Earnings } from "@/lib/api/types";

const earnings: Earnings = {
  month: "2026-10", buyersPaid: 10000, platformFee: 1500, feePercent: 15, aiUsage: 500, tax: 0, earned: 8000, available: 6000.5,
  byAgent: [{ agentId: "a1", agent: "Invoice Reader", runs: 4, buyersPaid: 10000, fee: 1500, aiUsage: 500, earned: 8000, perRun: 2000 }],
};

it("renders the headline figures formatted", () => {
  render(<EarningsView earnings={earnings} />);
  expect(screen.getAllByText("Buyers paid").length).toBeGreaterThan(0);
  expect(screen.getByText("Karya fee (15%)")).toBeInTheDocument();
  expect(screen.getAllByText("AI usage").length).toBeGreaterThan(0);
  expect(screen.getByText("You earned")).toBeInTheDocument();
  expect(screen.getAllByText("₹10,000.00").length).toBeGreaterThan(0);
  expect(screen.getByText("Available to withdraw")).toBeInTheDocument();
  expect(screen.getByText("₹6,000.50")).toBeInTheDocument();
});

it("hides Tax when zero and shows it otherwise", () => {
  const { rerender } = render(<EarningsView earnings={earnings} />);
  expect(screen.queryByText("Tax")).not.toBeInTheDocument();
  rerender(<EarningsView earnings={{ ...earnings, tax: 270 }} />);
  expect(screen.getByText("Tax")).toBeInTheDocument();
  expect(screen.getByText("₹270.00")).toBeInTheDocument();
});

it("explains a negative available balance", () => {
  render(<EarningsView earnings={{ ...earnings, available: -250 }} />);
  expect(screen.getByText("You owe ₹250.00; it comes out of future earnings")).toBeInTheDocument();
});

it("lists per-agent rows", () => {
  render(<EarningsView earnings={earnings} />);
  expect(screen.getByText("Invoice Reader")).toBeInTheDocument();
  expect(screen.getByText("₹2,000.00")).toBeInTheDocument();
});

it("links the CSV download and month navigation, with no withdraw button", () => {
  render(<EarningsView earnings={earnings} />);
  expect(screen.getByRole("link", { name: "Download statement (CSV)" })).toHaveAttribute("href", "/api/developer/statements/2026-10");
  expect(screen.getByRole("link", { name: "Previous month" })).toHaveAttribute("href", "/developer/earnings?month=2026-09");
  expect(screen.getByRole("link", { name: "Next month" })).toHaveAttribute("href", "/developer/earnings?month=2026-11");
  expect(screen.queryByRole("button", { name: /withdraw/i })).not.toBeInTheDocument();
});
