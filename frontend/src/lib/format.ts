export function formatMoney(n: number, currency = "INR"): string {
  const symbol = currency === "INR" ? "₹" : `${currency} `;
  const whole = Number.isInteger(n);
  return symbol + n.toLocaleString("en-IN", { minimumFractionDigits: whole ? 0 : 2, maximumFractionDigits: 2 });
}

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  const text = Number.isInteger(v) ? String(v) : v.toFixed(1);
  return `${text} ${units[i]}`;
}

export function humanize(resource: string): string {
  const s = resource.replace(/[_-]+/g, " ").trim();
  return s.charAt(0).toUpperCase() + s.slice(1).toLowerCase();
}

export function formatTime(iso: string | null): string {
  if (!iso) return "–";
  return new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false });
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** A past moment: the time alone for today, with the day for earlier dates, and the year when it is not this year. */
export function formatWhen(iso: string | null, now: Date = new Date()): string {
  if (!iso) return "–";
  const d = new Date(iso);
  const time = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  if (d.toDateString() === now.toDateString()) return time;
  const day = `${d.getDate()} ${MONTHS[d.getMonth()]}`;
  return d.getFullYear() === now.getFullYear() ? `${day}, ${time}` : `${day} ${d.getFullYear()}, ${time}`;
}

export function formatDate(iso: string | null): string {
  if (!iso) return "–";
  return new Date(iso).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
}

/** Rupees with Indian digit grouping and always two decimals: 100000 -> ₹1,00,000.00. */
export function formatInr(n: number): string {
  const text = Math.abs(n).toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return (n < 0 && text !== "0.00" ? "-₹" : "₹") + text;
}
