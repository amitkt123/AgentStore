import type { Api } from "./api.js";

export interface UsageResult { charged: number; totalCharged: number; costCapRemaining: number | null }

export class Usage {
  constructor(private readonly api: Api) {}

  async report(quantity: number, unit: string, outcomeRef?: string): Promise<UsageResult> {
    const r = await this.api.usage(quantity, unit, outcomeRef);
    return { charged: r.charged ?? 0, totalCharged: r.totalCharged ?? 0, costCapRemaining: r.costCapRemaining ?? null };
  }
}
