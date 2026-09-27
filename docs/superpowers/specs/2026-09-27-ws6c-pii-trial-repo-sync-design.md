# WS6c — PII Scan, Trial Profiles, REPO Sync, CLI Profile/Fixture Commands — Design

> Narrows WS6's parent section in `2026-09-23-agent-store-developer-platform-and-sandbox-design.md`. Closes the slice WS6b explicitly deferred. Backend and CLI only, no UI (FE-3).

## 1. Goal and scope

A developer's sandbox fixtures may contain real customer data by accident; a trial profile (the one buyers can run on sample data before connecting) must never leak it. Separately, a developer should be able to keep sandbox profiles as files in their repo instead of only editing them in the portal, and drive fixture upload and profile inspection from the CLI.

**In scope**

- PII scan on every fixture dataset (`dataset.pii_scan`), checked at upload time.
- `trial_enabled` on `sandbox_profile`, gated on a clean PII scan plus a developer attestation.
- Computed `sandbox_profile.status` (`READY` / `FILE_ERRORS`), reusing WS2's schema checker.
- REPO-sourced profile sync: `fixtures/profiles/*.yaml` → `sandbox_profile` on `agentstore push`, with a conflict rule against portal edits.
- CLI: `agentstore fixtures push|list`, `agentstore profile list|pull`.

**Out of scope (with owner)**

- Portal UI (screen 15): FE-3.
- Eval runs (`POST /agents/{id}/versions/{vid}/eval-runs`, `eval_case_result`): WS8.
- Developer verification gating trial listing visibility ("Try on sample data" shown only when verified + trial profile covers every READ scope): WS7 + FE-3.
- An explicit `versionId` picker for profile status: deferred until a real multi-DRAFT workflow needs it (see §5).

## 2. Data model (migration V35)

```sql
ALTER TABLE dataset ADD COLUMN pii_scan JSONB;
-- {status: 'CLEAN'|'FOUND', findings: [{type, row, column}]}, null until scanned

ALTER TABLE sandbox_profile ADD COLUMN description VARCHAR(255);
ALTER TABLE sandbox_profile ADD COLUMN trial_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE sandbox_profile ADD COLUMN no_real_data_attested_by UUID;  -- no FK: same "tests use synthetic user ids"
                                                                        -- convention as dataset_binding.bound_by_user_id (V27)
ALTER TABLE sandbox_profile ADD COLUMN no_real_data_attested_at TIMESTAMPTZ;
ALTER TABLE sandbox_profile ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'PORTAL';  -- PORTAL | REPO
ALTER TABLE sandbox_profile ADD COLUMN repo_source_hash VARCHAR(64);                   -- sha256 of the last-synced YAML file, REPO only
```

`status` is not a column — see §5.

## 3. PII scanner

