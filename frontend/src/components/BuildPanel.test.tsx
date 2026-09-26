import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { BuildPanel } from "./BuildPanel";
import type { BuildSummary } from "@/lib/types";

const failed: BuildSummary = {
  buildId: "b1",
  status: "FAILED",
  failureCode: "SECRET_FOUND",
  failureReason: "Secrets found in the bundle: config.py:3 (aws-access-token)",
  logUrl: "https://store.example/builds/b1.log?X-Amz-Signature=abc",
};

describe("BuildPanel", () => {
  it("shows the failure code, the reason and a link to the log", () => {
    render(<BuildPanel build={failed} />);
    expect(screen.getByText("SECRET_FOUND")).toBeInTheDocument();
    expect(screen.getByText(/config\.py:3/)).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "View build log" });
    expect(link).toHaveAttribute("href", failed.logUrl);
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noreferrer");
  });

  it("explains a running build and hides the log link until a log exists", () => {
    render(<BuildPanel build={{ buildId: "b2", status: "BUILDING", failureCode: null, failureReason: null, logUrl: null }} />);
    expect(screen.getByText(/Installing dependencies/)).toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("says a succeeded build can be submitted", () => {
    render(<BuildPanel build={{ buildId: "b3", status: "SUCCEEDED", failureCode: null, failureReason: null, logUrl: null }} />);
    expect(screen.getByText(/You can submit this version/)).toBeInTheDocument();
  });
});
