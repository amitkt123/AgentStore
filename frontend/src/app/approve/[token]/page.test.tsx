import { render, screen } from "@testing-library/react";
import { vi } from "vitest";

vi.mock("./actions", () => ({ decideByLinkAction: vi.fn() }));
vi.mock("@/lib/api/approvals", () => ({ getApprovalCard: vi.fn() }));
import { getApprovalCard } from "@/lib/api/approvals";
import Page from "./page";

it("renders the card for a valid link", async () => {
  vi.mocked(getApprovalCard).mockResolvedValue({ ok: true, card: { runDisplayId: "R-1", agentName: "A", action: "x", summary: "Do it?", expiresAt: "2999-01-01T00:00:00Z" } });
  render(await Page({ params: { token: "t" } }));
  expect(screen.getByText("Do it?")).toBeInTheDocument();
});

it("says the link is no longer valid when it is gone", async () => {
  vi.mocked(getApprovalCard).mockResolvedValue({ ok: false, reason: "gone" });
  render(await Page({ params: { token: "t" } }));
  expect(screen.getByRole("alert")).toHaveTextContent(/no longer valid/i);
});

it("says to try again when rate limited", async () => {
  vi.mocked(getApprovalCard).mockResolvedValue({ ok: false, reason: "busy" });
  render(await Page({ params: { token: "t" } }));
  expect(screen.getByRole("alert")).toHaveTextContent(/too many attempts/i);
});
