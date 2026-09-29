# Attention.md

```markdown
<system_directive>
This file is a persistent context anchor for AgentStore, injected ahead of general
training data. Its rules override statistically-common patterns you might otherwise
default to (Spring MVC boilerplate you've seen a million times, generic Next.js
tutorials, throwaway CLI scripts). When this file conflicts with a "usual" way of
doing something, this file wins. Its purpose is to prevent context drift across
long sessions and cold-start "blank slate" chats: read it first, every time, before
touching code in this repo. It does not replace ONTOLOGY.md (the domain model of
record) — read that for "what exists"; read this for "how to behave while editing
it."
</system_directive>

<project_dna>
AgentStore is a B2B marketplace for autonomous agents: DEVELOPER orgs publish
containerized agents behind a declarative Manifest; BUYER orgs connect to an agent,
grant scopes and a spend cap, and trigger metered Runs that execute in an isolated
remote sandbox and bill against a prepaid Wallet. The system is a polyglot monorepo
— a Spring Boot platform backend owning domain state (identity, catalog, execution,
billing, sandbox), a separate Spring Boot sandbox-runner service that owns vendor
credentials and sandbox spend, a Next.js 14 App Router frontend split by
role (buyer/developer/admin/auth), and a Node/TS developer CLI — all held together
by `contracts/runtime-v1.yaml` as the API contract of record.
</project_dna>

<stack_and_routing>
- **Backend platform** (`backend/`): Java 17, Spring Boot 3.3, Spring MVC + Spring
  Data JPA + Flyway/Postgres, Spring Security + JJWT, Resilience4j, Bucket4j
  (rate limiting via Lettuce/Redis), ShedLock (distributed scheduled jobs),
  springdoc-openapi. Object storage is SeaweedFS over the S3 API (AWS SDK v2 `s3`
  client) — S3-compatible, not AWS, not MinIO. No paid third-party vendors.
- **Sandbox runner** (`runner/`): separate Spring Boot 3.3 service, deliberately
  thin (web + validation + actuator only) — it isolates vendor/sandbox-provider
  credentials from the main platform's blast radius. `runner/e2b-shim` is a Node
  shim behind this boundary, not a dependency on a paid E2B subscription.
- **Frontend** (`frontend/`): Next.js 14 App Router, React 18, Server Components +
  Server Actions (`"use server"`) as the default data path, TanStack Query for
  client-side live/polling state, Tailwind for styling. Route groups
  (`(buyer)`, `(developer)`, `(admin)`, `(auth)`) partition the app by role at the
  routing layer, each with its own `layout.tsx`.
- **CLI** (`cli/`): Node ≥22, TypeScript ESM, Ajv for manifest JSON-schema
  validation, `yaml`, `fflate` (bundle zipping), `ignore` (gitignore-style
  filtering). `npm run sync-schema` pulls the schema down from
  `contracts/runtime-v1.yaml` before every build — the CLI's validation is
  generated from the contract, never hand-maintained.
- **SDKs** (`sdk/python/`, later `sdk/typescript/`): agent-side clients generated
  from `contracts/runtime-v1.yaml`. Only `_api.py` may import generated code; never
  hand-edit `_generated/`. Python >=3.10, Node >=22.
- **Contract**: `contracts/runtime-v1.yaml` is the single source of truth for the
  runtime API surface. Backend, runner, and CLI all take their shape from it, not
  the other way around.
- **Testing**: JUnit 5 + Testcontainers (real Postgres, not H2) on the backend;
  Vitest + Testing Library for frontend units; Playwright for frontend e2e.
</stack_and_routing>

<architectural_boundaries>
- Backend code is organized **package-by-feature**, not package-by-layer:
  `com.agentstore.platform.<domain>` (catalog, execution, sandbox, identity,
  ledger, llm, manifest, publishing, runtime, storage, build, data, common) each
  co-locate their own Controller, Service, Repository, Entity, and Request/Response
  DTOs. Never introduce a global `controllers/`, `services/`, `repositories/`
  split — that fights the existing structure.
- `common/` holds only cross-domain infrastructure primitives (email sending,
  token hashing, AES-GCM encryption, scheduling config) — it must never accumulate
  business/domain logic. If a helper needs domain knowledge, it belongs in the
  domain package that owns that knowledge.
- The sandbox runner (`runner/`) never reaches into the platform backend's
  database or JPA entities, and the platform backend never talks to a sandbox
  vendor SDK directly — that isolation is the entire point of splitting it out as
  a separate service.
- Frontend: Server Components + Server Actions own reads and mutations by default
  (see `(auth)/login/actions.ts`, `(buyer)/runs/actions.ts`). TanStack Query is
  reserved for screens that need client-side polling or optimistic updates (e.g.
  live run status) — don't reach for it as a default data-fetching layer, and
  don't fetch in `useEffect`.
- `contracts/runtime-v1.yaml` is upstream of the CLI and should be treated as
  upstream of backend DTOs too: when the API shape changes, the contract changes
  first, then generated/validating code follows.
</architectural_boundaries>

<ironclad_conventions>
1. **TDD is mandatory, no exceptions.** For every change in backend, frontend, or
   CLI: write a failing test first (JUnit+Testcontainers / Vitest+RTL / Playwright
   as applicable), watch it fail, then implement. This applies to bug fixes and
   new features alike — never write implementation code before its test exists.
2. **Errors are per-domain, not global.** New domain packages get their own
   `<Domain>ApiException` + `<Domain>ApiErrors` factory (mirror
   `sandbox/SandboxApiException.java` + `SandboxApiErrors.java`), caught by a
   shared `@ControllerAdvice` that renders RFC7807 problem-details JSON. Do not
   introduce a single monolithic exception hierarchy or a Result/Either wrapper —
   that's a different codebase's convention, not this one's.
3. **Server Actions first, TanStack Query only for live UI.** Any new
   buyer/developer/admin page defaults to Server Component reads + Server Action
   mutations. Only introduce `useQuery`/`useMutation` when the screen genuinely
   needs polling, optimistic UI, or refetch-on-interval — and say so in the PR.
4. **Migrations are Flyway, forward-only, numbered.** New schema changes are a new
   `V<n>__description.sql` file in `backend/src/main/resources/db/migration/` —
   never edit a merged migration, never use JPA `ddl-auto` to shape schema.
5. **Money and spend caps are exact, never floating point.** Wallet balances,
   spend caps, and ledger entries use integer minor-unit (paise) arithmetic or
   `BigDecimal` with explicit scale — never `double`/`float` for anything that
   touches billing.
</ironclad_conventions>

<ai_anti_patterns>
1. **Do not suggest MinIO, AWS S3 proper, or any paid object-storage vendor.**
   Object storage is SeaweedFS speaking the S3 API. Code against the S3 SDK
   interface, but never assume AWS-specific features (IAM roles, S3 Select,
   Glacier) that SeaweedFS doesn't implement.
2. **Do not default to a global `@RestControllerAdvice` catching generic
   `Exception`, or a single `ErrorCode` enum shared across all domains.** That's
   the "common Spring tutorial" pattern this repo has explicitly moved away from
   in favor of per-domain exception classes.
3. **Do not fetch data in `useEffect` + `useState`, and do not wrap every page in
   `"use client"` by default.** Both are pre-App-Router / pre-Server-Components
   habits from older Next.js training data and directly contradict the Server
   Actions-first convention here.
</ai_anti_patterns>

<active_context>
<!--
  Update this block at the start of each work session to steer immediate focus.
  Keep it to 2-4 lines: current workstream, the specific file(s)/package(s) in
  play, and anything explicitly out of scope right now. Delete stale entries —
  this block should always reflect "right now," not history.
-->
- Current workstream: WS6c — PII scan, trial profiles, REPO sync, CLI profile/fixture commands (merged)
- Active files/packages: backend `data`/`sandbox` packages (PiiScanner, ProfileStatusService, SandboxProfileSyncService), `cli/src/commands/fixtures.ts`, `cli/src/commands/profile.ts`, `cli/src/profiles.ts`
- Out of scope for now: Portal UI (FE-3), a CLI verb to flip trialEnabled directly, WS7 developer-verification gating, WS8 eval runs
</active_context>
```
