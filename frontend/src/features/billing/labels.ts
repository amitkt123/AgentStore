import type { PaymentMethod } from "@/lib/api/types";

export const METHOD_LABEL: Record<PaymentMethod, string> = {
  UPI: "UPI", CARD: "Card", NETBANKING: "Netbanking", BANK_TRANSFER: "Bank transfer",
};
