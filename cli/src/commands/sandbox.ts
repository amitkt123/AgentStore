import { ApiClient } from "../api.js";
import { requireCredentials } from "../config.js";
import type { Context } from "../context.js";
import { CliError } from "../errors.js";
import { readProject } from "../project.js";

export const POLL_INTERVAL_MS = 2000;
const FOLLOW_LIMIT_MS = 30 * 60 * 1000;
const TERMINAL = new Set(["COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT", "EXPIRED"]);

interface Step {
  kind: string;
  source: string;
  detail: string;
  createdAt: string;
}

interface PendingGate {
  id: string;
  action: string;
  summary: string;
}

interface RunDetail {
  id: string;
  displayId: string;
  status: string;
  simulatedCharge: number;
  requestedCharge: number;
  steps: Step[];
  pendingGate: PendingGate | null;
}

interface MyAgentVersion {
  id: string;
  versionNumber: string;
  status: string;
}

interface MyAgent {
  agentId: string;
  versions: MyAgentVersion[];
}

interface SandboxProfileSummary {
  id: string;
  name: string;
}

export interface SandboxRunOptions {
  version?: string;
  profile?: string;
  follow: boolean;
}

/** Numeric-segment compare, so "1.10.0" sorts after "1.9.0". */
function compareVersions(a: string, b: string): number {
  const partsA = a.split(".").map(Number);
  const partsB = b.split(".").map(Number);
  for (let i = 0; i < Math.max(partsA.length, partsB.length); i++) {
    const diff = (partsA[i] ?? 0) - (partsB[i] ?? 0);
    if (diff !== 0) {
      return diff;
    }
  }
  return 0;
}

function formatStep(step: Step): string {
  let text = step.detail;
  try {
    const parsed = JSON.parse(step.detail) as Record<string, unknown>;
    const said = parsed.summary ?? parsed.message;
    text = typeof said === "string" ? said : JSON.stringify(parsed);
  } catch {
    // not JSON: keep the raw text
  }
  return `${step.kind}: ${text}`;
}

export async function sandboxRun(ctx: Context, opts: SandboxRunOptions): Promise<number> {
  const project = readProject(ctx.cwd);
  const api = new ApiClient(requireCredentials(ctx), ctx.fetch);

  const mine = await api.get<MyAgent[]>("/api/v1/agents/mine");
  const agent = mine.find((a) => a.agentId === project.agentId);
  if (!agent || agent.versions.length === 0) {
    throw new CliError("This agent has no versions yet. Run `agentstore push --wait` first.");
  }
  const sorted = [...agent.versions].sort((a, b) => compareVersions(b.versionNumber, a.versionNumber));
  const version = opts.version ? agent.versions.find((v) => v.versionNumber === opts.version) : sorted[0];
  if (!version) {
    throw new CliError(`No version ${opts.version}. Available: ${sorted.map((v) => v.versionNumber).join(", ")}.`);
  }

  let body: Record<string, unknown> = { agentVersionId: version.id, inlineProfile: {} };
  if (opts.profile) {
    const profiles = await api.get<SandboxProfileSummary[]>(`/api/v1/agents/${project.agentId}/sandbox-profiles`);
    const profile = profiles.find((p) => p.name === opts.profile);
    if (!profile) {
      throw new CliError(`No sandbox profile "${opts.profile}". Available: ${profiles.map((p) => p.name).join(", ") || "none"}.`);
    }
    body = { agentVersionId: version.id, profileId: profile.id };
  }

  const started = await api.post<{ id: string; displayId: string; status: string }>("/api/v1/sandbox/runs", body);
  ctx.out(`Started sandbox run ${started.displayId} (${started.status}) on version ${version.versionNumber}.`);
  return opts.follow ? await follow(ctx, api, started.id) : 0;
}

async function follow(ctx: Context, api: ApiClient, runId: string): Promise<number> {
  const deadline = ctx.now() + FOLLOW_LIMIT_MS;
  let printed = 0;
  let askedGate: string | null = null;
  for (;;) {
    const run = await api.get<RunDetail>(`/api/v1/sandbox/runs/${runId}`);
    for (const step of run.steps.slice(printed)) {
      ctx.out(formatStep(step));
    }
    printed = run.steps.length;

    if (run.pendingGate && run.pendingGate.id !== askedGate) {
      askedGate = run.pendingGate.id;
      const answer = (await ctx.ask(`Approve "${run.pendingGate.action}" (${run.pendingGate.summary})? [y/N]`)).trim().toLowerCase();
      await api.post(`/api/v1/sandbox/runs/${runId}/gates/${run.pendingGate.id}/decision`, {
        approve: answer === "y" || answer === "yes",
      });
      continue;   // re-poll at once so the decision's effect shows up without a sleep
    }
    if (TERMINAL.has(run.status)) {
      ctx.out(`Run ${run.displayId} ${run.status} (simulated charge ${run.simulatedCharge}, would have asked ${run.requestedCharge}).`);
      return run.status === "COMPLETED" ? 0 : 1;
    }
    if (ctx.now() >= deadline) {
      throw new CliError(`Stopped following after 30 minutes; the run is still ${run.status}. Use \`agentstore sandbox logs ${runId}\`.`);
    }
    await ctx.sleep(POLL_INTERVAL_MS);
  }
}

export async function sandboxLogs(ctx: Context, runId: string): Promise<number> {
  const api = new ApiClient(requireCredentials(ctx), ctx.fetch);
  const run = await api.get<RunDetail>(`/api/v1/sandbox/runs/${encodeURIComponent(runId)}`);
  for (const step of run.steps) {
    ctx.out(formatStep(step));
  }
  ctx.out(`Run ${run.displayId} is ${run.status}.`);
  return 0;
}
