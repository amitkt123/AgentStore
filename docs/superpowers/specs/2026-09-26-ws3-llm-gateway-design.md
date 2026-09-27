# WS3 — LLM Gateway (platform LLM + BYOK, mock/replay) — Design

> **Status:** section 4 (mock/replay, errors, testing) approved by the user on 2026-09-26. Sections 1–3 were **reconstructed on 2026-09-27** from the WS3 outline in `2026-09-23-agent-store-developer-platform-and-sandbox-design.md`, the WS1 and WS2 specs, and the code on master (14ce900). Every decision that was inferred rather than stated is marked `[I#]` and listed in §9 for confirmation. Narrows the parent's WS3 section; where the two differ, this document wins. Backend and contract only.

## 1. Goal and scope

Agents call LLMs through `POST /runtime/v1/llm/chat`. The platform enforces the model allowlist, meters tokens, caps them per run and attributes every call to a run. Verified developers may bring their own key. Non-production runs can be served from canned or recorded responses, so sandbox reruns are free and deterministic.

WS1 left `LlmBackend` returning 501 (`UnavailableLlmBackend`). This workstream replaces it.

**In scope**
- Manifest `llm` block and its submit-time rules (§2).
- Data model: `llm_model`, `llm_call`, `byok_credential`, org verification flag, run LLM columns (§3).
- The gateway: request and response shape, call flow, budget reservation, provider adapters, resilience (§4).
- BYOK: key API, `byok_proxy`, `byok_direct` (§5).
- Mock, replay and recording (§6).
- Error contract (§7), testing (§8).

**Out of scope** (with owner)
- Streaming (SSE). The parent spec listed it; deferred. Token counts come from the provider's response body.
- The provider-native `raw()` passthrough: SDK/WS4 concern once the normalized shape is proven.
- "LLM keys" settings page and per-run LLM usage table (UI): developer-portal/FE plans.
- Deducting LLM cost from developer earnings: WS10.
- The per-developer monthly sandbox LLM quota: WS6. `llm_call` carries what it needs to sum.
- The verification/KYC workflow that sets `org.developer_verified_at`: WS7.
- Sandbox profiles that fill `run.llm_mode`, `replay_run_id` and `llm_mock_json`: WS6. WS3 adds the columns and honours them; nothing in WS3 sets them.
- Surfacing `llm_model.display_name` and `provider_retains_data` in listings: the columns land now, the UI later.

## 2. Manifest `llm`

The manifest gains an optional block. A manifest without it stays valid; its agents simply cannot use the gateway (`400 LLM_NOT_DECLARED`). The older `modelRequirements` field is ignored.

```json
"llm": {
  "mode": "platform",                      // platform | byok_proxy | byok_direct
  "models": ["claude-sonnet-5", "claude-haiku-4-5-20251001"],
  "maxTokensPerRun": 400000,
  "provider": "anthropic"                  // required for byok_*, forbidden for platform  [I2]
}
```

- `models`: 1 or more distinct ids. `maxTokensPerRun`: integer, 1 up to a platform maximum (`agentstore.llm.max-tokens-per-run-limit`, default 5,000,000). `provider`: `anthropic` or `openai`.
- Schema-level rules (`ManifestValidator`): `provider` present exactly when `mode` is `byok_*`; `byok_direct` requires the provider's API host (`api.anthropic.com`, `api.openai.com`) in `network.allowlist`.
- Submit-time rule (`PublishingService`, not the schema): `mode != platform` requires the developer org to be verified, else the submit is rejected with `BYOK_NOT_VERIFIED`. [I3]
- Tiering: BYOK of either flavour makes the version Tier B (parent spec), alongside the existing "any WRITE" rule.
- Model availability is **not** checked at submit. It is checked per call, so an operator disabling a model takes effect without resubmitting agents.
- The Java `Manifest` record gains `llm`; `manifest-schema.json` gains the definition; the CLI's bundled copy is re-synced with `npm run sync-schema` in `cli/`.

## 3. Data model (migrations V29 onward)

**`llm_model`**: `id` (e.g. `claude-sonnet-5`, PK), `provider` (`ANTHROPIC`/`OPENAI`), `display_name`, `provider_retains_data` BOOLEAN, `input_price_per_mtok` and `output_price_per_mtok` NUMERIC(12,4), `currency`, `enabled`. The migration seeds the two models named in the parent spec **disabled with zero prices**; an operator sets real prices and enables them. Prices are not invented here. [I10]

