import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { SubmissionWizard } from "./SubmissionWizard";

vi.mock("./submissionActions", () => ({
  createAgentAction: vi.fn().mockResolvedValue({ ok: true, agentId: "a1" }),
  createVersionAction: vi.fn().mockResolvedValue({ ok: true, versionId: "v1" }),
  submitVersionAction: vi.fn().mockResolvedValue({ ok: true }),
}));
const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));

async function toLastStep() {
  await userEvent.click(screen.getByRole("button", { name: "Continue" }));
  await userEvent.click(screen.getByRole("button", { name: "Continue" }));
  await userEvent.click(screen.getByRole("button", { name: "Continue" }));
}

describe("SubmissionWizard", () => {
  it("adds a scope row and carries it through to the preview step", async () => {
    render(<SubmissionWizard />);
    await userEvent.type(screen.getByPlaceholderText("Agent name"), "Test Agent");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));

    await userEvent.click(screen.getByRole("button", { name: /Add a scope/i }));
    await userEvent.type(screen.getByPlaceholderText(/resource, e\.g\./i), "bank_statement");

    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));

    expect(screen.getByText("bank_statement")).toBeInTheDocument();
  });

  it("no longer asks for a template id", () => {
    render(<SubmissionWizard />);
    expect(screen.queryByPlaceholderText(/template/i)).not.toBeInTheDocument();
  });

  it("says that creating the draft does not submit it", async () => {
    render(<SubmissionWizard />);
    await toLastStep();
    expect(screen.getByText(/does not submit it/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Submit for review" })).not.toBeInTheDocument();
  });

  it("creates the agent, then an image-less draft version, then goes to the version page without submitting", async () => {
    const { createAgentAction, createVersionAction, submitVersionAction } = await import("./submissionActions");
    render(<SubmissionWizard />);
    await userEvent.type(screen.getByPlaceholderText("Agent name"), "Test Agent");
    await toLastStep();
    await userEvent.click(screen.getByRole("button", { name: "Create draft" }));

    expect(createAgentAction).toHaveBeenCalled();
    expect(createVersionAction).toHaveBeenCalledWith("a1", expect.objectContaining({ versionNumber: "1.0.0" }));
    expect(vi.mocked(createVersionAction).mock.calls.at(-1)![1]).not.toHaveProperty("imageRef");
    expect(submitVersionAction).not.toHaveBeenCalled();
    expect(push).toHaveBeenCalledWith("/dashboard/agents/a1/versions/v1/status");
  });

  it("skips agent creation when an existing agent is provided", async () => {
    const { createAgentAction } = await import("./submissionActions");
    vi.mocked(createAgentAction).mockClear();
    render(<SubmissionWizard existingAgent={{ id: "existing-1", name: "Existing Agent", category: "GST & TDS" }} />);
    expect(screen.queryByPlaceholderText("Agent name")).not.toBeInTheDocument();
    await toLastStep();
    await userEvent.click(screen.getByRole("button", { name: "Create draft" }));
    expect(createAgentAction).not.toHaveBeenCalled();
  });

  it("does not create a second agent when retrying after a version-creation failure", async () => {
    const { createAgentAction, createVersionAction } = await import("./submissionActions");
    vi.mocked(createAgentAction).mockClear();
    vi.mocked(createVersionAction).mockClear();
    vi.mocked(createVersionAction).mockResolvedValueOnce({ ok: false, error: "Could not save version" });

    render(<SubmissionWizard />);
    await userEvent.type(screen.getByPlaceholderText("Agent name"), "Test Agent");
    await toLastStep();

    await userEvent.click(screen.getByRole("button", { name: "Create draft" }));
    expect(screen.getByText("Could not save version")).toBeInTheDocument();
    expect(createAgentAction).toHaveBeenCalledTimes(1);

    await userEvent.click(screen.getByRole("button", { name: "Create draft" }));
    expect(createAgentAction).toHaveBeenCalledTimes(1);
    expect(createVersionAction).toHaveBeenCalledTimes(2);
    expect(createVersionAction).toHaveBeenLastCalledWith("a1", expect.objectContaining({ versionNumber: "1.0.0" }));
  });
});
