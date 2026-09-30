// Public endpoints behind the link in an approval email. The token is the credential, so these never send the session.
export type ApprovalCard = { runDisplayId: string; agentName: string; action: string; summary: string; expiresAt: string };
export type ApprovalOutcome = { decision: "APPROVED" | "REJECTED"; runDisplayId: string };
export type LinkFailure = "gone" | "busy" | "not-approver" | "error";

const url = (token: string, suffix = "") => `${process.env.BACKEND_URL}/api/v1/approvals/${encodeURIComponent(token)}${suffix}`;

function failure(status: number): LinkFailure {
  if (status === 404 || status === 410) return "gone";
  if (status === 429) return "busy";
  if (status === 403) return "not-approver";
  return "error";
}

export async function getApprovalCard(token: string): Promise<{ ok: true; card: ApprovalCard } | { ok: false; reason: LinkFailure }> {
  try {
    const res = await fetch(url(token), { cache: "no-store" });
    if (!res.ok) return { ok: false, reason: failure(res.status) };
    return { ok: true, card: (await res.json()) as ApprovalCard };
  } catch {
    return { ok: false, reason: "error" };
  }
}

export async function decideByLink(token: string, decision: "APPROVE" | "REJECT"): Promise<{ ok: true; outcome: ApprovalOutcome } | { ok: false; reason: LinkFailure }> {
  try {
    const res = await fetch(url(token, "/decision"), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ decision }),
      cache: "no-store",
    });
    if (!res.ok) return { ok: false, reason: failure(res.status) };
    return { ok: true, outcome: (await res.json()) as ApprovalOutcome };
  } catch {
    return { ok: false, reason: "error" };
  }
}
