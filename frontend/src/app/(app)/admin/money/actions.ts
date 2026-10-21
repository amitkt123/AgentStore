"use server";
import { revalidatePath } from "next/cache";
import { ApiError } from "@/lib/api/http";
import { getOrgMoney, patchOrgMoney, postAdjustment } from "@/lib/api/money";
import type { OrgMoney, OrgMoneyPatch } from "@/lib/api/types";

const fail = (e: unknown): { error: string } => {
  if (e instanceof ApiError) return { error: e.message };
  throw e;
};

export async function saveSettingsAction(orgId: string, patch: OrgMoneyPatch): Promise<{ org: OrgMoney } | { error: string }> {
  try {
    const org = await patchOrgMoney(orgId, patch);
    revalidatePath("/admin/money");
    return { org };
  } catch (e) { return fail(e); }
}

// A fresh requestId per submit; the form disables its button while one is in flight.
export async function adjustWalletAction(orgId: string, amount: number, note: string): Promise<{ org: OrgMoney } | { error: string }> {
  try {
    await postAdjustment(orgId, { requestId: crypto.randomUUID(), amount, note });
    const org = await getOrgMoney(orgId);
    revalidatePath("/admin/money");
    return { org };
  } catch (e) { return fail(e); }
}
