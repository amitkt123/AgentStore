import { describe, expect, it } from "vitest";
import { groupStatementByAgent } from "./statement";
import type { StatementLineResponse } from "./types";

describe("groupStatementByAgent", () => {
  it("sums charges per agent and subtracts reversals", () => {
    const lines: StatementLineResponse[] = [
      { runId: "1", agentName: "GSTR-2B Reconciler", amount: 16352, type: "CHARGE", createdAt: "" },
      { runId: "2", agentName: "GSTR-2B Reconciler", amount: 340, type: "REVERSAL", createdAt: "" },
      { runId: "3", agentName: "Bank Statement Matcher", amount: 2400, type: "CHARGE", createdAt: "" },
    ];
    expect(groupStatementByAgent(lines)).toEqual([
      { agentName: "GSTR-2B Reconciler", total: 16012 },
      { agentName: "Bank Statement Matcher", total: 2400 },
    ]);
  });

  it("returns an empty list for no lines", () => {
    expect(groupStatementByAgent([])).toEqual([]);
  });
});