**`llm_call`**: `id`, `run_id` (FK), `model_id`, `source` (`PLATFORM`/`BYOK_PROXY`/`MOCK`/`REPLAY`), `status` (`OK`/`ERROR`/`BUDGET_REJECTED`/`REPLAY_MISS`), `input_tokens`, `output_tokens`, `cost` NUMERIC(12,4), `latency_ms`, `request_hash` CHAR(64), `replay_ordinal` INT NULL, `request_key` and `response_key` (object-store keys, null unless recorded), `recording_expires_at`, `recording_purged_at`, `error_code` (our code, never a provider body), `created_at`. Indexes: `(run_id, created_at)`, `(run_id, request_hash)`. Unique `(run_id, request_hash, replay_ordinal)` where `replay_ordinal` is not null (§6).

**`byok_credential`**: `id`, `developer_org_id` (FK), `provider`, `encrypted_key` BYTEA, `nonce` BYTEA, `last4`, `created_at`; unique `(developer_org_id, provider)`. Encrypted with the existing `AesGcmCipher` (the same key as connection secrets).

**`org.developer_verified_at`** TIMESTAMPTZ NULL. Nothing in WS3 sets it; WS7 will. Tests and local development set it directly. [I3]

**`run`** gains: `llm_tokens_used` INT and `llm_tokens_reserved` INT (both default 0, both non-negative), `llm_mode` VARCHAR(10) NULL (`MOCK`/`REPLAY`; null means real), `replay_run_id` UUID NULL, `llm_mock_json` JSONB NULL, and the constraint `CHECK (llm_mode IS NULL OR mode <> 'PRODUCTION')`.

## 4. The gateway

### 4.1 Shape

`GatewayLlmBackend` implements the existing `LlmBackend` seam; `RuntimeLlmController` and `ScopeGuard` are unchanged except that step recording moves into the gateway so failed calls are traced too (§7). Units, each testable alone:

- `LlmRequestNormalizer`: validates and canonicalises the request; produces the request hash.
- `LlmBudget`: atomic token reservation and settlement (§4.3).
- `LlmProvider` (interface) with `AnthropicProvider` and `OpenAiProvider`: one HTTP call, normalized in and out, no retry logic.
- `ProviderClient`: retry once, per-provider circuit breaker, timeouts, error mapping (§4.4).
- `LlmKeyResolver`: platform key or the developer's decrypted key.
- `LlmRecorder`: writes and reads request/response blobs.
- `MockResponder` and `ReplayResponder` (§6).

### 4.2 Request and response [I6]

Request: `{model, system?, messages[{role: user|assistant, content: string | block[]}], tools?[{name, description, inputSchema}], maxTokens, temperature?}`. Blocks are `text`, `tool_use {id, name, input}` and `tool_result {toolUseId, content}`; anything else is `400 INVALID_REQUEST`. `maxTokens` is required and must be a positive integer within `agentstore.llm.max-output-tokens` (default 32,000), so every call has a bounded reservation. Unknown top-level keys are rejected, not forwarded.

Response: `{model, content: block[] (text | tool_use), stopReason: end_turn | max_tokens | tool_use | other, usage: {inputTokens, outputTokens}}`. The adapters translate to and from Anthropic `/v1/messages` and OpenAI `/v1/chat/completions` (with `store: false`). Provider retention flags live in `llm_model.provider_retains_data`.

### 4.3 Call flow and budget

1. Load the run; reject if `manifest.llm` is absent (`LLM_NOT_DECLARED`), `mode` is `byok_direct` (`USE_DIRECT_PROVIDER`), or the request is invalid (`INVALID_REQUEST`).
2. Check `model` is in `manifest.llm.models` **and** an enabled `llm_model` row (`MODEL_NOT_ALLOWED`).
3. Dispatch on the run's `llm_mode`: mock and replay skip steps 4–8 (§6).
4. Resolve the key. `platform`: the platform key for the model's provider. `byok_proxy`: the developer org's credential; none stored gives `LLM_KEY_MISSING`. [I7]
5. **Reserve.** Reservation = estimated input tokens + `maxTokens`, where the estimate is the canonical request's UTF-8 byte length divided by 3, rounded up. One conditional statement makes it atomic:
   `UPDATE run SET llm_tokens_reserved = llm_tokens_reserved + :r WHERE id = :id AND llm_tokens_used + llm_tokens_reserved + :r <= :max`.
   Zero rows updated means the reservation does not fit `maxTokensPerRun`: write a `BUDGET_REJECTED` row and return `402 BUDGET_EXCEEDED` (§7, the same status and code WS1's `/usage` uses for the cost cap). Concurrent calls therefore cannot oversubscribe the cap. The reservation runs in its own short transaction; no database transaction is held across the provider call. [I4]
6. Call the provider through `ProviderClient`.
7. **Settle** in a second transaction: `llm_tokens_reserved -= r`, `llm_tokens_used += actual input + output`. On any failure the reservation is released and `used` is unchanged. Actual usage can exceed the estimate; the cap is enforced at admission, so the overshoot is bounded by one call's estimation error and the next call is rejected.
8. Write the `llm_call` row (tokens, latency, cost), record blobs for SANDBOX runs (§6), record the run step.

**Cost.** `cost = in/1e6 × input price + out/1e6 × output price`, scale 4, in the model's currency. `PLATFORM` calls record it; `BYOK_PROXY` calls record tokens with cost 0 (the developer's provider bill, not ours); `MOCK` and `REPLAY` record 0. LLM cost is recorded, not deducted from the run's buyer-facing `cost_cap`; earnings deduction is WS10. [I5]

