import { layoutFlow } from "./layout";
import { bankManifest, fixtureViews, manifestViews } from "./fixtures";
import type { BlockView } from "./blocks";

const bank = () => manifestViews(bankManifest);
const ids = (g: ReturnType<typeof layoutFlow>) => g.columns.map((c) => c.stage);

it("orders stages Reads, Thinks, Drafts, Asks you, Writes, Charges", () => {
  const g = layoutFlow(bank());
  expect(ids(g)).toEqual(["Reads", "Thinks", "Drafts", "Asks you", "Writes", "Charges"]);
  expect(g.columns[4].nodes).toHaveLength(1);
  expect(g.columns[4].nodes[0]).toMatchObject({ id: "post:ledger", title: "Posts ledger", derived: true, state: "IDLE" });
});

it("forks each gate into approved and rejected", () => {
  const g = layoutFlow(bank());
  expect(g.edges).toContainEqual({ from: "gate:post_entries", to: "post:ledger", kind: "approved" });
  expect(g.edges).toContainEqual({ from: "gate:post_entries", to: "end:gate:post_entries", kind: "rejected" });
  expect(g.ends[0].label).toBe("Stops here, nothing is posted");
});

it("fans in from several reads", () => {
  const m = { ...bankManifest, scopes: [
    { type: "READ" as const, resource: "a", description: "", requiresApproval: false },
    { type: "READ" as const, resource: "b", description: "", requiresApproval: false },
  ] };
  const g = layoutFlow(manifestViews(m));
  expect(g.edges).toContainEqual({ from: "read:a", to: "llm", kind: "main" });
  expect(g.edges).toContainEqual({ from: "read:b", to: "llm", kind: "main" });
});

it("derives the post state from the gate outcome", () => {
  const post = (o: Parameters<typeof layoutFlow>[1]) => layoutFlow(bank(), o).columns[4].nodes[0].state;
  expect(post({ gateOutcome: "APPROVED" })).toBe("DONE");
  expect(post({ gateOutcome: "REJECTED" })).toBe("SKIPPED");
  expect(layoutFlow(bank(), { gateOutcome: "REJECTED" }).ends[0].reached).toBe(true);
  expect(post({ gateOutcome: "EXPIRED" })).toBe("SKIPPED");
  expect(post({ gateOutcome: null, terminal: true })).toBe("SKIPPED");
});

it("lays out the run fixture with the undeclared block off the path", () => {
  const g = layoutFlow(fixtureViews());
  expect(ids(g)).toEqual(["Reads", "Asks you", "Charges"]);
  expect(g.offPath.map((n) => n.id)).toEqual(["write:secret"]);
  expect(g.edges).toContainEqual({ from: "gate:post_entries", to: "write:secret", kind: "undeclared" });
});

it("draws a single charge node with no edges", () => {
  const g = layoutFlow(manifestViews({ ...bankManifest, scopes: [], approvalGates: [], llm: null }));
  expect(ids(g)).toEqual(["Charges"]);
  expect(g.edges).toEqual([]);
  expect(g.ends).toEqual([]);
});

it("gives an undeclared block in the first stage no edge", () => {
  const read: BlockView = { id: "read:x", kind: "Reads", title: "X", body: "", state: "DONE", metric: null, undeclared: true };
  const charge: BlockView = { id: "charge", kind: "Charges", title: "Charges", body: "", state: "IDLE", metric: null, undeclared: false };
  const g = layoutFlow([read, charge]);
  expect(g.offPath).toHaveLength(1);
  expect(g.edges.filter((e) => e.to === "read:x")).toEqual([]);
});

it("points the active edge at the waiting node, and at nothing once terminal", () => {
  expect(layoutFlow(fixtureViews(), { reachedBlock: "gate:post_entries" }).activeTo).toBe("gate:post_entries");
  expect(layoutFlow(fixtureViews(), { reachedBlock: "gate:post_entries", terminal: true }).activeTo).toBeNull();
});
