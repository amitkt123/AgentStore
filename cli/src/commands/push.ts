import { ApiClient } from "../api.js";
import { requireCredentials } from "../config.js";
import type { Context } from "../context.js";
import { CliError } from "../errors.js";
import { readProfileFiles } from "../profiles.js";
import { readProject } from "../project.js";
import { validateProject } from "../validate.js";

export const POLL_INTERVAL_MS = 2000;
export const DEFAULT_TIMEOUT_SECONDS = 900;

export interface PushOptions {
  wait: boolean;
  timeoutSeconds: number;
}

interface UploadResponse {
  bundleId: string;
  uploadUrl: string;
}

interface FinalizeResponse {
  versionNumber: string;
  buildId: string;
  buildStatus: string;
}

interface BuildResponse {
  status: "QUEUED" | "BUILDING" | "SUCCEEDED" | "FAILED";
  failureCode: string | null;
  failureReason: string | null;
  logUrl: string | null;
}

interface SyncResult {
  name: string;
  action: string;
  unresolvedFixtures: string[];
}

export async function push(ctx: Context, opts: PushOptions): Promise<number> {
  const project = readProject(ctx.cwd);
  const credentials = requireCredentials(ctx);

  const { problems, bundle } = validateProject(ctx.cwd);
  if (!bundle) {
    ctx.err(`Not pushing: ${problems.length} problem${problems.length === 1 ? "" : "s"} in the project:`);
    for (const problem of problems) {
      ctx.err(`  - ${problem}`);
    }
    return 1;
  }

  const api = new ApiClient(credentials, ctx.fetch);
  const base = `/api/v1/agents/${project.agentId}`;
  const upload = await api.post<UploadResponse>(`${base}/bundles/uploads`, { sha256: bundle.sha256, sizeBytes: bundle.bytes.length });
  await api.putBytes(upload.uploadUrl, bundle.bytes);
  const finalized = await api.post<FinalizeResponse>(`${base}/bundles/${upload.bundleId}/finalize`, { sha256: bundle.sha256 });
  ctx.out(`Pushed version ${finalized.versionNumber} (build ${finalized.buildId}, ${finalized.buildStatus}).`);

  // The version is already pushed at this point; a sync problem (a bad request, an unreachable server, a
  // malformed fixtures/profiles/*.yaml file) is reported as a warning, never as a reason to fail the push.
  try {
    await syncProfiles(ctx, api, base);
  } catch (e) {
    ctx.err(`Profile sync failed: ${(e as Error).message}`);
  }

  if (!opts.wait) {
    return 0;
  }
  return await waitForBuild(ctx, api, `${base}/builds/${finalized.buildId}`, opts.timeoutSeconds);
}

async function syncProfiles(ctx: Context, api: ApiClient, base: string): Promise<void> {
  const entries = readProfileFiles(ctx.cwd);
  if (entries.length === 0) {
    return;
  }
  const results = await api.post<SyncResult[]>(`${base}/sandbox-profiles/sync`, entries);
  for (const result of results) {
    ctx.out(`Profile "${result.name}": ${result.action}`);
    for (const fixture of result.unresolvedFixtures) {
      ctx.err(`  Warning: fixture "${fixture}" not found for profile "${result.name}"; that binding was skipped.`);
    }
  }
}

/**
 * Polls the build and prints the log as it grows. The log is a pre-signed object, so each poll re-fetches it and
 * prints only what was not printed before. A half-written last line is held back until it is complete, and a log
 * that gets shorter (a re-queued build rewrites it) is printed again from the top.
 */
async function waitForBuild(ctx: Context, api: ApiClient, buildPath: string, timeoutSeconds: number): Promise<number> {
  const deadline = ctx.now() + timeoutSeconds * 1000;
  let printed = 0;
  for (;;) {
    const build = await api.get<BuildResponse>(buildPath);
    const finished = build.status === "SUCCEEDED" || build.status === "FAILED";

    const log = build.logUrl ? await api.getText(build.logUrl) : null;
    if (log !== null) {
      if (log.length < printed) {
        printed = 0;
      }
      const upTo = finished ? log.length : log.lastIndexOf("\n") + 1;
      if (upTo > printed) {
        for (const line of log.slice(printed, upTo).replace(/\n$/, "").split("\n")) {
          ctx.out(line);
        }
        printed = upTo;
      }
    }

    if (build.status === "SUCCEEDED") {
      ctx.out("Build succeeded.");
      return 0;
    }
    if (build.status === "FAILED") {
      ctx.err(`Build failed: ${build.failureCode ?? "UNKNOWN"}${build.failureReason ? ` - ${build.failureReason}` : ""}`);
      return 1;
    }
    if (ctx.now() >= deadline) {
      throw new CliError(`Timed out after ${timeoutSeconds}s waiting for the build; it is still ${build.status}. Check the version page in the portal.`);
    }
    await ctx.sleep(POLL_INTERVAL_MS);
  }
}
