import { render, screen } from "@testing-library/react";
import { StorageBar } from "./StorageBar";

it("shows used of limit", () => {
  render(<StorageBar usedBytes={1153434} limitBytes={5 * 1024 ** 3} />);
  expect(screen.getByText("1.1 MB of 5 GB used")).toBeInTheDocument();
});
it("says so when storage is full and does not overflow", () => {
  render(<StorageBar usedBytes={6 * 1024 ** 3} limitBytes={5 * 1024 ** 3} />);
  expect(screen.getByText(/storage is full/i)).toBeInTheDocument();
  expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "100");
});
