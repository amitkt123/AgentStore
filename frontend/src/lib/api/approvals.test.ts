import { vi } from "vitest";
import { decideByLink, getApprovalCard } from "./approvals";

beforeEach(() => { process.env.BACKEND_URL = "http://be"; });

const card = { runDisplayId: "R-12", agentName: "Ledger", action: "post", summary: "Post 142 entries?", expiresAt: "2999-01-01T00:00:00Z" };

it("fetches the card without any credential", async () => {
  const f = vi.fn().mockResolvedValue(new Response(JSON.stringify(card), { status: 200 }));
  vi.stubGlobal("fetch", f);
  expect(await getApprovalCard("tok/1")).toEqual({ ok: true, card });
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/approvals/tok%2F1");
  expect(JSON.stringify(f.mock.calls[0][1])).not.toMatch(/authorization/i);
});

it.each([[404, "gone"], [410, "gone"], [429, "busy"], [500, "error"]])("maps a %i on the card to %s", async (status, reason) => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status })));
  expect(await getApprovalCard("t")).toEqual({ ok: false, reason });
});

it("posts the decision without a credential", async () => {
  const f = vi.fn().mockResolvedValue(new Response(JSON.stringify({ decision: "APPROVED", runDisplayId: "R-12" }), { status: 200 }));
  vi.stubGlobal("fetch", f);
  expect(await decideByLink("t", "APPROVE")).toEqual({ ok: true, outcome: { decision: "APPROVED", runDisplayId: "R-12" } });
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/approvals/t/decision");
  expect(JSON.parse(f.mock.calls[0][1].body)).toEqual({ decision: "APPROVE" });
  expect(JSON.stringify(f.mock.calls[0][1].headers)).not.toMatch(/authorization/i);
});

it.each([[403, "not-approver"], [410, "gone"], [429, "busy"], [500, "error"]])("maps a %i on the decision to %s", async (status, reason) => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status })));
  expect(await decideByLink("t", "REJECT")).toEqual({ ok: false, reason });
});

it("reports a network failure as an error", async () => {
  vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("down")));
  expect(await getApprovalCard("t")).toEqual({ ok: false, reason: "error" });
});
