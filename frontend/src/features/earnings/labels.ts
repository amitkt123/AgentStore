import type { PayoutAccount } from "@/lib/api/types";

/** "Bank ••1234" or the UPI id. Never more than the last 4 digits of an account number. */
export function accountLabel(a: PayoutAccount): string {
  return a.type === "BANK" ? `Bank ••${a.accountLast4 ?? ""}` : (a.vpa ?? "UPI");
}

export const GENERIC_ERROR = "Something went wrong. Try again.";

const FAILURE_TEXT: Record<string, string> = {
  VALIDATION_FAILED: "The bank could not verify this account. Check the details or try another account.",
};

/** Plain text for a payout account failure; unknown UPPER_SNAKE codes get a generic sentence, other text is shown as is. */
export function failureText(reason: string | null): string {
  if (!reason) return "The bank could not verify this account.";
  if (FAILURE_TEXT[reason]) return FAILURE_TEXT[reason];
  return /^[A-Z][A-Z0-9_]+$/.test(reason) ? "The bank could not verify this account." : reason;
}

/** The exact withdrawal status, for admins (developers see the simpler Sending / Sent wording). */
export const WITHDRAWAL_STATUS_LABEL: Record<string, string> = {
  REQUESTED: "Requested", PROCESSING: "Processing", PROCESSED: "Processed", REVERSED: "Reversed", FAILED: "Failed",
};
