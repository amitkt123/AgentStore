"use server";
import { decideByLink } from "@/lib/api/approvals";

export async function decideByLinkAction(token: string, decision: "APPROVE" | "REJECT") {
  return decideByLink(token, decision);
}
