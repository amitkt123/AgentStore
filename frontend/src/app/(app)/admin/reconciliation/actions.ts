"use server";
import { revalidatePath } from "next/cache";
import { ApiError } from "@/lib/api/http";
import { listReconciliationIssues, resolveReconciliationIssue, runReconciliation } from "@/lib/api/money";
import type { ReconciliationIssue } from "@/lib/api/types";

type Result = { issues: ReconciliationIssue[] } | { error: string };
const fail = (e: unknown): { error: string } => {
  if (e instanceof ApiError) return { error: e.message };
  throw e;
};

export async function loadIssuesAction(date: string): Promise<Result> {
  try { return { issues: await listReconciliationIssues(date) }; } catch (e) { return fail(e); }
}

export async function runReconciliationAction(date: string): Promise<Result> {
  try {
    await runReconciliation(date);
    revalidatePath("/admin/reconciliation");
    return { issues: await listReconciliationIssues(date) };
  } catch (e) { return fail(e); }
}

export async function resolveIssueAction(id: string, note: string, date: string): Promise<Result> {
  try {
    await resolveReconciliationIssue(id, note);
    revalidatePath("/admin/reconciliation");
    return { issues: await listReconciliationIssues(date) };
  } catch (e) { return fail(e); }
}
