import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { StatusPill } from "./StatusPill";

describe("StatusPill", () => {
  it("renders a lowercased, spaced label for a known status", () => {
    render(<StatusPill status="IN_REVIEW" />);
    expect(screen.getByText("in review")).toBeInTheDocument();
  });

  it("falls back to a neutral style for an unrecognized status", () => {
    render(<StatusPill status="SOMETHING_NEW" />);
    expect(screen.getByText("something new")).toBeInTheDocument();
  });
});
