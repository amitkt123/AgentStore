import { render, screen } from "@testing-library/react";
import { ReadinessBanner } from "./ReadinessBanner";
import type { BuildSummary, Readiness } from "@/lib/api/types";

const build = (over: Partial<BuildSummary> = {}): BuildSummary => ({ buildId: "b1", status: "SUCCEEDED", failureCode: null, failureReason: null, logUrl: null, ...over });
const blocked = (...blockers: Readiness["blockers"]): Readiness => ({ ready: false, blockers });

it("says ready when nothing blocks", () => {
  render(<ReadinessBanner readiness={{ ready: true, blockers: [] }} build={build()} />);
  expect(screen.getByText("Ready to submit")).toBeInTheDocument();
});

it("shows a failed build with its reason and a log link", () => {
  render(<ReadinessBanner readiness={blocked({ code: "BUILD_NOT_READY", message: "no build", runId: null })} build={build({ status: "FAILED", failureReason: "pip install failed", logUrl: "https://logs.example.com/b1" })} />);
  expect(screen.getByText(/Build status: failed/)).toBeInTheDocument();
  expect(screen.getByText("pip install failed")).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "View build log" })).toHaveAttribute("href", "https://logs.example.com/b1");
});

it("says there is no build yet when the version has none", () => {
  render(<ReadinessBanner readiness={blocked({ code: "BUILD_NOT_READY", message: "no build", runId: null })} build={null} />);
  expect(screen.getByText(/Build status: no build yet/)).toBeInTheDocument();
});

it("does not link a log address that is not http or a path", () => {
  render(<ReadinessBanner readiness={blocked({ code: "BUILD_NOT_READY", message: "x", runId: null })} build={build({ status: "FAILED", logUrl: "javascript:alert(1)" })} />);
  expect(screen.queryByRole("link")).not.toBeInTheDocument();
});

it("shows repeated undeclared-access blockers once, with a count and the run as plain text", () => {
  const u = { code: "UNDECLARED_ACCESS" as const, message: "The last sandbox run attempted access outside its declared manifest", runId: "run-1" };
  render(<ReadinessBanner readiness={blocked(u, u, u)} build={build()} />);
  expect(screen.getAllByText(/attempted access outside/)).toHaveLength(1);
  expect(screen.getByText(/3 attempts/)).toBeInTheDocument();
  expect(screen.getByText("run-1")).toBeInTheDocument();
  expect(screen.queryByRole("link")).not.toBeInTheDocument();
});

it("shows the cost envelope message", () => {
  render(<ReadinessBanner readiness={blocked({ code: "COST_ENVELOPE_EXCEEDED", message: "The last sandbox run asked for more than its cost envelope allows", runId: "run-9" })} build={build()} />);
  expect(screen.getByText("Over the cost envelope")).toBeInTheDocument();
  expect(screen.getByText(/more than its cost envelope allows/)).toBeInTheDocument();
});
