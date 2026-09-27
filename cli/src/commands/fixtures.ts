import { existsSync, readFileSync } from "node:fs";
import { basename } from "node:path";
import { ApiClient } from "../api.js";
import { requireCredentials } from "../config.js";
import type { Context } from "../context.js";
import { CliError } from "../errors.js";
import { readProject } from "../project.js";

export interface FixturesPushOptions {
  file: string;
  displayName?: string;
  sourceLabel?: string;
}

interface PiiScanSummary {
  status: string;
  findingCount?: number;
}

interface FixtureResponse {
  id: string;
  displayName: string;
  format: string;
  rowCount: number | null;
  piiScan: PiiScanSummary | null;
}

export async function fixturesPush(ctx: Context, opts: FixturesPushOptions): Promise<number> {
  readProject(ctx.cwd);
  const credentials = requireCredentials(ctx);
  if (!existsSync(opts.file)) {
    throw new CliError(`No such file: ${opts.file}`);
  }
  const bytes = readFileSync(opts.file);
  const form = new FormData();
  form.append("file", new Blob([bytes]), basename(opts.file));
  if (opts.displayName) {
    form.append("displayName", opts.displayName);
  }
  if (opts.sourceLabel) {
    form.append("sourceLabel", opts.sourceLabel);
  }

  let res: Response;
  try {
    res = await ctx.fetch(`${credentials.apiUrl}/api/v1/developer/fixtures`, {
      method: "POST",
      headers: { Authorization: `Bearer ${credentials.apiKey}` },
      body: form,
    });
  } catch (e) {
    throw new CliError(`Could not reach ${credentials.apiUrl}: ${(e as Error).message}`);
  }
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new CliError(`Request failed (HTTP ${res.status})${text ? `: ${text.slice(0, 300)}` : ""}`);
  }
  const fixture = (await res.json()) as FixtureResponse;
  const rows = fixture.rowCount !== null ? `, ${fixture.rowCount} rows` : "";
  ctx.out(`Uploaded fixture "${fixture.displayName}" (${fixture.format}${rows}).`);
  if (fixture.piiScan && fixture.piiScan.status === "FOUND") {
    ctx.out(`Warning: PII scan found ${fixture.piiScan.findingCount ?? "some"} possible finding(s) in this fixture; it cannot back a trial profile until it is cleaned up.`);
  }
  return 0;
}

export async function fixturesList(ctx: Context): Promise<number> {
  readProject(ctx.cwd);
  const api = new ApiClient(requireCredentials(ctx), ctx.fetch);
  const list = await api.get<FixtureResponse[]>("/api/v1/developer/fixtures");
  if (list.length === 0) {
    ctx.out("No fixtures.");
    return 0;
  }
  for (const f of list) {
    const pii = f.piiScan ? f.piiScan.status.toLowerCase() : "unscanned";
    ctx.out(`${f.displayName}\t${f.format}\tpii=${pii}`);
  }
  return 0;
}
