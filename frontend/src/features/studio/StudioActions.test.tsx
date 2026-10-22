import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

const patch = vi.hoisted(() => vi.fn());
const submit = vi.hoisted(() => vi.fn());
vi.mock("@/app/(app)/developer/agents/actions", () => ({
  patchAction: (...a: unknown[]) => patch(...a),
  submitAction: (...a: unknown[]) => submit(...a),
}));
import { Declared } from "./Declared";
import { FeedbackBanner, FeedbackProvider } from "./feedback";
import { manifest } from "./fixtures";
import { PatchForms, SubmitBar } from "./StudioActions";

beforeEach(() => { patch.mockReset(); submit.mockReset(); });

const ui = (children: React.ReactNode) => render(<FeedbackProvider><FeedbackBanner />{children}</FeedbackProvider>);
const form = (name: string) => screen.getByRole("form", { name });

it("adds an allowed host, confirms it and clears the field", async () => {
  patch.mockResolvedValue({ ok: true, message: "Added host api.example.com" });
  ui(<PatchForms agentId="a1" versionId="v1" />);
  const f = form("Add an allowed host");
  await userEvent.type(within(f).getByLabelText("Host"), " api.example.com ");
  await userEvent.click(within(f).getByRole("button", { name: "Add host" }));
  expect(patch).toHaveBeenCalledWith("a1", "v1", { op: "add_allowlist_host", host: "api.example.com" });
  expect(await screen.findByRole("status")).toHaveTextContent("Added host api.example.com");
  expect(within(f).getByLabelText("Host")).toHaveValue("");
});

it("rejects a host with a scheme, a path or spaces without calling the backend", async () => {
  ui(<PatchForms agentId="a1" versionId="v1" />);
  const f = form("Add an allowed host");
  await userEvent.type(within(f).getByLabelText("Host"), "https://api.example.com/x");
  await userEvent.click(within(f).getByRole("button", { name: "Add host" }));
  expect(within(f).getByText("Enter just the host, like api.example.com.")).toBeInTheDocument();
  expect(patch).not.toHaveBeenCalled();
});

it("asks for the missing field in each form", async () => {
  ui(<PatchForms agentId="a1" versionId="v1" />);
  await userEvent.click(within(form("Add a read scope")).getByRole("button", { name: "Add scope" }));
  await userEvent.click(within(form("Add a resource")).getByRole("button", { name: "Add resource" }));
  await userEvent.click(within(form("Add an allowed host")).getByRole("button", { name: "Add host" }));
  expect(screen.getByText("Enter the resource to read, for example bank_statement.")).toBeInTheDocument();
  expect(screen.getByText("Enter a name for the resource.")).toBeInTheDocument();
  expect(screen.getByText("Enter a host, like api.example.com.")).toBeInTheDocument();
  expect(patch).not.toHaveBeenCalled();
});

it("adds a read scope with its approval flag", async () => {
  patch.mockResolvedValue({ ok: true, message: "Added read scope vendor_master" });
  ui(<PatchForms agentId="a1" versionId="v1" />);
  const f = form("Add a read scope");
  await userEvent.type(within(f).getByLabelText("Resource"), "vendor_master");
  await userEvent.type(within(f).getByLabelText("Description"), "Read vendors");
  await userEvent.click(within(f).getByLabelText("Needs approval before it runs"));
  await userEvent.click(within(f).getByRole("button", { name: "Add scope" }));
  expect(patch).toHaveBeenCalledWith("a1", "v1", { op: "add_read_scope", resource: "vendor_master", description: "Read vendors", requiresApproval: true });
});

it("adds a resource with its format in lower case", async () => {
  patch.mockResolvedValue({ ok: true, message: "Added resource vendor_master" });
  ui(<PatchForms agentId="a1" versionId="v1" />);
  const f = form("Add a resource");
  await userEvent.type(within(f).getByLabelText("Name"), "vendor_master");
  await userEvent.selectOptions(within(f).getByLabelText("Format"), "PDF");
  await userEvent.click(within(f).getByRole("button", { name: "Add resource" }));
  expect(patch).toHaveBeenCalledWith("a1", "v1", { op: "add_resource", name: "vendor_master", format: "pdf" });
});

