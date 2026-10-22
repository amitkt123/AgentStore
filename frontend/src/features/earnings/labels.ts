import type { PayoutAccount } from "@/lib/api/types";

/** "Bank ••1234" or the UPI id. Never more than the last 4 digits of an account number. */
export function accountLabel(a: PayoutAccount): string {
  return a.type === "BANK" ? `Bank ••${a.accountLast4 ?? ""}` : (a.vpa ?? "UPI");
}
