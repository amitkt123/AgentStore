import { render, screen, within } from "@testing-library/react";
import { Declared } from "./Declared";
import { empty, manifest } from "./fixtures";

const card = (name: string) => screen.getByRole("region", { name });

it("lists every declared block", () => {
  render(<Declared manifest={manifest()} />);
  expect(within(card("Scopes")).getByText("bank_statement")).toBeInTheDocument();
  expect(within(card("Scopes")).getByText("Needs approval")).toBeInTheDocument();
  expect(within(card("Allowed hosts")).getByText("api.example.com")).toBeInTheDocument();
  expect(within(card("Resources")).getByText("vendor_master")).toBeInTheDocument();
  expect(within(card("Resources")).getByText("CSV")).toBeInTheDocument();
  expect(within(card("Approval gates")).getByText("post_entries")).toBeInTheDocument();
  expect(within(card("Approval gates")).getByText("60 min")).toBeInTheDocument();
  expect(within(card("Cost envelope")).getByText(/per row/)).toBeInTheDocument();
  expect(within(card("Cost envelope")).getByText("Pay per use")).toBeInTheDocument();
  expect(within(card("Data classes")).getByText("FINANCIAL")).toBeInTheDocument();
  expect(within(card("Runtime")).getByText("python-3.12")).toBeInTheDocument();
  expect(within(card("Runtime")).getByText("main.py")).toBeInTheDocument();
  expect(within(card("LLM access")).getByText("None")).toBeInTheDocument();
});

it.each(["Scopes", "Allowed hosts", "Resources", "Approval gates", "Data classes", "Runtime"])("says Not declared for an absent %s block", (name) => {
  render(<Declared manifest={empty} />);
  expect(within(card(name)).getByText("Not declared")).toBeInTheDocument();
});

it("describes an llm block", () => {
  render(<Declared manifest={manifest({ llm: { mode: "BYOK_PROXY", models: ["claude-x"], maxTokensPerRun: 50000, provider: "ANTHROPIC" } })} />);
  const llm = card("LLM access");
  expect(within(llm).getByText("Your key, through Karya")).toBeInTheDocument();
  expect(within(llm).getByText("claude-x")).toBeInTheDocument();
  expect(within(llm).getByText("50000")).toBeInTheDocument();
  expect(within(llm).getByText("ANTHROPIC")).toBeInTheDocument();
});

it("reads enum values in either case", () => {
  render(<Declared manifest={manifest({ resources: [{ name: "v", format: "csv", schema: null }], llm: { mode: "platform" as never, models: [], maxTokensPerRun: 1, provider: null } })} />);
  expect(within(card("Resources")).getByText("CSV")).toBeInTheDocument();
  expect(within(card("LLM access")).getByText("Karya's models")).toBeInTheDocument();
});
