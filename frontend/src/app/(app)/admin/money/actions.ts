"use server";
import { revalidatePath } from "next/cache";
import { ApiError } from "@/lib/api/http";
import { freezeOrg, getOrgMoney, patchOrgMoney, postAdjustment, unfreezeOrg } from "@/lib/api/money";
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

// The form owns requestId and reuses it on retry, so a repeat of a posted adjustment is a server-side replay.
// Once the post has succeeded this never reports failure: a failed refetch only costs the fresh balance.
export async function adjustWalletAction(
  orgId: string, amount: number, note: string, requestId: string,
): Promise<{ org: OrgMoney | null; notice?: string } | { error: string }> {
  try {
    await postAdjustment(orgId, { requestId, amount, note });
  } catch (e) { return fail(e); }
  revalidatePath("/admin/money");
  try {
    return { org: await getOrgMoney(orgId) };
  } catch {
    return { org: null, notice: "Adjustment saved; refresh to see the new balance" };
  }
}

export async function freezeOrgAction(orgId: string, reason: string): Promise<{ org: OrgMoney } | { error: string }> {
  try {
    const org = await freezeOrg(orgId, reason);
    revalidatePath("/admin/money");
    return { org };
  } catch (e) { return fail(e); }
}

export async function unfreezeOrgAction(orgId: string): Promise<{ org: OrgMoney } | { error: string }> {
  try {
    const org = await unfreezeOrg(orgId);
    revalidatePath("/admin/money");
    return { org };
  } catch (e) { return fail(e); }
}
