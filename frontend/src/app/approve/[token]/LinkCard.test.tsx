import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

vi.mock("./actions", () => ({ decideByLinkAction: vi.fn() }));
import { decideByLinkAction } from "./actions";
import { LinkCard } from "./LinkCard";

const card = { runDisplayId: "R-12", agentName: "Ledger", action: "post", summary: "Post 142 entries?", expiresAt: "2999-01-01T00:00:00Z" };

it("shows the request and posts an approval", async () => {
  vi.mocked(decideByLinkAction).mockResolvedValue({ ok: true, outcome: { decision: "APPROVED", runDisplayId: "R-12" } });
  render(<LinkCard token="t" card={card} />);
  expect(screen.getByText("Post 142 entries?")).toBeInTheDocument();
  expect(screen.getByText(/Ledger/)).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Approve" }));
  expect(decideByLinkAction).toHaveBeenCalledWith("t", "APPROVE");
  expect(await screen.findByText(/You approved this/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
});

it("posts a rejection", async () => {
  vi.mocked(decideByLinkAction).mockResolvedValue({ ok: true, outcome: { decision: "REJECTED", runDisplayId: "R-12" } });
  render(<LinkCard token="t" card={card} />);
  await userEvent.click(screen.getByRole("button", { name: "Reject" }));
  expect(decideByLinkAction).toHaveBeenCalledWith("t", "REJECT");
  expect(await screen.findByText(/You rejected this/)).toBeInTheDocument();
});

it.each([
  ["not-approver", /no longer an approver/i],
  ["gone", /no longer valid/i],
  ["busy", /too many attempts/i],
  ["error", /something went wrong/i],
] as const)("shows %s as a sentence and keeps the buttons", async (reason, text) => {
  vi.mocked(decideByLinkAction).mockResolvedValue({ ok: false, reason });
  render(<LinkCard token="t" card={card} />);
  await userEvent.click(screen.getByRole("button", { name: "Approve" }));
  await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent(text));
  expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled();
});

it("disables the buttons once the card has expired", () => {
  render(<LinkCard token="t" card={{ ...card, expiresAt: "2000-01-01T00:00:00Z" }} />);
  expect(screen.getByText(/has expired/i)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
});
