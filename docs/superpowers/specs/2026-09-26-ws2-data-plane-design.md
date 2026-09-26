# WS2 — Data Plane (datasets, bindings, output store) — Design

> **Status:** design approved section by section on 2026-09-26. Narrows the WS2 section of `2026-09-23-agent-store-developer-platform-and-sandbox-design.md`. Where the two differ, this document wins. Backend and contract only; the buyer UI stays in the FE-2 plan.

## 1. Goal and scope

A buyer uploads files once into a workspace library and binds them to the resources an agent declares. The agent reads them through `/runtime/v1/resources/**`. Every write the agent makes lands in an output store, staged until an approved gate commits it. The buyer downloads what was committed.

WS1 left `ResourceBackend` returning 501. This workstream implements it and builds the buyer-facing side around it.

**In scope**
- Manifest `resources[]` with an inline schema (§2).
- Datasets: upload, normalize, list, delete, org storage quota (§4).
- Schema check and bindings to connections; trigger-time pinning (§5).
- `DatasetResourceBackend`: reads, file access, writes, staged writes (§6).
- Commit and discard of staged outputs on gate outcome (§7).
- Outputs API (§8); retention job (§9).

**Out of scope** (with owner)
- Buyer UI (Files library, "Bind data" step, Outputs tab): FE-2 plan, which consumes §4, §5 and §8 as written.
- XLSX: an add-on after this ships.
- Server-side encryption at rest (spec: SSE-KMS): WS12 deployment. This slice targets dev and single-node SeaweedFS and does not claim the control.
- Creating `FIXTURE`, `SAMPLE`, `EVAL` datasets and sandbox / eval / trial bindings: WS6, WS8, WS9. Owner types and the mode guard are ready for them.
- Pending-access requests from a new version on the file list: WS11.
- Real system write-back (connectors): post-M4.
- Approval-card preview columns and totals: WS1b and the frontend.
- Agent-SDK helpers for resources: WS4.
- Retention policy for `COMMITTED` outputs: kept indefinitely in v1; a follow-up.

## 2. Manifest `resources`

The manifest gains an optional list:

```json
"resources": [
  { "name": "bank_statements", "format": "csv",
    "schema": { "required": ["date", "narration", "amount"],
                "properties": { "date": {"type": "date"}, "amount": {"type": "number"} } } },
  { "name": "journal_entries", "format": "json", "schema": { "required": ["bank_line", "amount"] } },
  { "name": "statement_pdf", "format": "pdf" }
]
```

- `format` is `csv`, `json` or `pdf`. `schema` is optional and forbidden for `pdf`.
- `schema.required` lists column (or key) names. `schema.properties.<col>.type` is one of `string`, `number`, `integer`, `boolean`, `date`; a column without a type is a string.
- Validator rules: resource names are unique; every scope `resource` names a declared resource **when `resources` is present**; a `pdf` resource may only be READ. A manifest without `resources` stays valid and its resources are untyped (no bind-time check, format taken from the uploaded file). This keeps every existing agent working.
- The Java `Manifest` record gains `resources`; `manifest-schema.json` gains the definition; the CLI's bundled copy is re-synced with `npm run sync-schema` in `cli/`.
- Decision: the schema is inline in the manifest, not a `schemaRef` file in the bundle (settled 2026-09-26). It works for template and bundle agents and needs no zip access at bind time. `schemaRef` can be added later as sugar.

## 3. Data model (migrations V27 onward)

**`dataset`**: `id`, `owner_org_id` (FK org), `purpose` (`BUYER_DATA` now; `FIXTURE`, `SAMPLE`, `EVAL` reserved), `display_name`, `source_label`, `uploaded_by_user_id`, `format` (`CSV`/`JSON`/`PDF`), `storage_key` (original), `records_key` (normalized JSONL, null for PDF), `sha256`, `size_bytes`, `row_count` (null for PDF), `columns` JSONB (detected header or keys), `created_at`, `deleted_at`, `purge_after`, `purged_at`.

**`dataset_binding`**: `id`, `owner_type` (`CONNECTION` now; `SANDBOX_PROFILE`, `EVAL_CASE`, `TRIAL` reserved), `owner_id`, `resource_name`, `dataset_id`, `check_result` JSONB, `bound_by_user_id`, `bound_at`. Unique on `(owner_type, owner_id, resource_name)`.

**`run_dataset_pin`** (not in the earlier spec): `run_id`, `resource_name`, `dataset_id`, `sha256`, primary key `(run_id, resource_name)`. Written at run creation; the runtime reads only pins, so unplugging, replacing or purging a file mid-run cannot change what an agent reads. No FK cascade: a purged dataset is tombstoned, never deleted (§9).

**`run_output`**: `id`, `run_id`, `resource_name`, `status` (`STAGED`/`COMMITTED`/`DISCARDED`), `record_count`, `storage_key` (JSONL chunk), `gate_request_id` (nullable), `created_at`. One row per write call.

