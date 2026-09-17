import type { StatementLineResponse } from "./types";

export interface AgentTotal {
  agentName: string;
  total: number;
}

export function groupStatementByAgent(lines: StatementLineResponse[]): AgentTotal[] {
  const totals = new Map<string, number>();
  for (const line of lines) {
    const signedAmount = line.type === "REVERSAL" ? -line.amount : line.amount;
    totals.set(line.agentName, (totals.get(line.agentName) ?? 0) + signedAmount);
  }
  return [...totals.entries()].map(([agentName, total]) => ({ agentName, total }));
}