**Context.** `GET /runtime/v1/context` gains `limits.llm {mode, models, tokensRemaining}` when the manifest declares `llm`, so agents can plan against the budget. [I15]

### 4.4 Resilience

`ProviderClient` wraps each adapter: connect timeout 5 s, read timeout 120 s (non-streaming), **one retry** on 429, 5xx or an I/O error after a short backoff (honouring `Retry-After`, capped at 2 s), and a per-provider circuit breaker (resilience4j `circuit-breaker`, sliding window 20 calls, 50% failure rate over at least 10 calls, 30 s open). 5xx, timeouts, I/O errors and 429 count as failures; other 4xx do not. Provider base URLs are configuration, which is also how tests aim the gateway at a stub server. [I9]

**Provider response bodies are never forwarded** and never stored in `llm_call`. Only the HTTP status is logged, since bodies can echo request data.

### 4.5 Keys never leak

The developer's or platform's key exists only in memory for the outgoing request header. It is never placed in a response, an exception message, a log line, a recorded blob or a run step. Logging of outbound requests is limited to provider, model, status and latency.

## 5. BYOK

**Who may:** only verified developer orgs. Unverified: `403 BYOK_NOT_VERIFIED`, at key upload and at manifest submit.

**Keys API** (developer JWT, the developer org's admins; follows `DeveloperApiKeyController` conventions; paths under `/api/v1/developer/llm-keys`) [I14]:
- `PUT /{provider}` `{apiKey}`: stores or replaces the credential. Checks only that the value is non-blank, has no whitespace and is at most 512 characters. It does not test the key against the provider.
- `GET`: `[{provider, last4, createdAt}]`. The key is never returned.
- `DELETE /{provider}`. Later `byok_proxy` calls get `LLM_KEY_MISSING`.
- Developer API keys stay excluded by the existing `ApiKeyScope` allowlist.

**`byok_proxy`** (recommended): the call goes through the gateway with the developer org's key. The developer org is `agent.developerOrg` of the run's agent version. The model must still be an enabled `llm_model` row (that supplies provider and the token metering).

**`byok_direct`**: the agent calls the provider itself. `RunLauncher` adds `AGENT_SECRET_<PROVIDER>_API_KEY` (for example `AGENT_SECRET_ANTHROPIC_API_KEY`) to the run's environment from the developer org's credential, next to the existing `AGENT_SECRET_*` connection secrets. A missing credential fails the launch with `LLM_KEY_MISSING`. `POST /llm/chat` on such a run returns `400 USE_DIRECT_PROVIDER`. The platform sees only egress; the manifest's allowlist rule (§2) is what opens the provider host. The parent spec's `AGENTSTORE_SECRET_…` name is superseded by the prefix the launcher already uses. [I11]

## 6. Mock, replay and recording

Mock and replay apply only to non-production runs. A DB check (`llm_mode IS NULL OR mode <> 'PRODUCTION'`) guarantees a production run can never be mocked.

- **Request hash.** SHA-256 of the canonicalised request (`model`, `messages`, `tools`, `maxTokens`, `temperature`, keys sorted).
- **MOCK.** `run.llm_mock_json` maps hash to a canned response, plus an optional `default`: `{"responses": {"<hash>": {…}}, "default": {…}}`. WS6 profiles will fill it. Lookup order is exact hash, then `default`, then a fixed generic stub reply, so a mock run never fails just because it is missing an entry.
- **REPLAY.** The gateway serves recorded responses from `replay_run_id` by hash, consuming duplicates in recorded order. The n-th call with a given hash in the replaying run gets the n-th recorded `OK` call with that hash, ordered by `created_at, id`; `replay_ordinal` records n and the unique index makes a concurrent duplicate retry with the next n. The replay source must be a `SANDBOX` run; who may point at whose run is enforced by WS6 when it sets the column. [I12]
  - A miss (no recorded call for that ordinal) returns `409 REPLAY_MISS`, carrying the hash. It never falls through to a paid call, so replay stays deterministic and free, and the miss shows up in the behaviour report later (`llm_call.status = REPLAY_MISS`).
  - An expired recording returns `410 REPLAY_EXPIRED`.
- **Recording.** Real and replayed calls in `SANDBOX` runs store request and response blobs in the object store (`llm/{runId}/{callId}/request.json` and `response.json`). Mock calls don't. Production, trial and review runs never store prompts. Recordings expire after 14 days (`agentstore.llm.recording-ttl`, from the parent spec); an hourly ShedLock-guarded job deletes expired blobs and stamps `recording_purged_at`, and replay treats either an elapsed `recording_expires_at` or a purged recording as expired. [I13]
- **Metering.** Mock and replay calls write `llm_call` rows with cost 0 and never reserve budget. Replay copies the recorded token counts onto its row for the behaviour report; they do not count toward `llm_tokens_used`.

## 7. Error contract

`RuntimeApiException` problem bodies, same style as WS1 and WS2. Provider error bodies are never forwarded verbatim. [I8]

| Status / code | When |
|---|---|
| 400 `LLM_NOT_DECLARED` | the manifest has no `llm` block |
| 400 `MODEL_NOT_ALLOWED` | model not in `llm.models`, or not an enabled `llm_model` |
| 400 `INVALID_REQUEST` | malformed request (missing `maxTokens`, unknown block type, unknown key) |
| 400 `USE_DIRECT_PROVIDER` | chat on a `byok_direct` run |
| 400 `LLM_KEY_MISSING` | `byok_proxy` (or a `byok_direct` launch) with no stored credential |
| 403 `BYOK_NOT_VERIFIED` | org un-verified (key PUT, BYOK manifest submit) |
| 402 `BUDGET_EXCEEDED` | the reservation does not fit `maxTokensPerRun`; a `BUDGET_REJECTED` row is written. The runtime API already reports the buyer cost cap separately, so this is only the token budget |
| 409 `REPLAY_MISS` | no recorded response for this hash and ordinal; body carries the hash |
| 410 `REPLAY_EXPIRED` | the recording has expired or been purged |
| 502 `LLM_PROVIDER_ERROR` | provider 5xx or a provider 4xx (for example a rejected key), or an unparseable reply, after the one retry |
| 503 `LLM_PROVIDER_UNAVAILABLE` | circuit open, provider 429 after the retry, or the platform key for the provider is not configured |
| 504 `LLM_PROVIDER_TIMEOUT` | the provider timed out after the retry |

Failed calls still write an `llm_call` row (`ERROR` with our `error_code`, or `BUDGET_REJECTED`) and release their reservation. Requests rejected before any metering (400s, 403) write no row. `RunStepRecorder` records tokens too, not just `calls: 1`: each served call records `{block: "llm", calls: 1, tokens, inputTokens, outputTokens, model, source, status}` as the `LLM_CALL` step. `run_step` has no `block` column until WS1b, so the block name rides in the detail JSON. [I15]

## 8. Testing

Backend only, using a stub provider HTTP server (the JDK's `com.sun.net.httpserver.HttpServer`, so no new test dependency) and Testcontainers with `AbstractStorageIntegrationTest` for blobs. RED before GREEN for each task. [I16]
- Unit: request normalizer and hash (key order, whitespace, defaults); both provider adapters' translation; reservation arithmetic; mock lookup order.
- Integration:
  - a platform call is metered (row, tokens, cost, step);
  - an unlisted model gives 400;
  - concurrent calls cannot oversubscribe `maxTokensPerRun`;
  - an unverified org cannot PUT a key or submit a BYOK manifest;
  - `byok_proxy` sends the developer's key (the stub sees it);
  - `byok_direct` injects `AGENT_SECRET_…` and chat gives 400;
  - a key never appears in any response, log line or recorded blob;
  - mock: hash hit, default, generic fallback; replay: hit, miss, expired, duplicates consumed in order; a production run cannot carry `llm_mode`;
  - a 429 then success on retry, and an open circuit gives 503;
  - one acceptance test for the whole flow.
- The WS1 test that currently asserts 501 for `/llm/chat` (`RuntimeResourceEndpointsTest.theLlmEndpointIsNotImplementedYet`) is updated to the new behaviour, `UnavailableLlmBackend` is deleted, `contracts/runtime-v1.yaml` is regenerated and `RuntimeContractTest` updated. `ONTOLOGY.md` and the CLI schema copy are updated.

## 9. Inferences to confirm

Sections 1–3 were not available, so these were decided from the parent spec, the code, and section 4. Say which to change.

| # | Inference | Basis |
|---|---|---|
| I1 | Sections 1–3 reconstructed; scope taken from the parent WS3 outline plus section 4's out-of-scope list. | Parent spec §5 WS3 |
| I2 | Manifest `llm` has `provider` (byok only) so `byok_direct` knows which key and which host to allow; no `purpose` field yet. | Parent lists no provider field; code has no `llm` block |
| I3 | No verification concept exists in the code. WS3 adds only a nullable `org.developer_verified_at`; WS7 owns setting it; tests set it directly. | `Org` has no such field; KYC is out of scope |
| I4 | Token budget: reservation = estimated input (bytes/3) + `maxTokensPerRun`-bounded `maxTokens`; `maxTokens` required; atomic conditional UPDATE; overshoot bounded by one call. | Section 4 says "reservation doesn't fit maxTokensPerRun"; mechanics not given |
| I5 | LLM cost is recorded on `llm_call` but not deducted from the buyer's run `cost_cap`; `BYOK_PROXY` cost is 0. | Section 4 puts earnings deduction in WS10; parent §8 Q1 unresolved |
| I6 | The normalized request/response shape (§4.2), including tool blocks. | Parent says "minimal normalization"; no shape given |
| I7 | Platform keys come from environment configuration (no secret manager exists); an unconfigured platform key is a 503, not a 400. | No secret manager in the repo |
| I8 | The 502/503/504 mapping (that table row was cut off); validation failures write no `llm_call`. `BUDGET_EXCEEDED` as 402 is confirmed: WS1's `/usage` already uses it. | Section 4's table was garbled |
| I9 | resilience4j `circuit-breaker` (parent named it) with the thresholds above; JDK `HttpServer` as the stub. Adds one free library. | Parent spec; no-paid-vendors rule |
| I10 | `llm_model` seeded disabled with zero prices, because real prices are not known here. | Prices change; operator decision |
| I11 | Env name `AGENT_SECRET_<PROVIDER>_API_KEY`; launch fails on a missing credential; injected in every mode (WS6 may substitute fake secrets in sandbox). | `RunLauncher` uses the `AGENT_SECRET_` prefix |
| I12 | Replay ordinal mechanism and unique index; the replay source must be a SANDBOX run; ownership checks left to WS6. | Section 4: "consuming duplicates in recorded order" |
| I13 | A small hourly retention job for recordings with a 14-day TTL. | Parent spec's 14-day TTL; 410 needs expiry |
| I14 | Key API paths, roles and light validation; no live key check against the provider. | Follows `DeveloperApiKeyController` |
| I15 | Steps carry `block: "llm"` inside detail JSON; `/context` gains `limits.llm`. | `run_step.block` arrives with WS1b; parent §4.2 lists an LLM token budget in context |
| I16 | The test list bullets that were cut off were completed as written in §8. | Section 4 was garbled |

## 10. Plan task order

1. Manifest `llm` (model, schema, validator rules, CLI resync).
2. Migrations, entities, repositories; org verification column; `run` columns and the production constraint.
3. `LlmRequestNormalizer`, canonical hash, `LlmBudget` (atomic reserve/settle).
4. Provider adapters and `ProviderClient` (retry, breaker, error mapping) against the stub server.
5. `GatewayLlmBackend`: platform mode end to end, metering, steps, error contract; delete `UnavailableLlmBackend`; update the 501 test.
6. BYOK: keys API, `byok_proxy`, submit-time verification and tiering, `byok_direct` injection and 400.
7. Mock and replay, recording blobs, the retention job.
8. `/context` LLM limits; contract regeneration and `RuntimeContractTest`.
9. Docs and ontology, the acceptance test, full-suite run.

## 11. Decisions settled (do not re-ask)

- Three modes: `platform`, `byok_proxy`, `byok_direct`; BYOK only for verified developer orgs.
- Mock and replay for non-production runs only, enforced by a DB check.
- A replay miss never falls through to a paid call.
- Prompts stored only for SANDBOX runs, 14-day TTL; never for production or buyer runs.
- Provider error bodies are never forwarded.
- No streaming in this slice.
