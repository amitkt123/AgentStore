import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { CatalogList } from "./CatalogList";
import type { CatalogListingResponse } from "@/lib/types";

const listings: CatalogListingResponse[] = [
  { agentId: "a1", agentName: "GSTR-2B Reconciler", category: "GST & TDS", versionId: "v1", versionNumber: "4.2" },
];

describe("CatalogList", () => {
  it("links each listing to its agent detail page", () => {
    render(<CatalogList listings={listings} />);
    const link = screen.getByRole("link", { name: /GSTR-2B Reconciler/i });
    expect(link).toHaveAttribute("href", "/agents/a1/versions/v1");
  });

  it("shows a message when there are no live agents yet", () => {
    render(<CatalogList listings={[]} />);
    expect(screen.getByText(/No agents are live yet/i)).toBeInTheDocument();
  });
});
