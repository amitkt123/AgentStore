import type { Api } from "./api.js";
import { GateRejectedError } from "./errors.js";

export interface Decision { gateId: string; status: string; note?: string; approved: boolean }

export interface GateRequest {
  action: string;
  summary: string;
  payload: Record<string, unknown>;
  affects?: string[];
  raiseOnReject?: boolean;
}

export class Gates {
  constructor(private readonly api: Api, private readonly wait = 25) {}

  async request(req: GateRequest): Promise<Decision> {
    const created = await this.api.createGate({
      action: req.action, summary: req.summary, payload: req.payload, affectsResources: req.affects ?? [],
    });
    const gateId = created.gateId!;
    let view: { status?: string; note?: string } = created;
    while (view.status === "PENDING") view = await this.api.getGate(gateId, this.wait);
    const decision: Decision = { gateId, status: view.status!, note: view.note, approved: view.status === "APPROVED" };
    if (req.raiseOnReject && !decision.approved) throw new GateRejectedError(gateId, decision.status, decision.note);
    return decision;
  }
}
