import { formatInr, formatBytes, formatMoney, humanize, formatDate } from "./format";

it("formats rupees with Indian grouping", () => {
  expect(formatMoney(500)).toBe("₹500");
  expect(formatMoney(3841260)).toBe("₹38,41,260");
  expect(formatMoney(148.5)).toBe("₹148.50");
});
it("formats bytes", () => {
  expect(formatBytes(0)).toBe("0 B");
  expect(formatBytes(1153434)).toBe("1.1 MB");
  expect(formatBytes(5 * 1024 ** 3)).toBe("5 GB");
});
it("humanizes resource names", () => {
  expect(humanize("bank_statement")).toBe("Bank statement");
  expect(humanize("open-invoices")).toBe("Open invoices");
  expect(humanize("BASIC_PERSONAL")).toBe("Basic personal");
});

it("formats a date with day, month and year", () => {
  expect(formatDate("2026-09-30T12:00:00Z")).toMatch(/30 Sep(t)? 2026/);
  expect(formatDate(null)).toBe("–");
});

it("formatInr puts the minus sign before the rupee symbol", () => {
  expect(formatInr(-1500.5)).toBe("-₹1,500.50");
  expect(formatInr(1500)).toBe("₹1,500.00");
  expect(formatInr(0)).toBe("₹0.00");
});
