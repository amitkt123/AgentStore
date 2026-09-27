# agentstore CLI

Push agent code to the AgentStore developer API. Local-only for now: no npm package and no standalone binaries yet.

## Install (from this repository)

```bash
cd cli
npm install
npm run build
npm link          # puts `agentstore` on your PATH
```

Requires Node 22 or newer.

## Use

```bash
agentstore login                       # paste an API key from the portal's API keys page
mkdir my-agent && cd my-agent
agentstore init --python               # creates the agent, then scaffolds the project (or --node)
agentstore validate                    # the same checks the backend runs, offline
agentstore push --wait                 # upload, build, follow the log; exit 0 on success, 1 on a failed build
agentstore sandbox run --follow        # run the latest built version in the sandbox: no buyer, no money moving
agentstore sandbox logs RUN_ID         # show what a past sandbox run did
```

`sandbox run` picks the highest built version unless `--version` names one, and a one-off (inline) profile unless
`--profile NAME` names a saved one. `--follow` prints each step as it happens, prompts in the terminal for a `MANUAL`
gate decision, and exits 0 for a `COMPLETED` run or 1 otherwise. A quota refusal (`SANDBOX_QUOTA_EXCEEDED`, HTTP 429)
prints as any other API error, exit 2.

| Exit code | Meaning |
|---|---|
| 0 | Done (`push --wait`: the build succeeded) |
| 1 | The project is not valid, or the build failed (the failure code and reason are printed) |
| 2 | The tool could not do its job: bad usage, the API is unreachable, the upload was rejected, or `--wait` timed out (`--timeout SECONDS`, default 900) |

Credentials live in `~/.config/agentstore/credentials` (mode 0600). `AGENTSTORE_API_KEY` and `AGENTSTORE_API_URL` override the file; the API URL defaults to `http://localhost:8080`. `AGENTSTORE_PORTAL_URL` (default `http://localhost:3000`) is only used to print where to create a key.

Files listed in `.agentstoreignore` (gitignore syntax) are left out of the bundle. `.git/` and the root `agentstore.yaml` are always left out. Submitting a built version for review is done in the portal, on the version's page.

## Develop

```bash
npm test             # Vitest
npm run typecheck
npm run sync-schema  # re-copies backend/src/main/resources/manifest/manifest-schema.json (a test fails if it drifts)
```
