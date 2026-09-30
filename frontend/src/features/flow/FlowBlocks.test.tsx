import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { FlowBlocks } from "./FlowBlocks";
import type { BlockView } from "./blocks";

const base = { title: "T", body: "B", metric: null, undeclared: false } as const;
const views: BlockView[] = [
  { id: "a", kind: "Reads", state: "DONE", ...base },
  { id: "b", kind: "Asks you", state: "WAITING", ...base },
  { id: "c", kind: "Writes", state: "FAILED", ...base },
  { id: "d", kind: "Charges", state: "SKIPPED", ...base },
  { id: "e", kind: "Thinks", state: "IDLE", ...base },
];

it("states each block's status in words, not only colour", () => {
  render(<FlowBlocks blocks={views} />);
  ["Done", "Waiting for you", "Failed", "Skipped", "Not started"].forEach((t) => expect(screen.getByText(t)).toBeInTheDocument());
});

it("selects a block when clicked", async () => {
  const onSelect = vi.fn();
  render(<FlowBlocks blocks={views} onSelect={onSelect} />);
  await userEvent.click(screen.getAllByRole("button")[1]);
  expect(onSelect).toHaveBeenCalledWith("b");
});

it("marks undeclared blocks", () => {
  render(<FlowBlocks blocks={[{ ...views[0], undeclared: true }]} />);
  expect(screen.getByText(/not declared/i)).toBeInTheDocument();
});
