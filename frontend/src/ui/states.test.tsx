import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { EmptyState } from "./EmptyState";
import { ErrorState } from "./ErrorState";

it("shows the empty title and body", () => {
  render(<EmptyState title="No files yet" body="Upload a file to start." />);
  expect(screen.getByText("No files yet")).toBeInTheDocument();
  expect(screen.getByText("Upload a file to start.")).toBeInTheDocument();
});

it("shows the backend message and retries", async () => {
  const onRetry = vi.fn();
  render(<ErrorState message="Storage limit reached" onRetry={onRetry} />);
  expect(screen.getByRole("alert")).toHaveTextContent("Storage limit reached");
  await userEvent.click(screen.getByRole("button", { name: /try again/i }));
  expect(onRetry).toHaveBeenCalledOnce();
});
