# Agent Store MVP — Design

> Fundraising-timeline MVP: a working platform where a handful of invited developers publish real agents, buyers discover and run them with real governance UX, and runs actually execute with real metering. Legal/regulatory work (RBI PPI, GSP partnership, DPDP) is explicitly deferred — see `business-technical-analysis.md`. This doc covers the platform only.

## 1. Purpose and scope

**Goal:** a demoable, technically credible product to raise funds on — not the full v1 described in `agent-store-spec.md`. It proves the core loop end to end: a developer publishes a real agent, a buyer discovers it through a governed catalog, connects data, runs it, and gets billed off real usage.

**In scope:**
- Real container-based agent execution, isolated per run
- Real manifest-driven governance (permission statement, scope enforcement, approval gates)
- Real metering → ledger → billing statement
- Developer publishing wizard + light human review pipeline
- Buyer catalog, run console, connections flow, billing — built directly off the three existing mockups

**Explicitly out of scope (deferred, not forgotten):**
- Real Tally/GST-portal/bank integrations — replaced by sample-file upload or synthetic data as the connection source
- Real money movement — the ledger is real double-entry accounting; no bank rails, no UPI, no RBI PPI question to resolve yet
- Multi-region deployment, DPDP compliance workstream, GSP/ASP partnership
- Open or public agent submission — only the founder + a few invited developers can publish
- Microservices, Kafka, multi-tenant encryption-key-per-tenant — deferred until there's real scale or real third-party trust boundaries to justify them

## 2. Architecture

**One Spring Boot application** (modular monolith), one Postgres database, one Next.js/TypeScript frontend, and one external dependency for sandboxed execution (Modal or E2B).

```
Next.js (TS) frontend
  — Buyer console (catalog, run console, billing)
  — Developer portal (wizard, review status, payouts)
  — Connection flow (source → grant → credentials → test)
        |
        | typed REST/JSON (+ WebSocket or polling for live run traces)
        v
Spring Boot monolith (one deployable)
  ├─ catalog       — agents, versions, listing state
  ├─ manifest      — schema + validation; single source of truth for
  |                  permission statement / wizard preview / runtime enforcement
  ├─ publishing     — submission pipeline, review queue, staged rollout state
  ├─ execution      — on Run: resolve image, call Modal/E2B, inject scoped
  |                  secrets, enforce egress allowlist, stream trace back
  ├─ ledger         — usage events (outbox) → double-entry ledger → statements
  └─ identity       — buyer-org users (roles) + developer accounts
        |
        v
   Postgres (one DB, module-owned tables)

External: Modal or E2B (Firecracker microVM per run), container registry (e.g. ECR)
```

Modules talk to each other via Java interfaces within the same process — no network calls, no service discovery, no distributed transactions. The only real network boundary is the monolith calling out to the execution provider (Modal/E2B) and the registry.

**Why a bought execution substrate, not a built one:** Modal/E2B/Daytona already sell Firecracker-microVM-per-run isolation, billed per second (~$0.05/vCPU-hr), with secret injection and network policy support. Building this in-house (the microVM/gVisor plan in the original spec) is the single most expensive piece of infrastructure in the whole system and isn't a place to spend MVP time — it's also exactly what AWS's own new Bedrock AgentCore product does (managed sandbox + identity + audit logging in front of any agent framework), confirming this is the current industry pattern, not a shortcut.

**Escape hatch:** because `execution` is a clean module boundary, extracting it into its own deployable later (if/when volume justifies independent scaling) is a lift-and-shift, not a rewrite — same principle the original spec applied to the execution plane.

## 3. Data model

| Entity | Key fields | Notes |
|---|---|---|
| `Org` | name, type (buyer/developer) | An org can be a developer publisher or a buyer company, not both in v1 |
| `User` | org_id, role, email | Roles: buyer (finance lead / approver), developer, admin |
| `Agent` | org_id (developer), name, category | The listing identity; versions carry the actual manifest |
| `AgentVersion` | agent_id, manifest (JSON), image_ref, status (draft/review/live/rejected), changelog | Manifest is the single schema driving all three UI renderers |
| `Connection` | buyer_org_id, agent_id, granted_scopes, spend_cap, status | The grant a buyer makes; enforced at run time by `execution` |
| `Run` | connection_id, status, cost_cap, total_charged | One execution instance |
| `RunStep` | run_id, timestamp, kind (read/write/gate/cost), detail | The trace shown in the run console |
| `LedgerEntry` | run_id, org_id, amount, type (charge/reversal/fee), created_at | Double-entry; billing/cost tracked separately as in the original spec |
| `Wallet` | org_id, balance, cap | Prepaid balance for the demo; no real money rails behind it |

## 4. Core flows

**Publish:** developer fills the wizard (basics → scopes → cost/limits → evals → pricing → review) against the manifest schema → submits → `publishing` runs automated checks (manifest lint, scope-boundary sandbox test) → lands in the human-review queue (real queue, real reviewer — you, at this stage) → approved version goes live, tied to a built+registered container image.

**Connect:** buyer picks a data source (sample file upload for MVP, not live Tally/GST) → sees the grant screen pre-populated from the agent's manifest, can only turn on what's declared → credentials/config stored in a managed vault → test run (dry, no charge) → connection active with a spend cap.

**Run:** buyer triggers a run → `execution` resolves the agent's image, calls Modal/E2B for a fresh sandbox, injects the scoped secret for this connection, enforces the manifest's egress allowlist, streams `RunStep`s back to the frontend as they happen → if the manifest declares an approval gate and a step crosses it, the run pauses and waits on a buyer decision → on completion, usage events are emitted to the `ledger` module.

**Bill:** usage events land in an outbox table → aggregated into `LedgerEntry` rows → wallet balance debited → statement view assembled from ledger entries, matching the billing mockup's per-agent breakdown.

## 5. Open questions

- Which of Modal or E2B to standardize on — both fit the architecture identically, so this is a low-stakes pick that can be made at implementation time rather than blocking this design.
- What the first 1-2 reference agents actually do (needed to make the demo concrete) — likely candidates from `agent-store-spec.md` §7, but should be picked for whichever has the simplest real data story (e.g., bank statement matching against an uploaded CSV) rather than the one gated on GSP access.
- Whether developer payouts need to be real (even manual/off-platform) for the fundraising demo, or whether "developer sees their earnings dashboard" is sufficient without an actual payout rail.