New class `PiiScanner` in `com.agentstore.platform.data`, called from `DatasetService.upload(...)` immediately after ingestion, **only when `purpose == FIXTURE`** (the only population that can back a trial profile; buyer data and other purposes are out of scope for this scan). Runs against every cell of every normalized row for tabular formats (CSV/JSON). `DataFormat.PDF` has no text extraction anywhere in the codebase today (`FormatDetector` only sniffs the magic bytes) and building one is out of scope here — a PDF fixture is not scanned; `pii_scan` is left `null` for it, and such a fixture can never be marked clean, so it can never back a `trial_enabled` profile (§4's check treats `null` the same as `FOUND`).

Detectors:

| Type | Check |
| --- | --- |
| `PAN` | `^[A-Z]{5}[0-9]{4}[A-Z]$` |
| `GSTIN` | 15-char structural regex, plus the embedded chars 3–12 must themselves match the PAN pattern |
| `AADHAAR` | 12 digits, Verhoeff checksum over the digit sequence |
| `IFSC` | `^[A-Z]{4}0[A-Z0-9]{6}$` (structural only — no public checksum exists) |
| `ACCOUNT_NO` | heuristic: a 9–18 digit run in a column whose header matches `/account|acct|a\/c/i` |
| `EMAIL` | standard regex |
| `PHONE` | `^[6-9]\d{9}$` (Indian mobile) or E.164-shaped `^\+\d{10,15}$` |

Result: `{status: findings.isEmpty() ? 'CLEAN' : 'FOUND', findings: [{type, row, column}]}`, capped at 200 findings (a dataset with more just reports the first 200 plus a count — no reason to store an unbounded array). Written to `dataset.pii_scan` in the same transaction as the upload.

## 4. Trial profiles and attestation

`PUT /agents/{id}/sandbox-profiles/{profileId}` gains two request fields: `trialEnabled: boolean`, `attest: boolean`.

- Setting `trialEnabled: true` requires `attest: true` in the same request, else 400 `ATTESTATION_REQUIRED`.
- Every dataset bound to the profile must have `pii_scan.status == 'CLEAN'`; a `null` scan (PDF, or not yet scanned) counts as not clean; violations return 409 `PII_FOUND` with the offending resource names.
- On success: `no_real_data_attested_by = caller`, `no_real_data_attested_at = now()`, `trial_enabled = true`.
- **Any change to the profile's bindings** (this request or a later one) clears `no_real_data_attested_by/at` and forces `trial_enabled = false` — attestation covers a specific set of files, not the profile in the abstract. A request that both changes bindings and sets `trialEnabled: true` with `attest: true` re-attests against the new set in the same call (reset-then-reapply, not reset-then-reject).
- Setting `trialEnabled: false` never requires `attest` and always succeeds.

## 5. Profile status

Computed on every `GET /agents/{id}/sandbox-profiles` and `GET .../sandbox-profiles/{id}`, not stored:

1. Resolve the agent's version to check against: latest `DRAFT`, else latest version with `buildStatus == SUCCEEDED`, else `null` (no version yet → `status = READY`, nothing to check against).
2. For each binding `(resource, datasetId)`, look up the resource's declaration in that version's manifest and call the existing `DatasetCheckService.check(dataset, decl)`.
3. `status = FILE_ERRORS` if any binding's check is not OK, else `READY`.

This reuses WS2's `SchemaChecker` entirely — no new checking logic, just aggregation. Cost is bounded by a profile's binding count (typically single digits), computed inline, no caching. `ProfileView` gains a `status` field.

## 6. REPO profile sync

**Portal side:** any `PUT /agents/{id}/sandbox-profiles/{id}` (portal-driven edit) sets `source = 'PORTAL'` unconditionally, regardless of what it was before.

**CLI side:** `fixtures/profiles/<name>.yaml` (one file per profile), shape:

```yaml
name: happy-path
description: Clean invoice batch, no gates
input: {}
gatePolicy: { default: AUTO_APPROVE, actions: {} }
llmMode: MOCK
spendCap: 50.00
bindings:
  - resource: invoices
    fixture: sample-invoices.csv   # matches a dataset's displayName among the org's fixtures
```

`trialEnabled` is deliberately not a YAML field — attestation names a specific person (`no_real_data_attested_by`) and a repo sync has none, so REPO-sourced profiles are always created with `trial_enabled = false`; a developer flips it on afterward through the API (portal or a future CLI verb), which immediately reclaims `source = PORTAL` as normal.

After a successful `agentstore push` finalize, for each `fixtures/profiles/*.yaml` file: resolve each `fixture:` reference to a dataset id via `GET /developer/fixtures` (by `displayName`, taking the most recently created match if the org has more than one fixture with that name; unresolvable reference is a push warning, not a failure — the profile syncs with that binding skipped), compute `hash = sha256(file bytes)`, then:

| Existing profile | Hash vs. stored `repo_source_hash` | Action |
| --- | --- | --- |
| none (by name) | — | create, `source = REPO`, store hash |
| `source = REPO` | unchanged | no-op |
| `source = REPO` | changed | overwrite, store new hash |
| `source = PORTAL` | (any) | overwrite, `source = REPO`, store hash |

Local file always wins once pushed — matches the existing `manifest.json`-vs-server-revision precedent (`agentstore push` warns, `agentstore pull manifest` fetches) already in the CLI.

## 7. CLI

```
agentstore fixtures push <file> [--display-name N] [--source-label L]
                              Upload a local fixture file (wraps POST /developer/fixtures)
agentstore fixtures list      List the org's fixtures with pii_scan status
agentstore profile list       List sandbox profiles for the project's agent, with status/trialEnabled/source
agentstore profile pull       Write every server-side profile to fixtures/profiles/<name>.yaml
                              (round-trips a portal edit back to the repo; overwrites local files unconditionally —
                              a developer who wants to keep a conflicting local edit should check it into git first)
```

`agentstore push` itself gains no new flags; profile sync runs automatically after a successful finalize, same as today's bundle upload.

## 8. API summary

- `GET /api/v1/developer/fixtures` (existing) — response gains `piiScan` field
- `PUT /api/v1/agents/{id}/sandbox-profiles/{id}` (existing) — request gains `trialEnabled`/`attest`; response (`ProfileView`) gains `status`, `trialEnabled`, `source`, `description`
- `GET /api/v1/agents/{id}/sandbox-profiles` / `/{id}` (existing) — response gains the same fields
- `POST /api/v1/agents/{id}/sandbox-profiles/sync` (new, CLI-only, role `DEVELOPER`) — body: array of `{name, description?, input?, gatePolicy?, llmMode?, spendCap?, bindings[], contentHash}`; applies the table in §6 per entry, returns per-entry `{name, action: "created"|"updated"|"skipped", unresolvedFixtures: [...]}`

## 9. Testing

- `PiiScannerTest`: one true-positive and one false-negative case per type, including a GSTIN whose embedded PAN is deliberately malformed (should not flag), and the 200-finding cap.
- `DatasetService` upload test: `pii_scan` populated for `FIXTURE` purpose, left `null` for `BUYER_DATA` and for a PDF fixture.
- Attestation: happy path; 409 on dirty scan; 409 on a `null` (PDF/unscanned) scan; 400 without `attest`; binding change clears attestation in the same request and in a later one; re-attestation in the same request that changes bindings.
- Profile status: `READY` with no version; `READY` against a clean DRAFT; `FILE_ERRORS` when a binding fails; DRAFT-over-LIVE precedence.
- Sync: all four rows of the §6 table; unresolvable fixture reference produces a warning, not a sync failure; hash stability (re-pushing an unchanged file twice is a no-op both times).
- CLI: `fixtures push`/`list`, `profile list`/`pull` unit tests mirroring `push.test.ts`'s fetch-mocking pattern.
- OpenAPI drift test extended for `POST /sandbox-profiles/sync`.

## 10. Follow-ups (not built)

- Portal UI for all of the above (FE-3).
- A CLI verb to flip `trialEnabled` without going through the portal.
- WS7 gating trial visibility on developer verification.
- WS8 eval runs, which reference profiles by name.
