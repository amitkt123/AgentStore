import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PushInstructions } from "./PushInstructions";

describe("PushInstructions", () => {
  it("shows the command, the agent id and the version the bundle must declare", () => {
    render(<PushInstructions agentId="agent-123" versionNumber="1.0.0" />);
    const panel = screen.getByRole("region", { name: "Push your code" });
    expect(panel).toHaveTextContent("agentstore push --wait");
    expect(panel).toHaveTextContent("agent-123");
    expect(panel).toHaveTextContent("1.0.0");
  });

  it("warns that the bundle's manifest replaces the drafted one", () => {
    render(<PushInstructions agentId="a" versionNumber="1.0.0" />);
    expect(screen.getByText(/replaces the one you typed/i)).toBeInTheDocument();
  });
});
