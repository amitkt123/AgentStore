"use server";
import { revalidatePath } from "next/cache";
import { ApiError } from "@/lib/api/http";
import { approvePayoutAccount, fetchWithdrawals, type WithdrawalFilter } from "@/lib/api/money";
import type { AdminPayoutAccount, AdminWithdrawal } from "@/lib/api/types";

const fail = (e: unknown): { error: string } => {
  if (e instanceof ApiError) return { error: e.message };
  throw e;
};

export async function loadWithdrawalsAction(filter: WithdrawalFilter): Promise<{ withdrawals: AdminWithdrawal[] } | { error: string }> {
  try { return { withdrawals: await fetchWithdrawals(filter) }; } catch (e) { return fail(e); }
}

export async function approveAccountAction(id: string, note: string): Promise<{ account: AdminPayoutAccount } | { error: string }> {
  try {
    const account = await approvePayoutAccount(id, note);
    revalidatePath("/admin/payouts");
    return { account };
  } catch (e) { return fail(e); }
}
