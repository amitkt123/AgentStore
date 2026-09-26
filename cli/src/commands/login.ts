import { ApiClient } from "../api.js";
import { credentialsPath, DEFAULT_API_URL, normalizeUrl, saveCredentials } from "../config.js";
import type { Context } from "../context.js";
import { CliError } from "../errors.js";

export async function login(ctx: Context, opts: { apiUrl?: string }): Promise<number> {
  const apiUrl = normalizeUrl(opts.apiUrl ?? ctx.env.AGENTSTORE_API_URL ?? DEFAULT_API_URL);
  try {
    new URL(apiUrl);
  } catch {
    throw new CliError(`--api-url "${apiUrl}" is not a valid URL.`);
  }
  const portal = normalizeUrl(ctx.env.AGENTSTORE_PORTAL_URL ?? "http://localhost:3000");
  ctx.out(`Create an API key in the portal: ${portal}/dashboard/api-keys`);

  const apiKey = await ctx.readSecret("Paste your API key:");
  if (!apiKey.startsWith("ask_live_")) {
    throw new CliError("That does not look like an AgentStore API key (they start with ask_live_).");
  }

  // GET /agents/mine is on the key allowlist, so a bad or revoked key fails here instead of at the first push.
  const credentials = { apiKey, apiUrl };
  await new ApiClient(credentials, ctx.fetch).get("/api/v1/agents/mine");

  saveCredentials(ctx, credentials);
  ctx.out(`Logged in. Credentials saved to ${credentialsPath(ctx.home)}.`);
  return 0;
}
