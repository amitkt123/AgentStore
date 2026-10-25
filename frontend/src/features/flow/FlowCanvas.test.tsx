import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { FlowCanvas, FlowMini, miniLabel } from "./FlowCanvas";
import { layoutFlow } from "./layout";
import { bankManifest, fixtureViews, manifestViews } from "./fixtures";
import type { BlockView } from "./blocks";

const bankGraph = () => layoutFlow(manifestViews(bankManifest));
const buttons = () => within(screen.getByRole("list", { name: "Flow" })).getAllByRole("button");

it("lists nodes in stage order", () => {
  render(<FlowCanvas graph={bankGraph()} />);
  expect(buttons().map((b) => b.dataset.nodeId)).toEqual(["read:bank_statement", "llm", "draft:ledger", "gate:post_entries", "post:ledger", "charge"]);
});

it("states each node's status in words, not only colour", () => {
  const base = { title: "T", body: "B", metric: null, undeclared: false } as const;
  const views: BlockView[] = [
    { id: "read:a", kind: "Reads", state: "DONE", ...base },
    { id: "gate:b", kind: "Asks you", state: "WAITING", ...base },
    { id: "write:c", kind: "Writes", state: "FAILED", ...base },
    { id: "charge", kind: "Charges", state: "SKIPPED", ...base },
    { id: "llm", kind: "Thinks", state: "IDLE", ...base },
  ];
  render(<FlowCanvas graph={layoutFlow(views)} showState />);
  ["Done", "Waiting for you", "Failed", "Skipped", "Not started"].forEach((t) => expect(screen.getByText(t)).toBeInTheDocument());
});

it("selects a node when clicked", async () => {
  const onSelect = vi.fn();
  const { rerender } = render(<FlowCanvas graph={bankGraph()} onSelect={onSelect} />);
  await userEvent.click(buttons()[3]);
  expect(onSelect).toHaveBeenCalledWith("gate:post_entries");
  rerender(<FlowCanvas graph={bankGraph()} onSelect={onSelect} selectedId="gate:post_entries" />);
  expect(buttons()[3]).toHaveAttribute("aria-pressed", "true");
});

it("tells screen readers about the fork", () => {
  render(<FlowCanvas graph={bankGraph()} />);
  expect(buttons()[3]).toHaveAccessibleName(/then Approved continues, Rejected stops/);
});

it("draws an arrow per edge, with the fork labelled", () => {
  const g = bankGraph();
  const { container } = render(<FlowCanvas graph={g} />);
  expect(container.querySelectorAll("path[data-edge]")).toHaveLength(g.edges.length);
  expect(container.querySelectorAll('path[data-edge="rejected"]')).toHaveLength(1);
  const svgText = container.querySelector("svg")!.textContent;
  expect(svgText).toContain("Approved");
  expect(svgText).toContain("Rejected");
  expect(screen.getByText("Stops here, nothing is posted")).toBeInTheDocument();
});

it("marks undeclared nodes off the path", () => {
  render(<FlowCanvas graph={layoutFlow(fixtureViews())} />);
  expect(within(screen.getByRole("listitem", { name: "Off the declared path" })).getByText("Not declared by the agent")).toBeInTheDocument();
});

it("pulses only on a live run", () => {
  const g = layoutFlow(fixtureViews(), { reachedBlock: "gate:post_entries" });
  const { container, rerender } = render(<FlowCanvas graph={g} live />);
  expect(container.querySelectorAll("circle")).toHaveLength(1);
  expect(container.querySelectorAll('path[data-active="true"]')).toHaveLength(1);
  rerender(<FlowCanvas graph={g} />);
  expect(container.querySelectorAll("circle")).toHaveLength(0);
  expect(container.querySelectorAll('path[data-active="true"]')).toHaveLength(1);
});

it("renders a lone charge node without arrows", () => {
  const g = layoutFlow(manifestViews({ ...bankManifest, scopes: [], approvalGates: [], llm: null }));
  const { container } = render(<FlowCanvas graph={g} />);
  expect(buttons()).toHaveLength(1);
  expect(container.querySelectorAll("path[data-edge]")).toHaveLength(0);
});

it("labels the mini flow in words", () => {
  expect(miniLabel(bankGraph())).toBe("Reads 1, thinks, drafts 1, asks you once, writes 1, then charges");
  render(<FlowMini graph={bankGraph()} />);
  expect(screen.getByRole("img")).toHaveAccessibleName("Reads 1, thinks, drafts 1, asks you once, writes 1, then charges");
});