it("shows a patch failure in the banner and keeps what was typed", async () => {
  patch.mockResolvedValue({ ok: false, failure: { text: "A READ scope for 'x' is already declared" } });
  ui(<PatchForms agentId="a1" versionId="v1" />);
  const f = form("Add a read scope");
  await userEvent.type(within(f).getByLabelText("Resource"), "x");
  await userEvent.click(within(f).getByRole("button", { name: "Add scope" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("A READ scope for 'x' is already declared");
  expect(within(f).getByLabelText("Resource")).toHaveValue("x");
});

it("removes a scope from the declared card", async () => {
  patch.mockResolvedValue({ ok: true, message: "Removed read scope bank_statement" });
  ui(<Declared manifest={manifest()} editable={{ agentId: "a1", versionId: "v1" }} />);
  await userEvent.click(screen.getByRole("button", { name: "Remove read scope bank_statement" }));
  expect(patch).toHaveBeenCalledWith("a1", "v1", { op: "remove_scope", type: "READ", resource: "bank_statement" });
  expect(await screen.findByRole("status")).toHaveTextContent("Removed read scope bank_statement");
});

it("offers no Remove buttons when the version is not editable", () => {
  render(<Declared manifest={manifest()} />);
  expect(screen.queryByRole("button", { name: /Remove/ })).not.toBeInTheDocument();
});

it("disables Submit until the version is ready, and says why", () => {
  ui(<SubmitBar agentId="a1" versionId="v1" status="DRAFT" ready={false} />);
  expect(screen.getByRole("button", { name: "Submit for review" })).toBeDisabled();
  expect(screen.getByText("Fix the items above before you submit.")).toBeInTheDocument();
});

it.each([
  ["IN_REVIEW", /in review/],
  ["LIVE", /is live/],
  ["REJECTED", /was rejected/],
] as const)("shows no Submit button for a %s version, only why", (status, note) => {
  ui(<SubmitBar agentId="a1" versionId="v1" status={status} ready />);
  expect(screen.queryByRole("button", { name: "Submit for review" })).toBeNull();
  expect(screen.getByText(note)).toBeInTheDocument();
});

it("submits a ready draft and words the result from the returned status", async () => {
  submit.mockResolvedValue({ ok: true, message: "Submitted and published. This version is live.", status: "LIVE" });
  ui(<SubmitBar agentId="a1" versionId="v1" status="DRAFT" ready />);
  await userEvent.click(screen.getByRole("button", { name: "Submit for review" }));
  expect(submit).toHaveBeenCalledWith("a1", "v1");
  expect(await screen.findByRole("status")).toHaveTextContent("Submitted and published. This version is live.");
});

it("links to Get verified when the org is not verified", async () => {
  submit.mockResolvedValue({ ok: false, failure: { text: "Only verified developers can submit an agent that uses its own LLM key. Get verified first.", verify: true } });
  ui(<SubmitBar agentId="a1" versionId="v1" status="DRAFT" ready />);
  await userEvent.click(screen.getByRole("button", { name: "Submit for review" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Get verified first.");
  expect(screen.getByRole("link", { name: "Get verified" })).toHaveAttribute("href", "/developer/verification");
});

it("sends one request when Submit is clicked twice", async () => {
  let release!: (v: unknown) => void;
  submit.mockReturnValue(new Promise((r) => { release = r; }));
  ui(<SubmitBar agentId="a1" versionId="v1" status="DRAFT" ready />);
  const button = screen.getByRole("button", { name: "Submit for review" });
  await userEvent.click(button);
  await userEvent.click(button);
  expect(submit).toHaveBeenCalledTimes(1);
  release({ ok: true, message: "Submitted.", status: "IN_REVIEW" });
  await waitFor(() => expect(button).toBeEnabled());
});