**`org.storage_limit_bytes`**: default 5 GB.

## 4. Datasets API (`/api/v1`, buyer JWT)

Developer API keys stay excluded by the existing `ApiKeyScope` allowlist; nothing to change there. Errors use the existing `{code, message}` convention.

- `POST /datasets` (multipart `file`, `displayName`, `sourceLabel`).
  - Streams to a temp file, computes sha256, detects the format from extension and content. Unknown format: 415.
  - Caps: 50 MB per file (413) and the org storage quota (413 `QUOTA_EXCEEDED`). Requires `spring.servlet.multipart` limits set to match.
  - CSV → JSONL of string-valued objects; handles BOM, quoting, ragged rows (a short row's missing cells are null; an over-long row is a row error surfaced at check time). JSON must be an array of objects, else 422. PDF is stored as-is.
  - Stores the original and the JSONL, records `row_count` and `columns`. The file is **untyped**: nothing is checked against a resource until it is bound.
- `GET /datasets?include=usage`: the caller's org's files with bindings (`agent`, `version`, `access: READ`), `rowCount`, `sizeBytes`, `sourceLabel`, `uploadedBy`, `createdAt`.
- `DELETE /datasets/{id}`: 409 while a non-terminal run pins it; otherwise unbinds everywhere, sets `deleted_at` and `purge_after = now`, so the retention job removes it within about an hour. This is faster than the earlier spec's 30-day soft delete on purpose: for an explicit "delete this file" a 30-day window is a privacy liability. Only *unplugging* gets the 30-day grace (§5).
- `GET /orgs/{id}/storage` → `{usedBytes, limitBytes}`. Usage counts originals plus JSONL copies of non-purged datasets, not outputs.

## 5. Check, bind, pin

- `POST /datasets/{id}/check {agentVersionId, resource}` → `check_result` `{rowCount, columnsMatch, missingColumns[], rowErrors[{row, column, message}] (first 50)}`, saving nothing. Every row is validated against the resource's schema; CSV cells are coerced per declared type (`"12.50"` is a valid number, `"abc"` is not). Untyped resources and PDFs check only that the file parses and, for tabular files, has rows.
- `PUT /connections/{id}/bindings/{resource} {datasetId}`.
  - The caller's org must own both connection and dataset.
  - The resource must be declared in the connection's agent version, be READ, and be covered by the connection's granted scopes. WRITE resources are never bound.
  - Runs the same check; any failure returns `422 {code: DATASET_CHECK_FAILED, checkResult}`.
  - Success replaces an existing binding and clears the dataset's `purge_after`.
- `DELETE /connections/{id}/bindings/{resource}`: unplugs. A dataset no longer bound anywhere gets `purge_after = now + 30 days`.
- `GET /connections/{id}/bindings`: what is plugged in.
- Wiring bindings into `POST /connections` (the FE-2 `bindings` field) is WS15/FE-2.

**Trigger-time pinning.** `BindingResolver`, called from the run trigger (`RunController.trigger` today), resolves every granted READ resource the manifest declares and writes the run's `run_dataset_pin` rows in the run-creation transaction. A declared, granted READ resource with no binding fails the trigger with `422 MISSING_BINDING` listing the resources. There are no optional resources in v1. Manifests without `resources` are unaffected.

**Mode guard.** `BindingResolver` resolves a `BUYER_DATA` dataset only for a `PRODUCTION` run. `RunMode` already has `SANDBOX`, `REVIEW`, `TRIAL`; those modes can only ever resolve `FIXTURE`, `SAMPLE` or `EVAL` data.

## 6. Runtime: `DatasetResourceBackend`

Replaces `UnavailableResourceBackend`. The controllers, `ScopeGuard` and step recording are unchanged. The backend reads only the run's pins. Defense in depth: it re-checks `dataset.purpose` against `principal.mode()` on every read.

**Reads**
- `metadata`: format and row count of the pinned dataset. No pin: 404 `RESOURCE_NOT_BOUND` (a safety net; the trigger already blocks it).
- `records`: opaque cursor = base64 of `v1:<sha8>:<byteOffset>` into `records.jsonl`. A page is one S3 range read extended to `limit` whole lines; `nextCursor` is null at the end. A cursor whose sha does not match the pin: 400 `INVALID_CURSOR`. `ObjectStorage` gains `openRange(key, from, to)`. Values are coerced per the schema's types on the way out (the bind-time check proved every row coerces). A PDF resource: 409 `NOT_TABULAR` (read it through `/file`).
- `file`: returns a `FileLink` whose URL is a new `GET /runtime/v1/resources/{resource}/file/content`, which streams the original through the backend under the run token. Deliberately not a presigned object-store URL: agents run behind an egress allowlist, and a presigned URL would need the object-store host opened to them and would outlive the run's scope checks. Cost: backend bandwidth on files of at most 50 MB. `contracts/runtime-v1.yaml` is regenerated and its drift test (`RuntimeContractTest`) updated.

**Writes**
- Each call is validated whole against the WRITE resource's schema (required columns present, types coerce). Failure: 422 with the first 50 row errors.
- Caps: 5 MB per call and 200 MB per run (413 `WRITE_TOO_LARGE`). This closes the WS1 review's deferred minor "no total-size cap on agent-written bodies" for record writes.
- Records go to `outputs/{runId}/{outputId}.jsonl`, then a `run_output` row is inserted; if the insert fails the blob is deleted quietly. Status is `STAGED` when the scope has `requiresApproval`, otherwise `COMMITTED`.

**Failures.** Object store unreachable on a runtime call: 503 `STORAGE_UNAVAILABLE`. Rate limits and scope-violation handling are unchanged.

## 7. Staged writes: commit and discard

Three places set a gate's final status today: `GateService` on approve/reject, `GateService` on expiry, `RunLifecycleService` when a run ends and closes its open gate. Each publishes one `GateOutcomeEvent(runId, gateId, status, affectsResources)`. `OutputCommitService` handles it in the same transaction:

- `APPROVED`: this run's `STAGED` rows for the listed resources become `COMMITTED`, stamped with `gate_request_id`.
- `REJECTED` or `EXPIRED`: those rows become `DISCARDED`.
- An approved gate with empty `affects_resources` commits nothing. The agent must say what to commit.
- **Timing rule:** a gate commits only rows staged before the gate was requested. A row written to a `requiresApproval` scope after the approval is staged again and waits for the next gate.
- When a run reaches any terminal state, its remaining `STAGED` rows become `DISCARDED`, including an agent that finishes without ever asking for approval.

## 8. Outputs API

- `GET /runs/{id}/outputs`: each output's resource, status, record count, created time. `?preview=50` includes the first 50 rows. Only the run's buyer org may call it.
- `GET /runs/{id}/outputs/{outputId}/download?format=csv|json`: streamed through the backend (JSONL to CSV cannot be a presigned URL). The UI's staged/committed badge comes from `status`.

## 9. Retention

A ShedLock-guarded hourly job (ShedLock 5.16.0 is already a dependency):
- Purges datasets whose `purge_after` has passed, unless a non-terminal run pins them: deletes the original and the JSONL, nulls the storage keys, sets `purged_at`. The row is a **tombstone**, not deleted. This deviates from the earlier spec's "hard purge" because run pins and run traces refer to the dataset by name and sha; no buyer content survives, only name, size and sha.
- Deletes the blobs of `DISCARDED` outputs older than 30 days.
- Revoking a connection unbinds its bindings, which starts the 30-day clock for any file no longer bound anywhere.

## 10. Testing

Testcontainers with the existing `AbstractStorageIntegrationTest` (real SeaweedFS S3). RED before GREEN for each task.
- Unit: CSV and JSON normalizers (BOM, quoting, ragged rows, non-array JSON); schema checker (coercion, first-50 errors); cursor encode/decode.
- Integration: upload caps and quota; cross-org read, bind and download all fail; paging across page boundaries and a cursor from a different file; the full gate lifecycle (approve commits, reject and expiry discard, run-end discard, the timing rule) driven through the real `GateService`; the mode guard via both the resolver and the backend; retention (purge, tombstone, pinned-dataset exception); `RuntimeContractTest` against the regenerated contract; existing agents without `resources` run unchanged.
- End-to-end acceptance (replaces a UI walk-through): upload CSVs, bind, trigger, read through `/runtime/v1`, stage a write, approve the gate, download the committed output; and a SANDBOX run can never read a `BUYER_DATA` dataset.

## 11. Plan task order

1. Manifest `resources` (model, JSON schema, validator, CLI resync).
2. Migrations, entities, repositories.
3. Ingestion: format detection, CSV and JSON normalizers, `ObjectStorage.openRange`, multipart config.
4. Datasets API: upload, list, delete, storage and quota.
5. `SchemaChecker` and the check endpoint.
6. Bindings API, `BindingResolver`, trigger-time pins and `MISSING_BINDING`, mode guard.
7. `DatasetResourceBackend` reads: metadata, records cursor, file and its content endpoint, contract regeneration.
8. Writes and `run_output`, with the caps.
9. `GateOutcomeEvent`, `OutputCommitService`, run-end discard.
10. Outputs API.
11. Retention job and tombstones.
12. Docs and ontology, the acceptance test, full-suite run.

## 12. Decisions settled during design (do not re-ask)

- Inline `schema` in the manifest, not `schemaRef`.
- Formats: CSV, JSON, PDF. No XLSX yet.
- Read path: normalize to JSONL at upload; byte-offset cursor over an S3 range read.
- Multipart upload through the backend, 50 MB cap, 5 GB default org quota.
- Bindings pinned per run; a missing binding blocks the trigger.
- Explicit delete purges within about an hour; only unplugging has the 30-day grace.
- Files stream through the backend, not presigned URLs.
- Datasets are tombstoned on purge, not row-deleted.
