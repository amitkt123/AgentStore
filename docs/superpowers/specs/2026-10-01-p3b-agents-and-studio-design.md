# P3b: My agents and Studio — Design

> **Status:** design approved by the user in conversation on 2026-10-01. Second slice of P3 (developer area) from `2026-09-29-fe-rebuild-p0-p1-design.md` §9, after P3a. Frontend only; every endpoint used is already on master.

## 1. Outcome

A developer can see their agents, create an agent and a version, open a version's Studio, see what it declares and whether it is ready to submit, fix the manifest with one-click patches, and submit it for review.

**Aesthetics** come only from `fe-mockups/latest/` and the P3a components and tokens (`Stepper`, `Segmented`, `KeyValueRow`, `tokens.css`).

**Out of scope:** sandbox runs, quota, test files and profiles, the "what it did" half of the Studio and the flow-canvas treatment (all need run endpoints that are not on master, so they wait for P3c); earnings (needs WS10); the admin review queue (P4). The Studio shows a short note that observed behaviour appears once sandbox runs exist.

## 2. Routes and shell

Inside `app/(app)/developer/`, using P3a's role gating:

- `agents` — the list.
- `agents/[agentId]/versions/[versionId]` — the Studio.
- The developer rail (`developerRailItems()` in `ui/Rail.tsx`) gains **Agents**, placed before Get verified. Post-login landing stays `/developer/verification`.
- Ownership is enforced by the backend. A 403 or 404 from a Studio fetch renders the not-found page.

## 3. Data layer

New `lib/api/agents.ts`, in the style of `lib/api/apikeys.ts`:

| Function | Endpoint |
|---|---|
| `listMyAgents` | `GET /api/v1/agents/mine` → `MyAgent[]` |
| `createAgent` | `POST /api/v1/agents {name, category, description?}` |
| `createVersion` | `POST /api/v1/agents/{a}/versions {versionNumber, manifestJson, changelog?}` |
| `getVersion` | `GET /api/v1/agents/{a}/versions/{v}` → `VersionDetail` |
| `getReadiness` | `GET /api/v1/agents/{a}/versions/{v}/readiness` → `Readiness` |
| `patchManifest` | `POST /api/v1/agents/{a}/versions/{v}/manifest/patches` → `ManifestPatchResult` |
| `submitVersion` | `POST /api/v1/agents/{a}/versions/{v}/submit` → `AgentVersion` |

Types (in `lib/api/types.ts`, mirroring the backend records exactly):

- `VersionStatus = "DRAFT" | "IN_REVIEW" | "LIVE" | "REJECTED"`
- `MyAgent {agentId, agentName, category, versions: {id, versionNumber, status}[]}`
- `VersionDetail {id, agentId, agentName, versionNumber, status, manifest, build | null}`
- `Manifest {name, version, scopes: Scope[], networkAllowlist, costEnvelope, approvalGates, dataClasses, runtime | null, entrypoint | null, resources | null, llm | null}`; `Scope {type, resource, description, requiresApproval}`; `ResourceDecl {name, format, schema | null}`; `CostEnvelope {currency, maxPerRun, pricingUnit, unitPrice, unitLabel}`; `ApprovalGateDecl {trigger, description, timeoutMinutes, preview | null}`; `LlmDecl {mode, models, maxTokensPerRun, provider | null}`
- `BuildSummary {buildId, status: QUEUED | BUILDING | SUCCEEDED | FAILED, failureCode, failureReason, logUrl}`
- `Readiness {ready, blockers: {code, message, runId | null}[]}`, `code` one of `BUILD_NOT_READY | UNDECLARED_ACCESS | COST_ENVELOPE_EXCEEDED`
- `ManifestPatchRequest {op, resource?, description?, requiresApproval?, name?, format?, schema?, host?, type?}` with `op` one of `add_read_scope | add_resource | add_allowlist_host | remove_scope`; `ManifestPatchResult {manifestRevision, diff: {addedScopes, removedScopes, addedHosts}}`

Exact enum values for `ScopeType`, `DataFormat`, `PricingUnit` and `LlmMode` are read from the backend enums when the types are written, and a test pins them.

Pages are server components. Mutations are server actions in `actions.ts` (as in P3a): they map `ApiError` to a field or banner error, then `revalidatePath`.

## 4. My agents (`/developer/agents`)

- One row per agent: name, category, and its versions as chips (number + status). Each chip links to that version's Studio.
- **New agent** form: name, category, description (optional). Success redirects to the list with the new agent shown.
- **New version** form per agent: version number, changelog (optional), manifest JSON (textarea). A 400 shows the validator's messages under the manifest field.
- Empty state when there are no agents.

## 5. Studio (`/developer/agents/[agentId]/versions/[versionId]`)

Fetches `getVersion` and `getReadiness` together. Layout:

- **Header:** agent name, category, version number, status chip.
- **Readiness banner:** "Ready to submit", or one entry per blocker. `BUILD_NOT_READY` shows the build status and, if `FAILED`, the failure reason and log link. `UNDECLARED_ACCESS` shows the message and, when `runId` is set, the run reference as text (no link until runs have a screen). `COST_ENVELOPE_EXCEEDED` shows the message.
- **What you declared:** cards for scopes (type, resource, description, approval-required), allowlisted hosts, resources (name, format), approval gates (trigger, description, timeout), cost envelope, data classes, runtime and entrypoint (bundles only), and LLM access or "none". Absent blocks render "Not declared".
- **Behaviour note:** one line saying observed behaviour will appear once sandbox runs are available.
- **Actions:** Submit for review.

## 6. Patches and submit

- Patching and submit are offered only when `status` is `DRAFT`. Other statuses are read-only.
- Each scope row has Remove (`remove_scope`). Inline forms: Add read scope (resource, description, requires approval), Add resource (name, format), Add allowed host. A successful patch shows a one-line confirmation built from the diff (for example "Added host api.example.com"), then revalidates so the readiness banner refreshes.
- Submit is enabled only when `ready` and `DRAFT`. A 403 `VERIFICATION_REQUIRED` shows a banner linking to Get verified. Success shows the version as `IN_REVIEW`.
- Patch and submit errors other than the above appear as a banner at the top of the Studio.

## 7. Testing

- Unit: API wrappers (paths, methods, bodies), enum pins, error mapping.
- Component: readiness banner for each blocker and the ready state; declared cards including the "Not declared" cases; patch forms; Submit disabled when blocked or not `DRAFT`; list rows and empty state; forms' validation errors.
- Server actions: success, `ApiError` mapping, `VERIFICATION_REQUIRED`, revalidation.
- Routing: rail shows Agents for developers; buyers never see it; role gating unchanged.
- `tsc --noEmit` clean. `next lint` is not configured in this repo.
- **Not checked in a browser** until the user runs the stack; stated in the PR.

## 8. Docs

Add to `docs/frontend-gaps.md` that the Studio's "what it did" half and flow canvas are deferred to P3c pending run endpoints.
