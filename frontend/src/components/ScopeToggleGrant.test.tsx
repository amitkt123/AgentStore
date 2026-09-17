import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ScopeToggleGrant } from "./ScopeToggleGrant";
import type { Scope } from "@/lib/types";

const scopes: Scope[] = [
  { type: "READ", resource: "bank_statement", description: "Read uploaded bank statement rows" },
  { type: "WRITE", resource: "journal_draft", description: "Propose draft journal entries" },
];

describe("ScopeToggleGrant", () => {
  it("starts with every declared scope granted", () => {
    render(<ScopeToggleGrant scopes={scopes} onChange={vi.fn()} />);
    expect(screen.getByRole("switch", { name: /bank_statement/i })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("switch", { name: /journal_draft/i })).toHaveAttribute("aria-checked", "true");
  });

  it("never reports a resource outside the declared manifest scopes", async () => {
    const onChange = vi.fn();
    render(<ScopeToggleGrant scopes={scopes} onChange={onChange} />);
    await userEvent.click(screen.getByRole("switch", { name: /journal_draft/i }));
    const lastCall = onChange.mock.calls.at(-1)![0] as string[];
    expect(lastCall).toEqual(["bank_statement"]);
    expect(lastCall.every((r) => scopes.some((s) => s.resource === r))).toBe(true);
  });
});
