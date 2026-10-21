export const MONTH_RE = /^\d{4}-(0[1-9]|1[0-2])$/;

export function parseMonth(value: string | undefined): string | undefined {
  return value && MONTH_RE.test(value) ? value : undefined;
}

export function shiftMonth(month: string, delta: number): string {
  const [y, m] = month.split("-").map(Number);
  const idx = y * 12 + (m - 1) + delta;
  return `${Math.floor(idx / 12)}-${String((idx % 12) + 1).padStart(2, "0")}`;
}
