import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ReviewQueueList } from "./ReviewQueueList";
import type { ReviewItemResponse } from "@/lib/types";

const items: ReviewItemResponse[] = [
  { id: "ri1", agentVersionId: "v1", agentName: "Collections", versionNumber: "5.0", status: "PENDING" },
];

describe("ReviewQueueList", () => {
  it("links each item to its detail page", () => {
    render(<ReviewQueueList items={items} />);
    expect(screen.getByRole("link", { name: /Collections/i })).toHaveAttribute("href", "/review/ri1");
  });

  it("shows a message when the queue is empty", () => {
    render(<ReviewQueueList items={[]} />);
    expect(screen.getByText(/Nothing waiting for review/i)).toBeInTheDocument();
  });
});
