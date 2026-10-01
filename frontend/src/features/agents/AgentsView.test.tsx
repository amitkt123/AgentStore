import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

const createAgent = vi.hoisted(() => vi.fn());
const createVersion = vi.hoisted(() => vi.fn());
vi.mock("@/app/(app)/developer/agents/actions", () => ({
  createAgentAction: (...a: unknown[]) => createAgent(...a),
  createVersionAction: (...a: unknown[]) => createVersion(...a),
}));
import { AgentsView } from "./AgentsView";
import type { MyAgent } from "@/lib/api/types";

const agent = (over: Partial<MyAgent> = {}): MyAgent => ({
  agentId: "a1", agentName: "Invoice bot", category: "Finance",
  versions: [{ id: "v1", versionNumber: "1.0.0", status: "DRAFT" }, { id: "v2", versionNumber: "1.1.0", status: "LIVE" }],
  ...over,
});

beforeEach(() => { createAgent.mockReset(); createVersion.mockReset(); });

const openNewAgent = () => userEvent.click(screen.getByRole("button", { name: "New agent" }));
const openNewVersion = () => userEvent.click(screen.getByRole("button", { name: "New version for Invoice bot" }));
const pasteManifest = async (text: string) => {
  await userEvent.click(screen.getByLabelText("Manifest JSON"));
  await userEvent.paste(text);
};

it("shows an empty state when there are no agents", () => {
  render(<AgentsView agents={[]} />);
  expect(screen.getByText("No agents yet")).toBeInTheDocument();
});

it("lists each agent with its versions as links to the Studio", () => {
  render(<AgentsView agents={[agent()]} />);
  expect(screen.getByText("Invoice bot")).toBeInTheDocument();
  expect(screen.getByText("Finance")).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Open version 1.0.0, Draft" })).toHaveAttribute("href", "/developer/agents/a1/versions/v1");
  expect(screen.getByRole("link", { name: "Open version 1.1.0, Live" })).toHaveAttribute("href", "/developer/agents/a1/versions/v2");
});

it("says so when an agent has no versions", () => {
  render(<AgentsView agents={[agent({ versions: [] })]} />);
  expect(screen.getByText("No versions yet.")).toBeInTheDocument();
});

it("asks for a name and a category before creating an agent", async () => {
  render(<AgentsView agents={[]} />);
  await openNewAgent();
  await userEvent.click(screen.getByRole("button", { name: "Create agent" }));
  expect(screen.getByText("Give the agent a name.")).toBeInTheDocument();
  expect(screen.getByText("Add a category, for example Finance.")).toBeInTheDocument();
  expect(createAgent).not.toHaveBeenCalled();
});

it("creates an agent and closes the form", async () => {
  createAgent.mockResolvedValue({ ok: true });
  render(<AgentsView agents={[]} />);
  await openNewAgent();
  await userEvent.type(screen.getByLabelText("Name"), "Invoice bot");
  await userEvent.type(screen.getByLabelText("Category"), "Finance");
  await userEvent.click(screen.getByRole("button", { name: "Create agent" }));
  expect(createAgent).toHaveBeenCalledWith({ name: "Invoice bot", category: "Finance", description: "" });
  await waitFor(() => expect(screen.queryByRole("form", { name: "New agent" })).not.toBeInTheDocument());
});

it("shows the failure text when creating an agent fails", async () => {
  createAgent.mockResolvedValue({ ok: false, failure: { text: "Couldn't create the agent. Try again." } });
  render(<AgentsView agents={[]} />);
  await openNewAgent();
  await userEvent.type(screen.getByLabelText("Name"), "Invoice bot");
  await userEvent.type(screen.getByLabelText("Category"), "Finance");
  await userEvent.click(screen.getByRole("button", { name: "Create agent" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Couldn't create the agent. Try again.");
});

it("sends one request when Create agent is clicked twice", async () => {
  let release!: (v: unknown) => void;
  createAgent.mockReturnValue(new Promise((r) => { release = r; }));
  render(<AgentsView agents={[]} />);
  await openNewAgent();
  await userEvent.type(screen.getByLabelText("Name"), "Invoice bot");
  await userEvent.type(screen.getByLabelText("Category"), "Finance");
  const create = screen.getByRole("button", { name: "Create agent" });
  await userEvent.click(create);
  await userEvent.click(create);
  expect(createAgent).toHaveBeenCalledTimes(1);
  release({ ok: true });
});

it("rejects an empty or invalid manifest before calling the backend", async () => {
  render(<AgentsView agents={[agent()]} />);
  await openNewVersion();
  await userEvent.type(screen.getByLabelText("Version number"), "2.0.0");
  await userEvent.click(screen.getByRole("button", { name: "Create version" }));
  expect(screen.getByText("Paste the manifest JSON.")).toBeInTheDocument();
  await pasteManifest("{not json");
  await userEvent.click(screen.getByRole("button", { name: "Create version" }));
  expect(screen.getByText("That isn't valid JSON.")).toBeInTheDocument();
  expect(createVersion).not.toHaveBeenCalled();
});

it("asks for a version number", async () => {
  render(<AgentsView agents={[agent()]} />);
  await openNewVersion();
  await pasteManifest('{"name":"x"}');
  await userEvent.click(screen.getByRole("button", { name: "Create version" }));
  expect(screen.getByText("Add a version number, for example 1.0.0.")).toBeInTheDocument();
  expect(createVersion).not.toHaveBeenCalled();
});

it("creates a version with the manifest text as pasted", async () => {
  createVersion.mockResolvedValue({ ok: true });
  render(<AgentsView agents={[agent()]} />);
  await openNewVersion();
  await userEvent.type(screen.getByLabelText("Version number"), "2.0.0");
  await pasteManifest('{"name":"x"}');
  await userEvent.click(screen.getByRole("button", { name: "Create version" }));
  expect(createVersion).toHaveBeenCalledWith("a1", { versionNumber: "2.0.0", manifestJson: '{"name":"x"}', changelog: "" });
  await waitFor(() => expect(screen.queryByRole("form", { name: "New version" })).not.toBeInTheDocument());
});

it("shows a manifest failure under the manifest field", async () => {
  createVersion.mockResolvedValue({ ok: false, failure: { text: "Karya couldn't accept this manifest. Check that it has every required block and try again.", field: "manifest" } });
  render(<AgentsView agents={[agent()]} />);
  await openNewVersion();
  await userEvent.type(screen.getByLabelText("Version number"), "2.0.0");
  await pasteManifest('{"name":"x"}');
  await userEvent.click(screen.getByRole("button", { name: "Create version" }));
  expect(await screen.findByText(/couldn't accept this manifest/)).toBeInTheDocument();
  expect(screen.getByLabelText("Manifest JSON")).toHaveAttribute("aria-invalid", "true");
});
