# WS5c — Developer API keys and CLI — Design

> **Status:** design approved section by section on 2026-09-26. Supplements §6 of the WS5 spec (`2026-09-25-ws5-sandbox-runner-build-pipeline-cli-design.md`), which stays the source for the runner, build pipeline and egress. Where the two differ, this document wins; §6 of the WS5 spec gets a pointer to it.

## 1. Intent and scope

**Goal.** A developer goes from an empty directory to a built agent version from the terminal: `agentstore login`, `init`, `validate`, `push --wait`. The CLI authenticates with an org-scoped API key rather than a browser JWT.

**In scope**
- Backend: `developer_api_key` table, key management endpoints, `ApiKeyAuthFilter`, the allowlist in `SecurityConfig`.
- CLI in `cli/` (TypeScript), run locally: `login`, `init`, `validate`, `push [--wait]`.
- Frontend: an API-keys page, and `SubmissionWizard` loses its template-ID field.

**Out of scope**
- npm publishing and standalone binaries (a later, small plan once the CLI's shape has settled).
- `dev`, `test`, `sandbox`, `logs` and `submit` commands (WS6, WS8).
- Key expiry, scopes finer than "developer endpoints", and per-key rate limits.

**Success criteria**
1. With the local stack running, a developer creates a key in the portal, runs `agentstore login`, `init`, `push --wait` in a fresh directory, and sees the build finish with exit code 0 (or 1 with `failureCode` and reason).
2. A key cannot reach wallet, buyer, admin, identity, key-management or submit endpoints.
3. A revoked key, or a key whose creator left the org, is rejected on the next request.

## 2. API keys (backend)

**Table `developer_api_key`** (migration **V24**, the next free number after V23): `id`, `org_id`, `created_by_user_id`, `name`, `prefix` (first 12 characters, for display), `key_hash` (unique, SHA-256; the keys are high-entropy so a slow hash isn't needed), `created_at`, `last_used_at`, `revoked_at`. The unique index on `key_hash` serves the per-request lookup.

**Endpoints** (JWT only; a key cannot manage keys):

| Method | Path | Behaviour |
|---|---|---|
| POST | `/api/v1/developer/api-keys {name}` | Returns `ask_live_<32 random bytes, base62>` **once**; only the hash is stored. |
| GET | `/api/v1/developer/api-keys` | The org's keys: `id`, `name`, `prefix`, `createdAt`, `lastUsedAt`, `revokedAt`. Never the hash. |
| DELETE | `/api/v1/developer/api-keys/{id}` | Sets `revoked_at`. Idempotent. 404 for another org's key. |

All three need `hasRole('DEVELOPER')`. Any developer of the org may list and revoke the org's keys, as with member management (`OrgRoles.canManageMembers`).

**`ApiKeyAuthFilter`** sits next to `JwtAuthFilter` and handles `Authorization: Bearer ask_live_…`:
1. Hash the token, look up the row. Unknown or revoked → leave unauthenticated (401).
2. Check the creating user is still an `org_membership` member of the key's org with the developer role. A removed member's keys stop working at once.
3. Set the same `JwtService.Claims(userId, orgId, DEVELOPER)` principal as a JWT would, with authorities `ROLE_DEVELOPER` and `AUTH_API_KEY`. **The role is always DEVELOPER, never the creator's role**, so an admin who mints a key does not give the key admin reach. No existing controller changes.
4. Update `last_used_at` at most once a minute per key.

**Confinement (`SecurityConfig`).** Requests carrying `AUTH_API_KEY` may match only this allowlist; everything else returns 403. New endpoints are therefore denied to keys by default, and adding one to the list is a deliberate edit to one file.

- `POST /api/v1/agents`, `GET /api/v1/agents/mine`
- `/api/v1/agents/{id}/versions/**`, **except** `POST …/versions/{versionId}/submit` (submitting for review is a human decision; WS8)
- `/api/v1/agents/{id}/bundles/**`
- `/api/v1/agents/{id}/builds/**`

Existing ownership checks still apply, so a key from org A cannot touch org B's agent.

## 3. CLI (`cli/`)

**Shape.** TypeScript, ESM, Node ≥ 22, built to `dist/`, bin `agentstore`, run locally via `npm link`. Dependencies stay small: `ajv`, a zip library, an ignore-file matcher, Vitest. Arguments are parsed with Node's built-in `util.parseArgs`.

**Config and credentials**
- `~/.config/agentstore/credentials`: JSON `{apiKey, apiUrl}`, mode 0600 in a 0700 directory. A looser mode prints a warning.
- `AGENTSTORE_API_KEY` and `AGENTSTORE_API_URL` override the file. The API URL defaults to `http://localhost:8080`.
- The CLI never prints the key or the signed upload URL.

**Commands**

- **`login [--api-url URL]`.** Prints the portal's API-keys page URL, reads the pasted key without echo, and verifies it with `GET /agents/mine` (on the allowlist, so a bad key fails immediately). Credentials are saved only if verification passes.
- **`init [--python|--node] [--name --category --description]`.** (`--typescript` is accepted as an alias of `--node`: the build has no TypeScript step and the backend accepts only `.js`, `.mjs` and `.cjs` entrypoints, so the Node scaffold is plain ESM JavaScript on `node-22`.)
  1. Check the target directory is empty and writable (refuse otherwise, and refuse if `agentstore.yaml` exists).
  2. Prompt for anything not given by flag, then `POST /agents`.
  3. Write the scaffold: `agentstore.yaml` (holds the `agentId`; the API URL lives in the credentials, not here), `manifest.json`, `agent/`, the dependency file, `fixtures/`, `evals/`, `schemas/`, `.agentstoreignore`. The plan derives the minimal valid manifest from `manifest-schema.json`.
  4. If the write fails after the agent was created, print the `agentId` so the developer can recover instead of re-running `init` and creating a second agent.
- **`validate`.** Checks `manifest.json` against a bundled copy of `manifest-schema.json`; applies the same rules as `BundleManifestRules` (runtime is `python-3.12` or `node-22`, entrypoint is a relative path that exists and matches the runtime); checks the dependency file exists; checks the packed limits (100 MB bundle, 20,000 entries, 256 KiB manifest). A test fails if the bundled schema drifts from `backend/src/main/resources/manifest/manifest-schema.json`. This is fast feedback only; the backend's check is final.
- **`push [--wait] [--timeout SECONDS]`.** Validate → pack a zip (applying `.agentstoreignore`, `manifest.json` at the root) → SHA-256 → `POST …/bundles/uploads` → `PUT` to the signed URL → `POST …/bundles/{id}/finalize` → print version number and build id.
  - With `--wait`: poll `GET …/builds/{buildId}` every 2 s and tail the log by re-fetching the pre-signed `logUrl` and printing only the new bytes (logs are small and bounded).
  - **Exit codes:** 0 on `SUCCEEDED` (or after finalize without `--wait`); 1 when the project or build is bad (`validate` problems, or a `FAILED` build, printing `failureCode` and `failureReason`); 2 when the tool could not do its job (usage error, network error, rejected upload, wait timeout of 15 minutes by default).

**Error handling.** 401 → "API key rejected or revoked; run `agentstore login`". 403 → the backend message. A finalize rejection (`422`) prints the backend's reason verbatim.

## 4. Frontend

- **API keys page** `/dashboard/api-keys` and an "API keys" link in the developer layout. A server component fetches through `lib/backend.ts`; server actions create and revoke. A table shows name, prefix, created, last used, and status with a Revoke button. Creating a key shows the full value once in a copy box with a "you won't see this again" warning; it lives in component state only, never in a URL or `localStorage`.
- **`SubmissionWizard` (option 1, decided).** The wizard keeps creating a version, minus the template-ID field, so the version is an image-less DRAFT with the typed manifest and changelog. Its final step shows the `agentstore push` command for the agent, and says that **the `manifest.json` inside the pushed bundle is authoritative: `finalize` matches the draft by version number and replaces its manifest**. Submit-for-review stays blocked until a build has succeeded (already enforced in WS5b).
- `POST /agents/{id}/versions` drops `imageRef` from `CreateAgentVersionRequest`, since nothing can legitimately supply a hand-built image any more (`finalize` already clears it). The entity's `imageRef` stays; a successful build sets it. The plan lists the tests that construct requests with `imageRef`.
- **Submitting a built draft (added while planning).** Nothing in the UI can submit a DRAFT today (`BuildPanel` says "You can submit this version" but has no button), and keys cannot submit. Once the wizard stops submitting, that would leave no route to review. So the version status page (`ReviewStatusView`) gets: for a DRAFT with no build yet, a "Push your code" panel with the `agentstore push` command and the agent id; for a DRAFT whose build `SUCCEEDED`, a "Submit for review" button (`submitVersionAction`, then refresh). The dashboard links a DRAFT to this page instead of the wizard.
- `frontend/e2e/seed.ts` created its version with `imageRef`; it now sets `image_ref` directly in the database before submitting.
- The build status and log on the version page shipped in WS5b and are not repeated here.

## 5. Testing

- **Backend** (Testcontainers Postgres, existing conventions): key shown once and stored only as a hash; unknown and revoked keys → 401; a removed member's key → 401; an admin-created key still has only DEVELOPER reach; 403 for wallet, admin, buyer, key-management and submit; 200 across the allowlist; another org's agent → 403/404; `last_used_at` throttling; migration V24.
- **CLI** (Vitest, mock HTTP server): `.agentstoreignore` when packing, the size limits, `validate` rules and schema-drift check, `login` verify-then-save, credentials file mode 0600 and env override, `init` refusing a non-empty directory and printing the `agentId` when the write fails, `push` request order and `--wait` exit codes (0, 1, 2 for timeout), log tailing that prints only new bytes.
- **Frontend** (Vitest + Testing Library): create shows the key once, revoke, the empty state; the wizard has no template field and shows the push command.
- **Acceptance** (manual, needs the local stack and the real runner): success criterion 1 end to end.

## 6. Plan

One plan, `docs/superpowers/plans/2026-09-26-ws5c-api-keys-cli.md`, in this order so each step is testable on its own: (1) migration, key service and endpoints; (2) `ApiKeyAuthFilter` and the `SecurityConfig` allowlist; (3) drop `imageRef` from the version request and fix its tests; (4) CLI skeleton, config, `login`; (5) `validate`; (6) `init`; (7) `push` and `--wait`; (8) API-keys page; (9) wizard change; (10) docs (ontology, README, the WS5 spec pointer).

## 7. Decisions recorded while designing (2026-09-26)

- The CLI learns its agent from `init`, which calls `POST /agents` and writes `agentId` into `agentstore.yaml` (chosen over lazy creation in `push`, which risks duplicates, and over hand-pasted ids).
- Key confinement is a path allowlist in `SecurityConfig` (chosen over a per-method annotation and over a denylist, which fails open).
- Key role is fixed at DEVELOPER; `submit` is not reachable by keys.
- The wizard stays, without the template field (chosen over retiring it).
- Distribution is local-only for now.
