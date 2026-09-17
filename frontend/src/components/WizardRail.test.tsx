import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { WizardRail } from "./WizardRail";

describe("WizardRail", () => {
  it("renders every step label", () => {
    render(<WizardRail steps={["Basics", "Scopes", "Review"]} currentIndex={1} />);
    expect(screen.getByText("Basics")).toBeInTheDocument();
    expect(screen.getByText("Scopes")).toBeInTheDocument();
    expect(screen.getByText("Review")).toBeInTheDocument();
  });
});
