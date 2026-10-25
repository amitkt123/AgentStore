"use server";
import { revalidatePath } from "next/cache";
import { ApiError } from "@/lib/api/http";
import { addPayoutAccount, createWithdrawal, simulatePayout } from "@/lib/api/money";
import type { PayoutAccount, PayoutAccountBody, PayoutSimulateOutcome, Withdrawal } from "@/lib/api/types";

const fail = (e: unknown): { error: string } => {
  if (e instanceof ApiError) return { error: e.message };
  throw e;
};

export async function savePayoutAccount(body: PayoutAccountBody): Promise<{ account: PayoutAccount } | { error: string }> {
  try {
    const account = await addPayoutAccount(body);
    revalidatePath("/developer/earnings");
    return { account };
  } catch (e) { return fail(e); }
}

// requestId comes from the form, one per intended withdrawal, so a retry is not a second payout.
export async function withdraw(amount: number, requestId: string): Promise<{ withdrawal: Withdrawal } | { error: string }> {
  try {
    const withdrawal = await createWithdrawal({ requestId, amount });
    revalidatePath("/developer/earnings");
    return { withdrawal };
  } catch (e) { return fail(e); }
}

export async function simulatePayoutAction(withdrawalId: string, outcome: PayoutSimulateOutcome): Promise<{ ok: true } | { error: string }> {
  try {
    await simulatePayout(withdrawalId, outcome);
    revalidatePath("/developer/earnings");
    return { ok: true };
  } catch (e) { return fail(e); }
}
