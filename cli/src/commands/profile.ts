import { ApiClient } from "../api.js";
import { requireCredentials } from "../config.js";
import type { Context } from "../context.js";
import { readProject } from "../project.js";
import { writeProfileFile, type ProfileYaml } from "../profiles.js";

interface ProfileSummary {
  name: string;
  status: string;
  trialEnabled: boolean;
  source: string;
}

interface ProfileBinding {
  resource: string;
  datasetId: string;
  displayName: string | null;
}

interface ProfileDetail {
  name: string;
  description: string | null;
  input: Record<string, unknown>;
  gatePolicy: { default: string; actions: Record<string, string> };
  llmMode: string | null;
  spendCap: number | null;
  bindings: ProfileBinding[];
}

export async function profileList(ctx: Context): Promise<number> {
  const project = readProject(ctx.cwd);
  const api = new ApiClient(requireCredentials(ctx), ctx.fetch);
  const list = await api.get<ProfileSummary[]>(`/api/v1/agents/${project.agentId}/sandbox-profiles`);
  if (list.length === 0) {
    ctx.out("No sandbox profiles.");
    return 0;
  }
  for (const p of list) {
    ctx.out(`${p.name}\t${p.status}\ttrial=${p.trialEnabled}\tsource=${p.source}`);
  }
  return 0;
}

export async function profilePull(ctx: Context): Promise<number> {
  const project = readProject(ctx.cwd);
  const api = new ApiClient(requireCredentials(ctx), ctx.fetch);
  const list = await api.get<ProfileDetail[]>(`/api/v1/agents/${project.agentId}/sandbox-profiles`);
  for (const p of list) {
    writeProfileFile(ctx.cwd, toYaml(p));
    ctx.out(`Wrote fixtures/profiles/${p.name}.yaml`);
  }
  return 0;
}

function toYaml(p: ProfileDetail): ProfileYaml {
  return {
    name: p.name,
    description: p.description ?? undefined,
    input: p.input,
    gatePolicy: p.gatePolicy,
    llmMode: p.llmMode ?? undefined,
    spendCap: p.spendCap ?? undefined,
    bindings: p.bindings
      .filter((b) => b.displayName !== null)
      .map((b) => ({ resource: b.resource, fixture: b.displayName as string })),
  };
}
