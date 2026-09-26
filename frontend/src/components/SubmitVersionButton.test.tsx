import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { SubmitVersionButton } from "./SubmitVersionButton";
import { submitVersionAction } from "./submissionActions";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));
vi.mock("./submissionActions", () => ({ submitVersionAction: vi.fn() }));

describe("SubmitVersionButton", () => {
  it("submits the version and refreshes the page", async () => {
    vi.mocked(submitVersionAction).mockResolvedValueOnce({ ok: true });
    render(<SubmitVersionButton agentId="a1" versionId="v1" />);

    await userEvent.click(screen.getByRole("button", { name: "Submit for review" }));

    expect(submitVersionAction).toHaveBeenCalledWith("a1", "v1");
    expect(refresh).toHaveBeenCalled();
  });

  it("shows the reason and does not refresh when the submission is refused", async () => {
    refresh.mockClear();
    vi.mocked(submitVersionAction).mockResolvedValueOnce({ ok: false, error: "Could not submit for review" });
    render(<SubmitVersionButton agentId="a1" versionId="v1" />);

    await userEvent.click(screen.getByRole("button", { name: "Submit for review" }));

    expect(screen.getByText("Could not submit for review")).toBeInTheDocument();
    expect(refresh).not.toHaveBeenCalled();
  });
});
