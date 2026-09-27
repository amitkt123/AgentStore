# WS3 — LLM Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task (the user chose inline execution, not subagent-driven). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Agents call LLMs through `POST /runtime/v1/llm/chat`; the platform enforces the manifest's model allowlist, meters tokens against a per-run budget that concurrent calls cannot oversubscribe, attributes every call to a run, lets verified developers bring their own key, and serves non-production runs from canned or recorded responses.

**Architecture:** A new `llm` package holds `GatewayLlmBackend` (replacing the 501 `UnavailableLlmBackend` behind the existing `LlmBackend` seam) and its units: `LlmRequestNormalizer` (validate, canonicalise, hash), `LlmBudget` (atomic reserve/settle as single conditional `UPDATE`s on `run`), `LlmProvider` adapters for Anthropic and OpenAI (one HTTP call each, no retry), `ProviderClient` (one retry, per-provider resilience4j circuit breaker, error mapping), `ByokKeyService` (AES-GCM-encrypted developer keys), and the mock/replay/recording pieces. No database transaction is ever held across a provider call.

**Tech Stack:** Java 21, Spring Boot 3.3.4, JPA/Hibernate (`ddl-auto: validate`), Flyway (V29, V30), Postgres 16, JDK `java.net.http.HttpClient`, resilience4j `circuit-breaker` 2.2.0 (new, free OSS), Jackson, ShedLock 5.16.0, JUnit 5 + Testcontainers, JDK `com.sun.net.httpserver.HttpServer` as the provider stub, Node/vitest (CLI schema resync).

**Spec:** `docs/superpowers/specs/2026-09-26-ws3-llm-gateway-design.md` (commit 5a8b3f1; §6–§8, the former "section 4", approved 2026-09-26; §1–§5 were reconstructed and the inferences `[I1]`–`[I16]` in its §9 are treated as accepted because the user asked for this plan). Where it and the WS3 section of `2026-09-23-agent-store-developer-platform-and-sandbox-design.md` differ, the WS3 spec wins.

## Global Constraints

- Work on branch `feat/ws3-llm-gateway`, cut from `docs/ws3-design` (so the spec and this plan ride along). Nothing is pushed until the user says so.
- **No AI attribution in commits.** Never add a `Co-Authored-By` trailer or any Claude/Anthropic reference to a commit message or PR body (the user's global rule overrides the harness default). Plain messages such as `feat(llm): …`.
- Backend tests: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test [-Dtest=ClassName]`. Docker runs through Colima (`colima start` if `docker ps` fails). CLI: `cd cli && npm ci` if `node_modules` is missing, then `npx vitest run`. Worktree-isolated sessions refuse compound bash commands containing `git`: run git commands one per call.
- Last existing migration is `V28__run_output.sql`; new ones are **V29** and **V30**. `spring.jpa.hibernate.ddl-auto` is `validate`, so every migration must match the entities exactly. `open-in-view` is `false` (no lazy loading outside a transaction).
- Manifest `llm` block: `mode` ∈ `platform | byok_proxy | byok_direct`; `models` 1 or more distinct ids; `maxTokensPerRun` integer, 1 up to `agentstore.llm.max-tokens-per-run-limit` (default 5,000,000); `provider` ∈ `anthropic | openai`, required exactly when `mode` is `byok_*`, forbidden for `platform`; `byok_direct` requires the provider's API host (`api.anthropic.com`, `api.openai.com`) in `network.allowlist`. A manifest without `llm` stays valid (its agents get `400 LLM_NOT_DECLARED`). `modelRequirements` is ignored. Model availability is checked per call, never at submit. BYOK of either flavour makes the version Tier B.
- Request: `{model, system?, messages[{role: user|assistant, content: string | block[]}], tools?[{name, description, inputSchema}], maxTokens, temperature?}`; blocks are `text`, `tool_use {id, name, input}`, `tool_result {toolUseId, content}`; anything else is `400 INVALID_REQUEST`. `maxTokens` is required, a positive integer, at most `agentstore.llm.max-output-tokens` (default 32,000). Unknown top-level keys are rejected, not forwarded. Response: `{model, content: block[] (text | tool_use), stopReason: end_turn | max_tokens | tool_use | other, usage: {inputTokens, outputTokens}}`. OpenAI calls send `store: false`.
- Budget: reservation = estimated input tokens (canonical request UTF-8 byte length ÷ 3, rounded up) + `maxTokens`; one atomic conditional `UPDATE run SET llm_tokens_reserved = llm_tokens_reserved + :r WHERE id = :id AND llm_tokens_used + llm_tokens_reserved + :r <= :max`; zero rows → `BUDGET_REJECTED` row and `402 BUDGET_EXCEEDED`. Settlement is a second short transaction (`reserved -= r`, `used += actual`); on any failure the reservation is released and `used` is unchanged. Cost: `in/1e6 × input price + out/1e6 × output price`, scale 4; recorded only for `PLATFORM` calls (`BYOK_PROXY`, `MOCK`, `REPLAY` record 0); never deducted from the buyer's `cost_cap`.
- Resilience: connect timeout 5 s, read timeout 120 s, **one retry** on 429, 5xx, timeout or I/O error after a short backoff (honour `Retry-After`, capped at 2 s), per-provider circuit breaker (window 20 calls, 50 % failure rate over at least 10 calls, 30 s open); 5xx, timeouts, I/O errors and 429 count as failures, other 4xx do not. Provider base URLs are configuration (that is how tests aim at the stub).
- **Provider response bodies are never forwarded and never stored.** Keys (platform or developer) exist only in memory for the outgoing header: never in a response, exception message, log line, recorded blob or run step. Outbound logging is limited to provider, model, status and latency.
- Error contract (problem+json via `RuntimeApiException`): 400 `LLM_NOT_DECLARED`, `MODEL_NOT_ALLOWED`, `INVALID_REQUEST`, `USE_DIRECT_PROVIDER`, `LLM_KEY_MISSING`; 403 `BYOK_NOT_VERIFIED`; 402 `BUDGET_EXCEEDED`; 409 `REPLAY_MISS` (body carries the hash); 410 `REPLAY_EXPIRED`; 502 `LLM_PROVIDER_ERROR`; 503 `LLM_PROVIDER_UNAVAILABLE` (circuit open, 429 after the retry, or platform key not configured); 504 `LLM_PROVIDER_TIMEOUT`. Failed calls write an `llm_call` row (`ERROR` with our `error_code`, or `BUDGET_REJECTED`) and release their reservation; requests rejected before any metering (400s, 403) write no row.
- Mock and replay apply only to non-production runs (DB `CHECK (llm_mode IS NULL OR mode <> 'PRODUCTION')`). A replay miss never falls through to a paid call. Recordings (`llm/{runId}/{callId}/request.json|response.json`) exist only for `SANDBOX` runs and expire after 14 days (`agentstore.llm.recording-ttl`); production, trial and review runs never store prompts.
- `byok_direct`: `RunLauncher` injects `AGENT_SECRET_<PROVIDER>_API_KEY` (e.g. `AGENT_SECRET_ANTHROPIC_API_KEY`) from the developer org's credential; a missing credential fails the launch with `LLM_KEY_MISSING`; `POST /llm/chat` on such a run is `400 USE_DIRECT_PROVIDER`.
- **The existing tests must keep passing.** Only `RuntimeResourceEndpointsTest.theLlmEndpointIsNotImplementedYet` (asserts the old 501) is edited, in Task 6; `RuntimeContractTest` gains one test and `contracts/runtime-v1.yaml` is regenerated, never hand-edited (Task 9). Never edit any other existing test to make it pass.
- No paid third parties: resilience4j is the only new dependency and it is free and open source.
- Out of scope (do not build): streaming/SSE; the provider-native `raw()` passthrough; the "LLM keys" settings page and per-run LLM usage table (UI); deducting LLM cost from developer earnings (WS10); the per-developer monthly sandbox LLM quota (WS6); the verification/KYC workflow that sets `org.developer_verified_at` (WS7); sandbox profiles that fill `run.llm_mode`, `replay_run_id` and `llm_mock_json` (WS6; WS3 adds the columns and honours them, nothing in WS3 sets them); surfacing `display_name`/`provider_retains_data` in listings.

## Deviations from the spec (small, called out so the user can veto)

1. **Request hash covers exactly the fields §6 lists and omits `system`.** Two requests that differ only in the system prompt therefore hash the same (mock lookups cannot tell them apart; replay serves them in recorded order). This looks like an oversight in §6, but the approved text is followed. The list lives in one method (`LlmRequestNormalizer.canonical`, the `includeSystem` flag), so adding `system` later is a one-line change. **The token estimate does include `system`**, otherwise a large system prompt would be under-reserved. *Please confirm or ask for `system` to be hashed.*
2. **Task order differs from spec §10:** BYOK key storage, the keys API, submit-time verification and tiering (Task 5) come *before* the gateway (Task 6), because the gateway's key resolver depends on the key store; `byok_direct` launcher injection is Task 7. The spec's nine tasks become ten here.
3. **`llm_call.request_hash` is `VARCHAR(64)`, not `CHAR(64)`**, because Hibernate's `validate` rejects `bpchar` against a `String` attribute; the value is always 64 hex characters.
4. **`run.llm_tokens_used` / `llm_tokens_reserved` are read-only on the JPA entity** (`insertable = false, updatable = false`) and only ever changed by native atomic statements. Without this, any code path that loads a `Run` without a lock and later calls `save` would overwrite the counters with stale values. A test pins it.
5. **Test models use their own ids** (`test-claude`, `test-gpt`, `test-disabled`) upserted by `LlmFixtures`; the two migration-seeded rows are never modified, so the "seeded disabled with zero prices" test stays valid in a shared database.
6. **Seed values the spec leaves open:** `provider_retains_data = TRUE` (unknown, so the conservative answer until an operator says otherwise) and `currency = 'USD'`; both are operator-editable columns.
7. **Error mapping details the spec leaves open:** a provider I/O error after the retry is `502 LLM_PROVIDER_ERROR`; an unparseable 200 reply and a non-429 provider 4xx are not retried and do not count towards the breaker; a key that cannot be resolved for a *platform* call (unconfigured) writes an `ERROR` row (it is a 5xx), while `LLM_KEY_MISSING` (a 400) writes none.
8. **Recording failure never fails a served call:** if the object store is down after a successful provider call, the response is returned, a warning is logged, and the row has no recording keys (a later replay of it is a `REPLAY_MISS`).
9. **Purging a recording nulls `request_key`/`response_key` as well as stamping `recording_purged_at`** (as WS2's dataset purge does).
10. **`DELETE /api/v1/developer/llm-keys/{provider}` is idempotent (`204` even when no key exists)**, and is allowed for unverified orgs (clean-up); only `PUT` needs verification. `temperature` is validated only as a finite number ≥ 0 (the providers enforce their own ranges).
11. **A malformed canned mock entry is treated as absent** (falls through to `default`, then the generic stub) so a mock run never fails on a bad entry.
12. **`ManifestValidator` gains a `long` constructor** (`agentstore.llm.max-tokens-per-run-limit`) beside the no-arg one that existing tests use.
13. **OpenAI requests use `max_completion_tokens`** (accepted by current chat-completions models; `max_tokens` is deprecated there).

## Review Focus

The inputs and conditions the spec implies but no happy path exercises, most likely to bite first. Each has a pinning test in the task named.

1. **A failed or concurrent call leaking or oversubscribing the token budget.** Provider 5xx/429/timeout/open circuit must release the reservation and leave `used` unchanged; concurrent calls must never reserve more than `maxTokensPerRun`; a lone call bigger than the whole budget is a clean 402 with no provider request. → Task 3 (`LlmBudgetTest`, 20 threads), Task 6 (`GatewayBudgetTest`: `aFailedCallReleasesItsReservation`, `concurrentCallsCannotOversubscribeTheBudget`, `aCallLargerThanTheWholeBudgetIsRejected`, breaker-open release in `GatewayErrorContractTest`).
2. **A key or provider body leaking.** A provider that answers 401 with a body echoing the key and the prompt must not surface either in the HTTP response, the `llm_call` row, the run step or the logs; a stored key must not be readable from the database or from any response; a tiny key must not be fully echoed as `last4`. → Task 4 (`AnthropicProviderTest` exception messages), Task 5 (`ByokKeyApiTest`), Task 6 (`GatewayKeyLeakTest`).
3. **Odd request shapes.** `maxTokens` as `0`, `-1`, `"100"`, `10.5`, `true`, above the cap or missing; `content` as a string versus a block list (same hash); empty `messages`; unknown roles, block types and keys; `tool_result` without `content`; multi-byte text (the estimate counts bytes, not characters). → Task 3 (`LlmRequestNormalizerTest`).
4. **Replay determinism.** Duplicates consumed in recorded order, the n+1-th identical request is a `409 REPLAY_MISS` (never a paid call), concurrent duplicates never share an ordinal, an expired or purged recording is `410`, a source run that never recorded (PRODUCTION) is a miss, and a `PRODUCTION` run can never carry `llm_mode`. → Task 2 (constraint), Task 8 (`GatewayReplayTest`).
5. **Prompts stored only for SANDBOX runs, and purged on schedule.** PRODUCTION, TRIAL and REVIEW runs write no blobs and no recording keys; MOCK never records; the retention job removes expired blobs once and is idempotent; an unexpired recording survives it. → Task 8 (`GatewayRecordingTest`, `LlmRecordingRetentionTest`).

## File Structure

**Backend, new** (`backend/src/main/java/com/agentstore/platform/…`)
- `manifest/LlmMode.java`, `LlmVendor.java`, `LlmDecl.java`, `LlmManifestRules.java` — manifest model and cross-field rules.
- `llm/LlmProperties.java` — all `agentstore.llm.*` configuration in one place.
- `llm/LlmRequest.java`, `LlmResponse.java` — normalized request/response records (with `toMap`/`fromMap`).
- `llm/LlmRequestNormalizer.java` — validation, canonical form, hash, token estimate.
- `llm/LlmBudget.java` — atomic reserve/settle/release.
- `llm/LlmProvider.java`, `ProviderCallException.java`, `ProviderHttp.java`, `AnthropicProvider.java`, `OpenAiProvider.java`, `ProviderClient.java` — adapters, retry, breaker, error mapping.
- `llm/LlmSource.java`, `LlmCallStatus.java`, `LlmModel.java`, `LlmModelRepository.java`, `LlmCall.java`, `LlmCallRepository.java`, `ByokCredential.java`, `ByokCredentialRepository.java` — persistence.
- `llm/LlmApiException.java`, `LlmApiErrors.java`, `ByokKeyService.java`, `LlmKeyController.java`, `LlmKeyMissingException.java` — BYOK keys API and launcher hook.
- `llm/LlmKeyResolver.java`, `LlmCost.java`, `RunLlmContext.java`, `RunLlmContextLoader.java`, `LlmCallLog.java`, `GatewayLlmBackend.java` — the gateway.
- `llm/LlmRecorder.java`, `MockResponder.java`, `ReplayResponder.java`, `LlmRecordingRetentionService.java`, `LlmRecordingRetentionJob.java` — mock, replay, recording, retention.
- `execution/RunLlmMode.java`.
- `db/migration/V29__llm_gateway.sql`, `V30__run_llm_columns.sql`.

**Backend, modified:** `manifest/Manifest.java`, `ManifestParser.java`, `ManifestValidator.java`, `resources/manifest/manifest-schema.json`; `identity/Org.java`; `execution/Run.java`, `RunRepository.java`, `RunLauncher.java`; `publishing/PublishingService.java`; `runtime/RuntimeLlmController.java`, `ContextResponse.java`, `RuntimeContextController.java`; `resources/application.yml`; `pom.xml`. **Deleted:** `runtime/UnavailableLlmBackend.java`.

**Repo root:** `contracts/runtime-v1.yaml` (regenerated), `ONTOLOGY.md`. **CLI:** `cli/src/schema/manifest-schema.json` (resynced).

**Backend tests, new** (`backend/src/test/java/com/agentstore/platform/…`): `manifest/LlmManifestTest`; `llm/{LlmFixtures,StubProviderServer,AbstractLlmIntegrationTest,LlmDataModelTest,LlmRequestNormalizerTest,LlmBudgetTest,AnthropicProviderTest,OpenAiProviderTest,ProviderClientTest,ByokKeyApiTest,ByokSubmitTest,LlmCostTest,LlmKeyResolverTest,GatewayPlatformCallTest,GatewayBudgetTest,GatewayErrorContractTest,GatewayByokProxyTest,GatewayKeyLeakTest,ByokDirectLaunchTest,MockResponderTest,GatewayMockTest,GatewayReplayTest,GatewayRecordingTest,LlmRecordingRetentionTest,RuntimeContextLlmLimitsTest,LlmGatewayAcceptanceTest}`; `identity/LlmKeysAreNotKeyReachableTest`. **Test resources, new:** `src/test/resources/manifest/valid-llm-platform.json`, `valid-llm-small-budget.json`, `valid-llm-byok-proxy.json`, `valid-llm-byok-direct.json`.

---

### Task 1: Manifest `llm`

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/manifest/LlmMode.java`, `LlmVendor.java`, `LlmDecl.java`, `LlmManifestRules.java`
- Create: `backend/src/test/resources/manifest/valid-llm-platform.json`, `valid-llm-small-budget.json`, `valid-llm-byok-proxy.json`, `valid-llm-byok-direct.json`
- Test: `backend/src/test/java/com/agentstore/platform/manifest/LlmManifestTest.java`
- Modify: `manifest/Manifest.java`, `manifest/ManifestParser.java`, `manifest/ManifestValidator.java`, `backend/src/main/resources/manifest/manifest-schema.json`, `cli/src/schema/manifest-schema.json` (via script)

**Interfaces:**
- Consumes: existing `Manifest`, `ManifestParser`, `ManifestValidator`, `ResourceManifestRules`.
- Produces (later tasks rely on these exact names):
  - `enum LlmMode { PLATFORM, BYOK_PROXY, BYOK_DIRECT }` with `String wire()`, `boolean isByok()`, `static Optional<LlmMode> fromWire(String)`.
  - `enum LlmVendor { ANTHROPIC, OPENAI }` with `String wire()` (`"anthropic"`/`"openai"`), `String apiHost()`, `static Optional<LlmVendor> fromWire(String)`.
  - `record LlmDecl(LlmMode mode, List<String> models, long maxTokensPerRun, LlmVendor provider)`; `provider` is null for `platform`.
  - `Manifest.llm()` (`LlmDecl`, null when the manifest has no `llm` key); the 9- and 10-argument `Manifest` constructors keep working.
  - `new ManifestValidator()` still works; `new ManifestValidator(long maxTokensPerRunLimit)` is the Spring constructor.

- [ ] **Step 1: Commit this plan, then cut the working branch**

The plan and the spec ride along on `docs/ws3-design`. If this plan file is not committed yet, commit it first (one git command per call in worktree-isolated sessions):

```bash
git add docs/superpowers/plans/2026-09-27-ws3-llm-gateway.md
git commit -m "docs: WS3 LLM gateway implementation plan"
git checkout -b feat/ws3-llm-gateway
```

- [ ] **Step 2: Add the four test manifests**

`backend/src/test/resources/manifest/valid-llm-platform.json`:

```json
{
  "name": "LLM Platform Agent",
  "version": "1.0.0",
  "scopes": [
    { "type": "READ", "resource": "bank_statement", "description": "Read statement lines" }
  ],
  "network": { "allowlist": [] },
  "costEnvelope": { "currency": "INR", "maxPerRun": 500.00, "pricingUnit": "METERED", "unitPrice": 2.00, "unitLabel": "reconciled_transaction" },
  "approvalGates": [],
  "dataClasses": ["BANK_STATEMENT"],
  "llm": { "mode": "platform", "models": ["test-claude", "test-gpt", "test-disabled"], "maxTokensPerRun": 100000 }
}
```

`valid-llm-small-budget.json` (same, but a 1,000-token budget and one model):

```json
{
  "name": "LLM Small Budget Agent",
  "version": "1.0.0",
  "scopes": [
    { "type": "READ", "resource": "bank_statement", "description": "Read statement lines" }
  ],
  "network": { "allowlist": [] },
  "costEnvelope": { "currency": "INR", "maxPerRun": 500.00, "pricingUnit": "METERED", "unitPrice": 2.00, "unitLabel": "reconciled_transaction" },
  "approvalGates": [],
  "dataClasses": ["BANK_STATEMENT"],
  "llm": { "mode": "platform", "models": ["test-claude"], "maxTokensPerRun": 1000 }
}
```

`valid-llm-byok-proxy.json`:

```json
{
  "name": "LLM BYOK Proxy Agent",
  "version": "1.0.0",
  "scopes": [
    { "type": "READ", "resource": "bank_statement", "description": "Read statement lines" }
  ],
  "network": { "allowlist": [] },
  "costEnvelope": { "currency": "INR", "maxPerRun": 500.00, "pricingUnit": "METERED", "unitPrice": 2.00, "unitLabel": "reconciled_transaction" },
  "approvalGates": [],
  "dataClasses": ["BANK_STATEMENT"],
  "llm": { "mode": "byok_proxy", "provider": "anthropic", "models": ["test-claude"], "maxTokensPerRun": 100000 }
}
```

`valid-llm-byok-direct.json`:

```json
{
  "name": "LLM BYOK Direct Agent",
  "version": "1.0.0",
  "scopes": [
    { "type": "READ", "resource": "bank_statement", "description": "Read statement lines" }
  ],
  "network": { "allowlist": ["api.anthropic.com"] },
  "costEnvelope": { "currency": "INR", "maxPerRun": 500.00, "pricingUnit": "METERED", "unitPrice": 2.00, "unitLabel": "reconciled_transaction" },
  "approvalGates": [],
  "dataClasses": ["BANK_STATEMENT"],
  "llm": { "mode": "byok_direct", "provider": "anthropic", "models": ["test-claude"], "maxTokensPerRun": 100000 }
}
```

- [ ] **Step 3: Write the failing test**

`backend/src/test/java/com/agentstore/platform/manifest/LlmManifestTest.java`:

```java
package com.agentstore.platform.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LlmManifestTest {

    final ObjectMapper mapper = new ObjectMapper();
    final ManifestParser parser = new ManifestParser();
    final ManifestValidator validator = new ManifestValidator();

    String read(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/manifest/" + name));
    }

    /** valid-llm-platform.json with its llm block replaced, or removed when {@code llmJson} is null. */
    String withLlm(String llmJson) throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(read("valid-llm-platform.json"));
        if (llmJson == null) {
            root.remove("llm");
        } else {
            root.set("llm", mapper.readTree(llmJson));
        }
        return root.toString();
    }

    String withLlmAndAllowlist(String llmJson, String... hosts) throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(withLlm(llmJson));
        var allowlist = root.putObject("network").putArray("allowlist");
        for (String host : hosts) {
            allowlist.add(host);
        }
        return root.toString();
    }

    @Test
    void parsesThePlatformBlock() throws Exception {
        Manifest manifest = parser.parse(read("valid-llm-platform.json"));

        assertThat(manifest.llm().mode()).isEqualTo(LlmMode.PLATFORM);
        assertThat(manifest.llm().models()).containsExactly("test-claude", "test-gpt", "test-disabled");
        assertThat(manifest.llm().maxTokensPerRun()).isEqualTo(100_000L);
        assertThat(manifest.llm().provider()).isNull();
        assertThat(validator.validate(read("valid-llm-platform.json")).errors()).isEmpty();
    }

    @Test
    void parsesByokBlocks() throws Exception {
        Manifest proxy = parser.parse(read("valid-llm-byok-proxy.json"));
        Manifest direct = parser.parse(read("valid-llm-byok-direct.json"));

        assertThat(proxy.llm().mode()).isEqualTo(LlmMode.BYOK_PROXY);
        assertThat(proxy.llm().provider()).isEqualTo(LlmVendor.ANTHROPIC);
        assertThat(direct.llm().mode()).isEqualTo(LlmMode.BYOK_DIRECT);
        assertThat(validator.validate(read("valid-llm-byok-proxy.json")).errors()).isEmpty();
        assertThat(validator.validate(read("valid-llm-byok-direct.json")).errors()).isEmpty();
        assertThat(LlmMode.BYOK_PROXY.isByok()).isTrue();
        assertThat(LlmMode.PLATFORM.isByok()).isFalse();
    }

    @Test
    void aManifestWithoutLlmStaysValidAndHasNoLlm() throws Exception {
        assertThat(parser.parse(withLlm(null)).llm()).isNull();
        assertThat(validator.validate(withLlm(null)).valid()).isTrue();
        assertThat(parser.parse(read("valid-tier-a.json")).llm()).isNull();
    }

    @Test
    void theProviderIsRequiredExactlyForByokModes() throws Exception {
        assertThat(validator.validate(withLlm("""
                {"mode":"platform","provider":"anthropic","models":["m"],"maxTokensPerRun":10}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"byok_proxy","models":["m"],"maxTokensPerRun":10}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"byok_direct","models":["m"],"maxTokensPerRun":10}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"byok_proxy","provider":"google","models":["m"],"maxTokensPerRun":10}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"byok_proxy","provider":"openai","models":["m"],"maxTokensPerRun":10}""")).valid()).isTrue();
    }

    @Test
    void modelsMustBeANonEmptyListOfDistinctIds() throws Exception {
        assertThat(validator.validate(withLlm("""
                {"mode":"platform","models":[],"maxTokensPerRun":10}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"platform","models":["a","a"],"maxTokensPerRun":10}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"platform","models":[""],"maxTokensPerRun":10}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"platform","maxTokensPerRun":10}""")).valid()).isFalse();
    }

    @Test
    void maxTokensPerRunIsAPositiveIntegerWithinThePlatformLimit() throws Exception {
        assertThat(validator.validate(withLlm("""
                {"mode":"platform","models":["m"],"maxTokensPerRun":0}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"platform","models":["m"],"maxTokensPerRun":10.5}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"platform","models":["m"]}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"platform","models":["m"],"maxTokensPerRun":5000000}""")).valid()).isTrue();

        var tooBig = validator.validate(withLlm("""
                {"mode":"platform","models":["m"],"maxTokensPerRun":5000001}"""));
        assertThat(tooBig.valid()).isFalse();
        assertThat(tooBig.errors()).anyMatch(e -> e.contains("maxTokensPerRun") && e.contains("5000000"));

        ManifestValidator strict = new ManifestValidator(1000);
        assertThat(strict.validate(withLlm("""
                {"mode":"platform","models":["m"],"maxTokensPerRun":1000}""")).valid()).isTrue();
        assertThat(strict.validate(withLlm("""
                {"mode":"platform","models":["m"],"maxTokensPerRun":1001}""")).valid()).isFalse();
    }

    @Test
    void byokDirectNeedsTheProviderHostInTheNetworkAllowlist() throws Exception {
        String direct = """
                {"mode":"byok_direct","provider":"anthropic","models":["m"],"maxTokensPerRun":10}""";
        var missing = validator.validate(withLlm(direct));
        assertThat(missing.valid()).isFalse();
        assertThat(missing.errors()).anyMatch(e -> e.contains("api.anthropic.com"));

        assertThat(validator.validate(withLlmAndAllowlist(direct, "api.openai.com")).valid()).isFalse();
        assertThat(validator.validate(withLlmAndAllowlist(direct, "api.anthropic.com")).valid()).isTrue();

        String openai = """
                {"mode":"byok_direct","provider":"openai","models":["m"],"maxTokensPerRun":10}""";
        assertThat(validator.validate(withLlmAndAllowlist(openai, "api.anthropic.com")).valid()).isFalse();
        assertThat(validator.validate(withLlmAndAllowlist(openai, "api.openai.com")).valid()).isTrue();

        // byok_proxy needs no egress hole
        assertThat(validator.validate(withLlm("""
                {"mode":"byok_proxy","provider":"anthropic","models":["m"],"maxTokensPerRun":10}""")).valid()).isTrue();
    }

    @Test
    void unknownKeysInTheBlockAreRejected() throws Exception {
        assertThat(validator.validate(withLlm("""
                {"mode":"platform","models":["m"],"maxTokensPerRun":10,"stream":true}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"free_for_all","models":["m"],"maxTokensPerRun":10}""")).valid()).isFalse();
    }

    @Test
    void theOlderModelRequirementsFieldIsStillIgnored() throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(withLlm(null));
        root.putObject("modelRequirements").put("provider", "anthropic").put("model", "claude-sonnet-5");

        assertThat(validator.validate(root.toString()).valid()).isTrue();
        assertThat(parser.parse(root.toString()).llm()).isNull();
    }
}
```

- [ ] **Step 4: Run it to see it fail**

Run: `cd backend && mvn -q test -Dtest=LlmManifestTest`
Expected: FAIL to compile (`LlmMode`, `LlmVendor`, `Manifest.llm()` and `new ManifestValidator(long)` do not exist).

- [ ] **Step 5: Implement**

`backend/src/main/java/com/agentstore/platform/manifest/LlmMode.java`:

```java
package com.agentstore.platform.manifest;

import java.util.Optional;

/** How an agent reaches an LLM (manifest {@code llm.mode}). */
public enum LlmMode {
    PLATFORM("platform"), BYOK_PROXY("byok_proxy"), BYOK_DIRECT("byok_direct");

    private final String wire;

    LlmMode(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public boolean isByok() {
        return this != PLATFORM;
    }

    public static Optional<LlmMode> fromWire(String wire) {
        for (LlmMode mode : values()) {
            if (mode.wire.equals(wire)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
```

`LlmVendor.java`:

```java
package com.agentstore.platform.manifest;

import java.util.Optional;

/** The LLM providers the platform can call. {@code apiHost} is what {@code byok_direct} must allow-list. */
public enum LlmVendor {
    ANTHROPIC("anthropic", "api.anthropic.com"), OPENAI("openai", "api.openai.com");

    private final String wire;
    private final String apiHost;

    LlmVendor(String wire, String apiHost) {
        this.wire = wire;
        this.apiHost = apiHost;
    }

    public String wire() {
        return wire;
    }

    public String apiHost() {
        return apiHost;
    }

    public static Optional<LlmVendor> fromWire(String wire) {
        for (LlmVendor vendor : values()) {
            if (vendor.wire.equals(wire)) {
                return Optional.of(vendor);
            }
        }
        return Optional.empty();
    }
}
```

`LlmDecl.java`:

```java
package com.agentstore.platform.manifest;

import java.util.List;

/** The manifest's optional {@code llm} block. {@code provider} is null unless the mode is a {@code byok_*} one. */
public record LlmDecl(LlmMode mode, List<String> models, long maxTokensPerRun, LlmVendor provider) {
}
```

`LlmManifestRules.java`:

```java
package com.agentstore.platform.manifest;

import java.util.ArrayList;
import java.util.List;

/** Cross-field {@code llm} rules the JSON schema cannot express. They apply only when the manifest declares {@code llm}. */
public final class LlmManifestRules {

    private LlmManifestRules() {
    }

    public static List<String> check(Manifest manifest, long maxTokensPerRunLimit) {
        List<String> errors = new ArrayList<>();
        LlmDecl llm = manifest.llm();
        if (llm == null) {
            return errors;
        }
        if (llm.maxTokensPerRun() > maxTokensPerRunLimit) {
            errors.add("llm.maxTokensPerRun must be at most " + maxTokensPerRunLimit);
        }
        if (llm.mode() == LlmMode.BYOK_DIRECT && llm.provider() != null
                && !manifest.networkAllowlist().contains(llm.provider().apiHost())) {
            errors.add("llm.mode byok_direct requires " + llm.provider().apiHost() + " in network.allowlist");
        }
        return errors;
    }
}
```

Replace the whole of `manifest/Manifest.java` with:

```java
package com.agentstore.platform.manifest;

import java.util.List;
import java.util.Optional;

/**
 * {@code runtime} and {@code entrypoint} are required for bundles (BundleManifestRules) and null otherwise.
 * {@code resources} is null when the manifest has no {@code resources} key (untyped resources), never an empty
 * stand-in; {@code llm} is null when the manifest has no {@code llm} key (its agents cannot use the gateway).
 */
public record Manifest(String name, String version, List<Scope> scopes, List<String> networkAllowlist,
                        CostEnvelope costEnvelope, List<ApprovalGateDecl> approvalGates,
                        List<String> dataClasses, String runtime, String entrypoint, List<ResourceDecl> resources,
                        LlmDecl llm) {

    public Manifest(String name, String version, List<Scope> scopes, List<String> networkAllowlist,
                    CostEnvelope costEnvelope, List<ApprovalGateDecl> approvalGates,
                    List<String> dataClasses, String runtime, String entrypoint) {
        this(name, version, scopes, networkAllowlist, costEnvelope, approvalGates, dataClasses, runtime, entrypoint,
                null, null);
    }

    public Manifest(String name, String version, List<Scope> scopes, List<String> networkAllowlist,
                    CostEnvelope costEnvelope, List<ApprovalGateDecl> approvalGates,
                    List<String> dataClasses, String runtime, String entrypoint, List<ResourceDecl> resources) {
        this(name, version, scopes, networkAllowlist, costEnvelope, approvalGates, dataClasses, runtime, entrypoint,
                resources, null);
    }

    public boolean declaresResources() {
        return resources != null;
    }

    public Optional<ResourceDecl> resource(String resourceName) {
        return resources == null ? Optional.empty()
                : resources.stream().filter(r -> r.name().equals(resourceName)).findFirst();
    }
}
```

In `ManifestParser.java`, change the return of `parse` and add `parseLlm`:

```java
        return new Manifest(root.get("name").asText(), root.get("version").asText(), scopes, allowlist,
                costEnvelope, gates, dataClasses, textOrNull(root, "runtime"), textOrNull(root, "entrypoint"),
                parseResources(root), parseLlm(root));
    }

    private static LlmDecl parseLlm(JsonNode root) {
        JsonNode llm = root.get("llm");
        if (llm == null || !llm.isObject()) {
            return null;
        }
        String modeWire = llm.get("mode").asText();
        LlmMode mode = LlmMode.fromWire(modeWire)
                .orElseThrow(() -> new IllegalArgumentException("Unknown llm mode: " + modeWire));
        List<String> models = new ArrayList<>();
        for (JsonNode model : llm.path("models")) {
            models.add(model.asText());
        }
        LlmVendor provider = null;
        if (llm.hasNonNull("provider")) {
            String providerWire = llm.get("provider").asText();
            provider = LlmVendor.fromWire(providerWire)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown llm provider: " + providerWire));
        }
        return new LlmDecl(mode, models, llm.get("maxTokensPerRun").asLong(), provider);
    }
```

(Keep the existing `parseResources`, `parseSchema` and `textOrNull` methods untouched.)

Replace the whole of `manifest/ManifestValidator.java` with:

```java
package com.agentstore.platform.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class ManifestValidator {

    public static final long DEFAULT_MAX_TOKENS_PER_RUN_LIMIT = 5_000_000L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ManifestParser parser = new ManifestParser();
    private final JsonSchema schema;
    private final long maxTokensPerRunLimit;

    public ManifestValidator() {
        this(DEFAULT_MAX_TOKENS_PER_RUN_LIMIT);
    }

    @Autowired
    public ManifestValidator(@Value("${agentstore.llm.max-tokens-per-run-limit:5000000}") long maxTokensPerRunLimit) {
        this.maxTokensPerRunLimit = maxTokensPerRunLimit;
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        this.schema = factory.getSchema(
                getClass().getClassLoader().getResourceAsStream("manifest/manifest-schema.json"));
    }

    public Result validate(String rawJson) {
        JsonNode node;
        try {
            node = mapper.readTree(rawJson);
        } catch (Exception e) {
            return new Result(false, List.of("Manifest is not valid JSON: " + e.getMessage()));
        }
        var errors = schema.validate(node);
        if (!errors.isEmpty()) {
            return new Result(false, errors.stream().map(Object::toString).collect(Collectors.toList()));
        }
        Manifest manifest = parser.parse(rawJson);
        List<String> semantic = new ArrayList<>(ResourceManifestRules.check(manifest));
        semantic.addAll(LlmManifestRules.check(manifest, maxTokensPerRunLimit));
        return semantic.isEmpty() ? new Result(true, List.of()) : new Result(false, semantic);
    }

    public record Result(boolean valid, List<String> errors) {
    }
}
```

In `backend/src/main/resources/manifest/manifest-schema.json`, replace the line `"dataClasses": { "type": "array", "items": { "type": "string" } },` with:

```json
    "dataClasses": { "type": "array", "items": { "type": "string" } },
    "llm": {
      "type": "object",
      "required": ["mode", "models", "maxTokensPerRun"],
      "additionalProperties": false,
      "properties": {
        "mode": { "type": "string", "enum": ["platform", "byok_proxy", "byok_direct"] },
        "models": {
          "type": "array",
          "minItems": 1,
          "uniqueItems": true,
          "items": { "type": "string", "minLength": 1, "maxLength": 100 }
        },
        "maxTokensPerRun": { "type": "integer", "minimum": 1 },
        "provider": { "type": "string", "enum": ["anthropic", "openai"] }
      },
      "if": { "properties": { "mode": { "const": "platform" } }, "required": ["mode"] },
      "then": { "not": { "required": ["provider"] } },
      "else": { "required": ["provider"] }
    },
```

- [ ] **Step 6: Run the new test and every existing manifest test**

Run: `cd backend && mvn -q test -Dtest='LlmManifestTest,ResourceManifestTest,ManifestValidatorTest,ManifestParserTest,ManifestRuntimeFieldsTest,BundleManifest*Test'`
Expected: PASS (all).

- [ ] **Step 7: Re-sync the CLI's bundled schema**

```bash
cd cli && npm run sync-schema && npx vitest run
```

Expected: `copied … manifest-schema.json`, vitest all green.

- [ ] **Step 8: Commit**

```bash
git add backend cli
git commit -m "feat(llm): manifest llm block, rules and schema"
```

---

### Task 2: Migrations, entities and repositories

**Files:**
- Create: `backend/src/main/resources/db/migration/V29__llm_gateway.sql`, `V30__run_llm_columns.sql`
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunLlmMode.java`
- Create: `backend/src/main/java/com/agentstore/platform/llm/LlmSource.java`, `LlmCallStatus.java`, `LlmModel.java`, `LlmModelRepository.java`, `LlmCall.java`, `LlmCallRepository.java`, `ByokCredential.java`, `ByokCredentialRepository.java`
- Modify: `identity/Org.java`, `execution/Run.java`
- Test: `backend/src/test/java/com/agentstore/platform/llm/LlmFixtures.java`, `LlmDataModelTest.java`

**Interfaces:**
- Consumes: `LlmVendor` (Task 1); `ExecutionFixtures`, `RunRepository`, `OrgRepository`.
- Produces:
  - `enum RunLlmMode { MOCK, REPLAY }`; `Run.getLlmMode()/setLlmMode(RunLlmMode)`, `getReplayRunId()/setReplayRunId(UUID)`, `getLlmMockJson()/setLlmMockJson(String)`, `getLlmTokensUsed()`, `getLlmTokensReserved()` (read-only, no setters).
  - `Org.getDeveloperVerifiedAt()/setDeveloperVerifiedAt(Instant)`.
  - `enum LlmSource { PLATFORM, BYOK_PROXY, MOCK, REPLAY }`; `enum LlmCallStatus { OK, ERROR, BUDGET_REJECTED, REPLAY_MISS }`.
  - `LlmModel` (`getId()`, `getProvider()` `LlmVendor`, `getDisplayName()`, `isProviderRetainsData()`, `getInputPricePerMtok()`, `getOutputPricePerMtok()`, `getCurrency()`, `isEnabled()`); `LlmModelRepository.findByIdAndEnabledTrue(String)`.
  - `LlmCall` with constructor `LlmCall(UUID id, UUID runId, String modelId, LlmSource source, LlmCallStatus status, String requestHash)` and setters `setInputTokens(int)`, `setOutputTokens(int)`, `setCost(BigDecimal)`, `setLatencyMs(int)`, `setReplayOrdinal(Integer)`, `setRequestKey/ResponseKey(String)`, `setRecordingExpiresAt/PurgedAt(Instant)`, `setErrorCode(String)`; getters for all. `LlmCallRepository.findByRunIdOrderByCreatedAtAsc(UUID)`.
  - `ByokCredential(UUID developerOrgId, LlmVendor provider)` with `getEncryptedKey()/setEncryptedKey(byte[])`, `getNonce()/setNonce(byte[])`, `getLast4()/setLast4(String)`, `getCreatedAt()/setCreatedAt(Instant)`; `ByokCredentialRepository.findByDeveloperOrgIdAndProvider(UUID, LlmVendor)`, `findByDeveloperOrgIdOrderByProviderAsc(UUID)`, `deleteByDeveloperOrgIdAndProvider(UUID, LlmVendor)`.
  - Test helper `LlmFixtures` (public `@Component`): `ensureModels()`, `runningAgent(String manifestFile) → LlmAgent`, `verifyDeveloperOrg(Org)`, `int[] tokens(UUID runId)` (`{used, reserved}`), `setTokens(UUID, int used, int reserved)`, `configureRun(UUID runId, RunMode, RunLlmMode, UUID replayRunId, String mockJson)`; constants `CLAUDE`, `GPT`, `DISABLED`; `record LlmAgent(Scenario scenario, Run run, String token)` with `runId()` and `developerOrg()`.

- [ ] **Step 1: Write the failing test and the fixture**

`backend/src/test/java/com/agentstore/platform/llm/LlmFixtures.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunLlmMode;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.Org;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Shared setup for LLM gateway tests: test models, RUNNING runs of the LLM test manifests, counter access. */
@Component
public class LlmFixtures {

    public static final String CLAUDE = "test-claude";
    public static final String GPT = "test-gpt";
    public static final String DISABLED = "test-disabled";

    public record LlmAgent(ExecutionFixtures.Scenario scenario, Run run, String token) {
        public UUID runId() {
            return run.getId();
        }

        public Org developerOrg() {
            return scenario.version().getAgent().getDeveloperOrg();
        }
    }

    private final ExecutionFixtures executionFixtures;
    private final RunRepository runs;
    private final JdbcTemplate jdbc;

    public LlmFixtures(ExecutionFixtures executionFixtures, RunRepository runs, JdbcTemplate jdbc) {
        this.executionFixtures = executionFixtures;
        this.runs = runs;
        this.jdbc = jdbc;
    }

    /**
     * Upserts three test models: two enabled with prices, one disabled. The rows the migration seeds are never
     * touched, so a test that asserts the seed stays valid in a shared database.
     */
    public void ensureModels() {
        upsertModel(CLAUDE, "ANTHROPIC", "3.0000", "15.0000", true);
        upsertModel(GPT, "OPENAI", "2.5000", "10.0000", true);
        upsertModel(DISABLED, "ANTHROPIC", "1.0000", "5.0000", false);
    }

    private void upsertModel(String id, String provider, String inputPrice, String outputPrice, boolean enabled) {
        jdbc.update("""
                INSERT INTO llm_model (id, provider, display_name, provider_retains_data, input_price_per_mtok,
                                       output_price_per_mtok, currency, enabled)
                VALUES (?, ?, ?, FALSE, ?, ?, 'USD', ?)
                ON CONFLICT (id) DO UPDATE SET provider = EXCLUDED.provider,
                    input_price_per_mtok = EXCLUDED.input_price_per_mtok,
                    output_price_per_mtok = EXCLUDED.output_price_per_mtok, enabled = EXCLUDED.enabled""",
                id, provider, id, new BigDecimal(inputPrice), new BigDecimal(outputPrice), enabled);
    }

    /** A RUNNING PRODUCTION run of the given manifest (from src/test/resources/manifest) with a raw run token. */
    public LlmAgent runningAgent(String manifestFile) {
        var scenario = executionFixtures.liveWithManifest(manifestFile, List.of("bank_statement"),
                new BigDecimal("500.00"), new BigDecimal("1000.00"));
        String token = "rt-" + UUID.randomUUID();
        Run run = executionFixtures.run(scenario, RunStatus.RUNNING, "sbx-" + UUID.randomUUID(), token);
        run.setDeadlineAt(Instant.now().plusSeconds(600));
        return new LlmAgent(scenario, runs.save(run), token);
    }

    public void verifyDeveloperOrg(Org org) {
        jdbc.update("UPDATE org SET developer_verified_at = now() WHERE id = ?", org.getId());
    }

    /** {@code {used, reserved}} straight from the database. */
    public int[] tokens(UUID runId) {
        return jdbc.queryForObject("SELECT llm_tokens_used, llm_tokens_reserved FROM run WHERE id = ?",
                (rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)}, runId);
    }

    public void setTokens(UUID runId, int used, int reserved) {
        jdbc.update("UPDATE run SET llm_tokens_used = ?, llm_tokens_reserved = ? WHERE id = ?", used, reserved, runId);
    }

    /** Sets the run's mode and its LLM mock/replay configuration in one flush (the way WS6 profiles will). */
    public void configureRun(UUID runId, RunMode mode, RunLlmMode llmMode, UUID replayRunId, String mockJson) {
        Run run = runs.findById(runId).orElseThrow();
        run.setMode(mode);
        run.setLlmMode(llmMode);
        run.setReplayRunId(replayRunId);
        run.setLlmMockJson(mockJson);
        runs.saveAndFlush(run);
    }
}
```

`backend/src/test/java/com/agentstore/platform/llm/LlmDataModelTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunLlmMode;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.manifest.LlmVendor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmDataModelTest extends AbstractIntegrationTest {

    static final String HASH = "a".repeat(64);

    @Autowired LlmFixtures fx;
    @Autowired LlmModelRepository models;
    @Autowired LlmCallRepository calls;
    @Autowired ByokCredentialRepository credentials;
    @Autowired RunRepository runs;
    @Autowired OrgRepository orgs;

    @Test
    void theMigrationSeedsTheParentSpecModelsDisabledWithZeroPrices() {
        for (String id : List.of("claude-sonnet-5", "claude-haiku-4-5-20251001")) {
            LlmModel model = models.findById(id).orElseThrow();
            assertThat(model.getProvider()).isEqualTo(LlmVendor.ANTHROPIC);
            assertThat(model.isEnabled()).isFalse();
            assertThat(model.getInputPricePerMtok()).isEqualByComparingTo("0");
            assertThat(model.getOutputPricePerMtok()).isEqualByComparingTo("0");
        }
    }

    @Test
    void aProductionRunCannotCarryAnLlmModeButASandboxRunCan() {
        var agent = fx.runningAgent("valid-llm-platform.json");

        assertThatThrownBy(() -> fx.configureRun(agent.runId(), RunMode.PRODUCTION, RunLlmMode.MOCK, null, "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);

        fx.configureRun(agent.runId(), RunMode.SANDBOX, RunLlmMode.MOCK, null, "{\"default\":{}}");
        Run run = runs.findById(agent.runId()).orElseThrow();
        assertThat(run.getLlmMode()).isEqualTo(RunLlmMode.MOCK);
        assertThat(run.getLlmMockJson()).contains("default");
    }

    @Test
    void aFullEntitySaveNeverWritesTheTokenCounters() {
        var agent = fx.runningAgent("valid-llm-platform.json");
        fx.setTokens(agent.runId(), 5, 7);
        Run stale = runs.findById(agent.runId()).orElseThrow();      // holds 5 and 7
        assertThat(stale.getLlmTokensUsed()).isEqualTo(5);
        assertThat(stale.getLlmTokensReserved()).isEqualTo(7);

        fx.setTokens(agent.runId(), 9, 3);                            // another transaction moves the counters
        stale.setViolationCount(1);
        runs.saveAndFlush(stale);

        assertThat(fx.tokens(agent.runId())).containsExactly(9, 3);
    }

    @Test
    void newRunsStartWithZeroCountersAndNoLlmMode() {
        var agent = fx.runningAgent("valid-llm-platform.json");

        assertThat(fx.tokens(agent.runId())).containsExactly(0, 0);
        Run run = runs.findById(agent.runId()).orElseThrow();
        assertThat(run.getLlmMode()).isNull();
        assertThat(run.getReplayRunId()).isNull();
        assertThat(run.getLlmMockJson()).isNull();
    }

    @Test
    void aReplayOrdinalIsUniquePerRunAndHashButNullOrdinalsAreNot() {
        fx.ensureModels();
        var agent = fx.runningAgent("valid-llm-platform.json");

        calls.saveAndFlush(call(agent.runId(), 1));
        assertThatThrownBy(() -> calls.saveAndFlush(call(agent.runId(), 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        calls.saveAndFlush(call(agent.runId(), 2));
        calls.saveAndFlush(call(agent.runId(), null));
        calls.saveAndFlush(call(agent.runId(), null));

        assertThat(calls.findByRunIdOrderByCreatedAtAsc(agent.runId())).hasSize(4);
    }

    @Test
    void aCallRoundTripsItsColumns() {
        fx.ensureModels();
        var agent = fx.runningAgent("valid-llm-platform.json");
        LlmCall call = call(agent.runId(), null);
        call.setInputTokens(100);
        call.setOutputTokens(40);
        call.setCost(new java.math.BigDecimal("0.0009"));
        call.setLatencyMs(12);
        call.setErrorCode(null);
        calls.saveAndFlush(call);

        LlmCall loaded = calls.findByRunIdOrderByCreatedAtAsc(agent.runId()).get(0);
        assertThat(loaded.getInputTokens()).isEqualTo(100);
        assertThat(loaded.getOutputTokens()).isEqualTo(40);
        assertThat(loaded.getCost()).isEqualByComparingTo("0.0009");
        assertThat(loaded.getSource()).isEqualTo(LlmSource.PLATFORM);
        assertThat(loaded.getStatus()).isEqualTo(LlmCallStatus.OK);
        assertThat(loaded.getRequestHash()).isEqualTo(HASH);
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void aByokCredentialIsUniquePerOrgAndProvider() {
        Org org = orgs.save(new Org(null, "Dev " + UUID.randomUUID(), OrgType.DEVELOPER));

        credentials.saveAndFlush(credential(org, LlmVendor.ANTHROPIC));
        credentials.saveAndFlush(credential(org, LlmVendor.OPENAI));
        assertThatThrownBy(() -> credentials.saveAndFlush(credential(org, LlmVendor.ANTHROPIC)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(credentials.findByDeveloperOrgIdOrderByProviderAsc(org.getId())).hasSize(2);
        assertThat(credentials.findByDeveloperOrgIdAndProvider(org.getId(), LlmVendor.OPENAI)).isPresent();
    }

    @Test
    void anOrgIsUnverifiedUntilSomethingSetsTheStamp() {
        Org org = orgs.save(new Org(null, "Dev " + UUID.randomUUID(), OrgType.DEVELOPER));
        assertThat(orgs.findById(org.getId()).orElseThrow().getDeveloperVerifiedAt()).isNull();

        fx.verifyDeveloperOrg(org);

        assertThat(orgs.findById(org.getId()).orElseThrow().getDeveloperVerifiedAt()).isNotNull();
    }

    private static LlmCall call(UUID runId, Integer ordinal) {
        LlmCall call = new LlmCall(UUID.randomUUID(), runId, LlmFixtures.CLAUDE, LlmSource.PLATFORM, LlmCallStatus.OK, HASH);
        call.setReplayOrdinal(ordinal);
        return call;
    }

    private static ByokCredential credential(Org org, LlmVendor vendor) {
        ByokCredential credential = new ByokCredential(org.getId(), vendor);
        credential.setEncryptedKey(new byte[]{1, 2, 3});
        credential.setNonce(new byte[]{4, 5, 6});
        credential.setLast4("1234");
        return credential;
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=LlmDataModelTest`
Expected: FAIL to compile (entities, repositories and `RunLlmMode` do not exist).

- [ ] **Step 3: Write the migrations**

`backend/src/main/resources/db/migration/V29__llm_gateway.sql`:

```sql
CREATE TABLE llm_model (
    id VARCHAR(100) PRIMARY KEY,                  -- the provider's model id, e.g. claude-sonnet-5
    provider VARCHAR(20) NOT NULL,                -- ANTHROPIC | OPENAI
    display_name VARCHAR(200) NOT NULL,
    provider_retains_data BOOLEAN NOT NULL DEFAULT TRUE,
    input_price_per_mtok NUMERIC(12,4) NOT NULL DEFAULT 0,
    output_price_per_mtok NUMERIC(12,4) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    enabled BOOLEAN NOT NULL DEFAULT FALSE
);

-- The two models the parent spec names, disabled with zero prices: real prices are an operator decision.
INSERT INTO llm_model (id, provider, display_name) VALUES
    ('claude-sonnet-5', 'ANTHROPIC', 'Claude Sonnet 5'),
    ('claude-haiku-4-5-20251001', 'ANTHROPIC', 'Claude Haiku 4.5');

CREATE TABLE llm_call (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES run(id),
    model_id VARCHAR(100) NOT NULL REFERENCES llm_model(id),
    source VARCHAR(12) NOT NULL,                  -- PLATFORM | BYOK_PROXY | MOCK | REPLAY
    status VARCHAR(16) NOT NULL,                  -- OK | ERROR | BUDGET_REJECTED | REPLAY_MISS
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    cost NUMERIC(12,4) NOT NULL DEFAULT 0,
    latency_ms INT NOT NULL DEFAULT 0,
    request_hash VARCHAR(64) NOT NULL,            -- SHA-256 hex of the canonical request
    replay_ordinal INT,
    request_key VARCHAR(500),                     -- object-store keys; null unless recorded
    response_key VARCHAR(500),
    recording_expires_at TIMESTAMPTZ,
    recording_purged_at TIMESTAMPTZ,
    error_code VARCHAR(50),                       -- our code, never a provider body
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX llm_call_run_created_idx ON llm_call (run_id, created_at);
CREATE INDEX llm_call_run_hash_idx ON llm_call (run_id, request_hash);
CREATE UNIQUE INDEX llm_call_replay_ordinal_idx ON llm_call (run_id, request_hash, replay_ordinal)
    WHERE replay_ordinal IS NOT NULL;
CREATE INDEX llm_call_recording_expiry_idx ON llm_call (recording_expires_at)
    WHERE recording_purged_at IS NULL AND recording_expires_at IS NOT NULL;

CREATE TABLE byok_credential (
    id UUID PRIMARY KEY,
    developer_org_id UUID NOT NULL REFERENCES org(id),
    provider VARCHAR(20) NOT NULL,
    encrypted_key BYTEA NOT NULL,
    nonce BYTEA NOT NULL,
    last4 VARCHAR(8) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (developer_org_id, provider)
);
```

`V30__run_llm_columns.sql`:

```sql
ALTER TABLE org ADD COLUMN developer_verified_at TIMESTAMPTZ;   -- nothing in WS3 sets it; WS7 will

ALTER TABLE run ADD COLUMN llm_tokens_used INT NOT NULL DEFAULT 0 CHECK (llm_tokens_used >= 0);
ALTER TABLE run ADD COLUMN llm_tokens_reserved INT NOT NULL DEFAULT 0 CHECK (llm_tokens_reserved >= 0);
ALTER TABLE run ADD COLUMN llm_mode VARCHAR(10);                -- MOCK | REPLAY; null means real
ALTER TABLE run ADD COLUMN replay_run_id UUID;
ALTER TABLE run ADD COLUMN llm_mock_json JSONB;
ALTER TABLE run ADD CONSTRAINT run_llm_mode_not_production CHECK (llm_mode IS NULL OR mode <> 'PRODUCTION');
```

- [ ] **Step 4: Write the entities and repositories**

`execution/RunLlmMode.java`:

```java
package com.agentstore.platform.execution;

/** How a non-production run's LLM calls are served instead of hitting a provider; null on the run means real. */
public enum RunLlmMode { MOCK, REPLAY }
```

`llm/LlmSource.java`:

```java
package com.agentstore.platform.llm;

/** Where a call's answer came from. Only PLATFORM calls record a cost. */
public enum LlmSource { PLATFORM, BYOK_PROXY, MOCK, REPLAY }
```

`llm/LlmCallStatus.java`:

```java
package com.agentstore.platform.llm;

public enum LlmCallStatus { OK, ERROR, BUDGET_REJECTED, REPLAY_MISS }
```

`llm/LlmModel.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.manifest.LlmVendor;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** One model the platform can serve. Operators set prices and {@code enabled}; nothing in code invents them. */
@Entity
@Table(name = "llm_model")
@Getter
@Setter
@NoArgsConstructor
public class LlmModel {

    @Id
    private String id;

    @Enumerated(EnumType.STRING)
    private LlmVendor provider;

    private String displayName;
    private boolean providerRetainsData;
    private BigDecimal inputPricePerMtok;
    private BigDecimal outputPricePerMtok;
    private String currency;
    private boolean enabled;
}
```

`llm/LlmModelRepository.java`:

```java
package com.agentstore.platform.llm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LlmModelRepository extends JpaRepository<LlmModel, String> {

    Optional<LlmModel> findByIdAndEnabledTrue(String id);
}
```

`llm/LlmCall.java`:

```java
package com.agentstore.platform.llm;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One call the gateway handled (served, failed, over budget or a replay miss). The id is chosen by the caller because
 * recorded blobs are keyed by it before the row is written. Never holds a prompt, a key or a provider body.
 */
@Entity
@Table(name = "llm_call")
@Getter
@Setter
@NoArgsConstructor
public class LlmCall {

    @Id
    private UUID id;

    private UUID runId;
    private String modelId;

    @Enumerated(EnumType.STRING)
    private LlmSource source;

    @Enumerated(EnumType.STRING)
    private LlmCallStatus status;

    private int inputTokens;
    private int outputTokens;
    private BigDecimal cost = BigDecimal.ZERO;
    private int latencyMs;
    private String requestHash;
    private Integer replayOrdinal;
    private String requestKey;
    private String responseKey;
    private Instant recordingExpiresAt;
    private Instant recordingPurgedAt;
    private String errorCode;
    private Instant createdAt;

    public LlmCall(UUID id, UUID runId, String modelId, LlmSource source, LlmCallStatus status, String requestHash) {
        this.id = id;
        this.runId = runId;
        this.modelId = modelId;
        this.source = source;
        this.status = status;
        this.requestHash = requestHash;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`llm/LlmCallRepository.java`:

```java
package com.agentstore.platform.llm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LlmCallRepository extends JpaRepository<LlmCall, UUID> {

    List<LlmCall> findByRunIdOrderByCreatedAtAsc(UUID runId);
}
```

`llm/ByokCredential.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.manifest.LlmVendor;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A developer org's LLM key for one provider, AES-GCM encrypted with the same key as connection secrets. */
@Entity
@Table(name = "byok_credential")
@Getter
@Setter
@NoArgsConstructor
public class ByokCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID developerOrgId;

    @Enumerated(EnumType.STRING)
    private LlmVendor provider;

    private byte[] encryptedKey;
    private byte[] nonce;
    private String last4;
    private Instant createdAt;

    public ByokCredential(UUID developerOrgId, LlmVendor provider) {
        this.developerOrgId = developerOrgId;
        this.provider = provider;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`llm/ByokCredentialRepository.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.manifest.LlmVendor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ByokCredentialRepository extends JpaRepository<ByokCredential, UUID> {

    Optional<ByokCredential> findByDeveloperOrgIdAndProvider(UUID developerOrgId, LlmVendor provider);

    List<ByokCredential> findByDeveloperOrgIdOrderByProviderAsc(UUID developerOrgId);

    void deleteByDeveloperOrgIdAndProvider(UUID developerOrgId, LlmVendor provider);
}
```

In `identity/Org.java`, add below the `storageLimitBytes` field:

```java
    /** Set by the developer verification workflow (WS7). Until that exists, tests and local development set it directly. */
    private Instant developerVerifiedAt;
```

In `execution/Run.java`, add `import lombok.AccessLevel;` next to the other Lombok imports and add these fields after `inputJson`:

```java
    /**
     * Read-only here on purpose: these counters change only through the gateway's atomic statements
     * ({@code RunRepository.reserveLlmTokens/settleLlmTokens}). A full entity save must never write a stale copy back.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "llm_tokens_used", insertable = false, updatable = false)
    private int llmTokensUsed;

    @Setter(AccessLevel.NONE)
    @Column(name = "llm_tokens_reserved", insertable = false, updatable = false)
    private int llmTokensReserved;

    /** MOCK or REPLAY for a non-production run whose LLM calls are not real; null means real. Set by WS6. */
    @Enumerated(EnumType.STRING)
    @Column(name = "llm_mode")
    private RunLlmMode llmMode;

    private UUID replayRunId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "llm_mock_json", columnDefinition = "jsonb")
    private String llmMockJson;
```

- [ ] **Step 5: Run the test to see it pass**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=LlmDataModelTest`
Expected: PASS (8 tests). If Hibernate `validate` complains about a column, fix the entity or migration so they match exactly.

- [ ] **Step 6: Run the execution and identity suites to prove nothing else moved**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='com.agentstore.platform.execution.*Test,com.agentstore.platform.identity.*Test'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat(llm): llm_model, llm_call, byok_credential, org verification and run llm columns"
```

---

### Task 3: Request normalizer, canonical hash, token budget

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/llm/LlmRequest.java`, `LlmResponse.java`, `LlmRequestNormalizer.java`, `LlmBudget.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunRepository.java`
- Test: `backend/src/test/java/com/agentstore/platform/llm/LlmRequestNormalizerTest.java`, `LlmBudgetTest.java`

**Interfaces:**
- Consumes: `RuntimeApiException(HttpStatus, String code, String message)` (public, `runtime` package); `RunRepository`; `LlmFixtures` (Task 2).
- Produces:
  - `record LlmRequest(String model, String system, List<Msg> messages, List<Tool> tools, int maxTokens, Double temperature)` with nested `record Msg(String role, List<Map<String,Object>> content)` and `record Tool(String name, String description, Map<String,Object> inputSchema)`; `tools` is never null (empty when absent); `Map<String,Object> toMap()`. Block maps use the wire keys: `{type:"text", text}`, `{type:"tool_use", id, name, input}`, `{type:"tool_result", toolUseId, content}`.
  - `record LlmResponse(String model, List<Map<String,Object>> content, String stopReason, int inputTokens, int outputTokens)` with `Map<String,Object> toMap()` and `static LlmResponse fromMap(Map<String,Object>, String fallbackModel)` (throws `IllegalArgumentException` on a malformed `content`).
  - `LlmRequestNormalizer` (`@Component`, constructor `LlmRequestNormalizer(int maxOutputTokens)`): `Normalized normalize(Map<String,Object> raw)` where `record Normalized(LlmRequest request, String hash, long estimatedInputTokens)`; throws `RuntimeApiException` 400 `INVALID_REQUEST`. Package-private `static long ceilDiv3(long)`.
  - `LlmBudget` (`@Component`): `boolean reserve(UUID runId, long tokens, long maxTokensPerRun)`, `void settle(UUID runId, long reserved, long usedTokens)`, `void release(UUID runId, long reserved)`.
  - `RunRepository.reserveLlmTokens(UUID id, int tokens, long max)` and `settleLlmTokens(UUID id, int reserved, int used)` (native atomic statements).

- [ ] **Step 1: Write the failing normalizer test**

`backend/src/test/java/com/agentstore/platform/llm/LlmRequestNormalizerTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.runtime.RuntimeApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmRequestNormalizerTest {

    final LlmRequestNormalizer normalizer = new LlmRequestNormalizer(32_000);

    static Map<String, Object> request(Object content) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("model", "m");
        r.put("messages", List.of(Map.of("role", "user", "content", content)));
        r.put("maxTokens", 100);
        return r;
    }

    static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(base);
        if (value == null) {
            copy.remove(key);
        } else {
            copy.put(key, value);
        }
        return copy;
    }

    void assertInvalid(Map<String, Object> request) {
        assertThatThrownBy(() -> normalizer.normalize(request)).isInstanceOfSatisfying(RuntimeApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.code()).isEqualTo("INVALID_REQUEST");
        });
    }

    @Test
    void aStringContentBecomesASingleTextBlock() {
        var normalized = normalizer.normalize(request("hi"));

        assertThat(normalized.request().messages().get(0).content())
                .containsExactly(Map.of("type", "text", "text", "hi"));
        assertThat(normalized.request().tools()).isEmpty();
        assertThat(normalized.request().system()).isNull();
        assertThat(normalized.request().temperature()).isNull();
        assertThat(normalized.request().maxTokens()).isEqualTo(100);
    }

    @Test
    void theHashIgnoresKeyOrderAndStringVersusBlockContent() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("model", "m");
        a.put("maxTokens", 100);
        a.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("messages", List.of(Map.of("content", List.of(Map.of("text", "hi", "type", "text")), "role", "user")));
        b.put("maxTokens", 100);
        b.put("model", "m");

        assertThat(normalizer.normalize(a).hash()).isEqualTo(normalizer.normalize(b).hash()).hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void theHashTreatsEmptyToolsAsAbsentAndAnIntegerTemperatureAsADouble() {
        Map<String, Object> base = request("hi");

        assertThat(normalizer.normalize(with(base, "tools", List.of())).hash())
                .isEqualTo(normalizer.normalize(base).hash());
        assertThat(normalizer.normalize(with(base, "temperature", 1)).hash())
                .isEqualTo(normalizer.normalize(with(base, "temperature", 1.0)).hash());
    }

    @Test
    void theHashChangesWhenAnyHashedFieldChanges() {
        Map<String, Object> base = request("hi");
        Map<String, Object> tool = Map.of("name", "lookup", "inputSchema", Map.of("type", "object"));
        List<String> hashes = new ArrayList<>(List.of(
                normalizer.normalize(base).hash(),
                normalizer.normalize(with(base, "model", "other")).hash(),
                normalizer.normalize(request("hello")).hash(),
                normalizer.normalize(with(base, "maxTokens", 101)).hash(),
                normalizer.normalize(with(base, "temperature", 0.5)).hash(),
                normalizer.normalize(with(base, "tools", List.of(tool))).hash(),
                normalizer.normalize(with(base, "messages",
                        List.of(Map.of("role", "assistant", "content", "hi")))).hash()));

        assertThat(hashes).doesNotHaveDuplicates();
    }

    @Test
    void theEstimateCountsBytesNotCharactersAndIncludesTheSystemPrompt() {
        long ascii = normalizer.normalize(request("a".repeat(30))).estimatedInputTokens();
        long euro = normalizer.normalize(request("€".repeat(30))).estimatedInputTokens();
        long withSystem = normalizer.normalize(with(request("a".repeat(30)), "system", "s".repeat(300)))
                .estimatedInputTokens();

        assertThat(euro).isGreaterThan(ascii);
        assertThat(withSystem).isGreaterThanOrEqualTo(ascii + 100);
        assertThat(LlmRequestNormalizer.ceilDiv3(0)).isZero();
        assertThat(LlmRequestNormalizer.ceilDiv3(1)).isEqualTo(1);
        assertThat(LlmRequestNormalizer.ceilDiv3(3)).isEqualTo(1);
        assertThat(LlmRequestNormalizer.ceilDiv3(4)).isEqualTo(2);
    }

    @Test
    void maxTokensMustBeAPositiveWholeNumberWithinTheCap() {
        Map<String, Object> base = request("hi");
        for (Object bad : new Object[]{null, 0, -1, 32_001, "100", 10.5, 1000.0, true, List.of(100)}) {
            assertInvalid(with(base, "maxTokens", bad));
        }
        assertThat(normalizer.normalize(with(base, "maxTokens", 1)).request().maxTokens()).isEqualTo(1);
        assertThat(normalizer.normalize(with(base, "maxTokens", 32_000)).request().maxTokens()).isEqualTo(32_000);
        assertThat(normalizer.normalize(with(base, "maxTokens", 100L)).request().maxTokens()).isEqualTo(100);
    }

    @Test
    void modelAndTemperatureAreChecked() {
        Map<String, Object> base = request("hi");
        for (Object bad : new Object[]{null, "", "  ", 5, List.of("m")}) {
            assertInvalid(with(base, "model", bad));
        }
        for (Object bad : new Object[]{-1, -0.1, "hot", true, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertInvalid(with(base, "temperature", bad));
        }
        assertThat(normalizer.normalize(with(base, "temperature", 0)).request().temperature()).isEqualTo(0.0);
    }

    @Test
    void unknownKeysAreRejectedNotForwarded() {
        Map<String, Object> base = request("hi");
        assertInvalid(with(base, "stream", true));
        assertInvalid(with(base, "top_p", 0.9));
        assertInvalid(with(base, "messages", List.of(Map.of("role", "user", "content", "hi", "name", "bob"))));
        assertInvalid(with(base, "messages", List.of(Map.of("role", "user", "content",
                List.of(Map.of("type", "text", "text", "hi", "cache", true))))));
        assertInvalid(with(base, "tools", List.of(Map.of("name", "t", "inputSchema", Map.of(), "strict", true))));
    }

    @Test
    void messagesMustBeANonEmptyListOfUserOrAssistantTurns() {
        Map<String, Object> base = request("hi");
        assertInvalid(with(base, "messages", null));
        assertInvalid(with(base, "messages", List.of()));
        assertInvalid(with(base, "messages", "hi"));
        assertInvalid(with(base, "messages", List.of("hi")));
        assertInvalid(with(base, "messages", List.of(Map.of("role", "system", "content", "hi"))));
        assertInvalid(with(base, "messages", List.of(Map.of("content", "hi"))));
        assertInvalid(with(base, "messages", List.of(Map.of("role", "user"))));
        assertInvalid(request(List.of()));
        assertInvalid(request(42));
        assertInvalid(request(List.of(Map.of("text", "no type"))));
        assertInvalid(request(List.of(Map.of("type", "image", "source", "x"))));
    }

    @Test
    void toolBlocksAreValidated() {
        assertInvalid(request(List.of(Map.of("type", "tool_result", "toolUseId", "t1"))));
        assertInvalid(request(List.of(Map.of("type", "tool_result", "content", "42"))));
        assertInvalid(request(List.of(Map.of("type", "tool_use", "name", "lookup", "input", Map.of()))));
        assertInvalid(request(List.of(Map.of("type", "tool_use", "id", "t1", "input", Map.of()))));
        assertInvalid(request(List.of(Map.of("type", "tool_use", "id", "t1", "name", "lookup", "input", "oops"))));

        var ok = normalizer.normalize(with(request(List.of(
                Map.of("type", "tool_use", "id", "t1", "name", "lookup", "input", Map.of("q", "x")),
                Map.of("type", "tool_result", "toolUseId", "t1", "content", "42"))), "tools",
                List.of(Map.of("name", "lookup", "description", "Looks things up",
                        "inputSchema", Map.of("type", "object")))));
        assertThat(ok.request().tools()).hasSize(1);
        assertThat(ok.request().tools().get(0).description()).isEqualTo("Looks things up");
        assertThat(ok.request().messages().get(0).content()).hasSize(2);
    }

    @Test
    void toolsMustBeWellFormed() {
        Map<String, Object> base = request("hi");
        assertInvalid(with(base, "tools", "lookup"));
        assertInvalid(with(base, "tools", List.of("lookup")));
        assertInvalid(with(base, "tools", List.of(Map.of("name", "lookup"))));
        assertInvalid(with(base, "tools", List.of(Map.of("name", " ", "inputSchema", Map.of()))));
        assertInvalid(with(base, "tools", List.of(Map.of("name", "t", "inputSchema", "object"))));
    }

    @Test
    void aNullRequestBodyIsInvalid() {
        assertThatThrownBy(() -> normalizer.normalize(null)).isInstanceOf(RuntimeApiException.class);
    }

    @Test
    void errorMessagesDoNotEchoLongInput() {
        Map<String, Object> hostile = with(request("hi"), "x".repeat(500), 1);

        assertThatThrownBy(() -> normalizer.normalize(hostile)).isInstanceOfSatisfying(RuntimeApiException.class,
                e -> assertThat(e.getMessage().length()).isLessThan(200));
    }
}
```

- [ ] **Step 2: Write the failing budget test**

`backend/src/test/java/com/agentstore/platform/llm/LlmBudgetTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LlmBudgetTest extends AbstractIntegrationTest {

    @Autowired LlmBudget budget;
    @Autowired LlmFixtures fx;

    @Test
    void reservesUntilTheBudgetIsFullAndNotOneTokenMore() {
        var agent = fx.runningAgent("valid-llm-platform.json");

        assertThat(budget.reserve(agent.runId(), 400, 1000)).isTrue();
        assertThat(budget.reserve(agent.runId(), 600, 1000)).isTrue();      // exactly at the boundary
        assertThat(budget.reserve(agent.runId(), 1, 1000)).isFalse();

        assertThat(fx.tokens(agent.runId())).containsExactly(0, 1000);
    }

    @Test
    void aReservationLargerThanTheWholeBudgetIsRefusedWithoutTouchingTheRun() {
        var agent = fx.runningAgent("valid-llm-platform.json");

        assertThat(budget.reserve(agent.runId(), 1001, 1000)).isFalse();
        assertThat(budget.reserve(agent.runId(), 0, 1000)).isFalse();
        assertThat(budget.reserve(agent.runId(), -5, 1000)).isFalse();

        assertThat(fx.tokens(agent.runId())).containsExactly(0, 0);
    }

    @Test
    void settlingMovesTheReservationToUsedWithTheActualCount() {
        var agent = fx.runningAgent("valid-llm-platform.json");

        assertThat(budget.reserve(agent.runId(), 500, 1000)).isTrue();
        budget.settle(agent.runId(), 500, 120);
        assertThat(fx.tokens(agent.runId())).containsExactly(120, 0);

        // actual usage may exceed the estimate; the cap is enforced at admission
        assertThat(budget.reserve(agent.runId(), 300, 1000)).isTrue();
        budget.settle(agent.runId(), 300, 450);
        assertThat(fx.tokens(agent.runId())).containsExactly(570, 0);
    }

    @Test
    void releasingGivesTheReservationBackAndLeavesUsedAlone() {
        var agent = fx.runningAgent("valid-llm-platform.json");
        fx.setTokens(agent.runId(), 200, 0);

        assertThat(budget.reserve(agent.runId(), 500, 1000)).isTrue();
        budget.release(agent.runId(), 500);

        assertThat(fx.tokens(agent.runId())).containsExactly(200, 0);
    }

    @Test
    void usedTokensCountAgainstTheBudget() {
        var agent = fx.runningAgent("valid-llm-platform.json");
        fx.setTokens(agent.runId(), 900, 0);

        assertThat(budget.reserve(agent.runId(), 100, 1000)).isTrue();
        assertThat(budget.reserve(agent.runId(), 1, 1000)).isFalse();
    }

    @Test
    void concurrentReservationsCannotOversubscribeTheBudget() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return budget.reserve(agent.runId(), 300, 1000);
            }));
        }
        start.countDown();
        long granted = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                granted++;
            }
        }
        pool.shutdown();

        assertThat(granted).isEqualTo(3);
        assertThat(fx.tokens(agent.runId())).containsExactly(0, 900);
    }
}
```

- [ ] **Step 3: Run both to see them fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='LlmRequestNormalizerTest,LlmBudgetTest'`
Expected: FAIL to compile (`LlmRequestNormalizer`, `LlmBudget`, `LlmRequest` do not exist).

- [ ] **Step 4: Implement the records**

`llm/LlmRequest.java`:

```java
package com.agentstore.platform.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The normalized chat request every adapter, the hash and the recorder work from. String message content has already
 * become a single text block; {@code tools} is empty, never null, when the agent sent none.
 */
public record LlmRequest(String model, String system, List<Msg> messages, List<Tool> tools, int maxTokens,
                         Double temperature) {

    public record Msg(String role, List<Map<String, Object>> content) {
    }

    public record Tool(String name, String description, Map<String, Object> inputSchema) {
    }

    /** The request in the shape agents send it (what a recording stores). */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", model);
        if (system != null) {
            out.put("system", system);
        }
        List<Map<String, Object>> turns = new ArrayList<>();
        for (Msg message : messages) {
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("role", message.role());
            turn.put("content", message.content());
            turns.add(turn);
        }
        out.put("messages", turns);
        if (!tools.isEmpty()) {
            List<Map<String, Object>> declared = new ArrayList<>();
            for (Tool tool : tools) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", tool.name());
                if (tool.description() != null) {
                    entry.put("description", tool.description());
                }
                entry.put("inputSchema", tool.inputSchema());
                declared.add(entry);
            }
            out.put("tools", declared);
        }
        out.put("maxTokens", maxTokens);
        if (temperature != null) {
            out.put("temperature", temperature);
        }
        return out;
    }
}
```

`llm/LlmResponse.java`:

```java
package com.agentstore.platform.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The normalized chat response: text and tool_use blocks, a stop reason and the provider's own token counts. */
public record LlmResponse(String model, List<Map<String, Object>> content, String stopReason, int inputTokens,
                          int outputTokens) {

    private static final Set<String> STOP_REASONS = Set.of("end_turn", "max_tokens", "tool_use", "other");

    public Map<String, Object> toMap() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputTokens", inputTokens);
        usage.put("outputTokens", outputTokens);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", model);
        out.put("content", content);
        out.put("stopReason", stopReason);
        out.put("usage", usage);
        return out;
    }

    /**
     * Reads a response that was recorded or canned by hand. Only {@code content} is required; a missing model,
     * stop reason or usage falls back to the request's model, {@code end_turn} and zero tokens.
     */
    @SuppressWarnings("unchecked")
    public static LlmResponse fromMap(Map<String, Object> map, String fallbackModel) {
        if (!(map.get("content") instanceof List<?> list)) {
            throw new IllegalArgumentException("content must be an array");
        }
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> block)) {
                throw new IllegalArgumentException("a content block must be an object");
            }
            Object type = block.get("type");
            boolean text = "text".equals(type) && block.get("text") instanceof String;
            boolean toolUse = "tool_use".equals(type) && block.get("id") instanceof String
                    && block.get("name") instanceof String && block.get("input") instanceof Map;
            if (!text && !toolUse) {
                throw new IllegalArgumentException("unsupported content block");
            }
            blocks.add((Map<String, Object>) block);
        }
        String model = map.get("model") instanceof String s && !s.isBlank() ? s : fallbackModel;
        String stop = map.get("stopReason") instanceof String s && STOP_REASONS.contains(s) ? s : "end_turn";
        int in = 0;
        int out = 0;
        if (map.get("usage") instanceof Map<?, ?> usage) {
            in = count(usage.get("inputTokens"));
            out = count(usage.get("outputTokens"));
        }
        return new LlmResponse(model, List.copyOf(blocks), stop, in, out);
    }

    private static int count(Object value) {
        return value instanceof Number n ? Math.max(0, n.intValue()) : 0;
    }
}
```

- [ ] **Step 5: Implement the normalizer**

`llm/LlmRequestNormalizer.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.runtime.RuntimeApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates and canonicalises a chat request. Everything after this class works on {@link LlmRequest}: unknown keys
 * and block types never reach a provider. The hash covers exactly the fields the spec lists (model, messages, tools,
 * maxTokens, temperature; sorted keys, string content already turned into a text block, empty tools omitted); the
 * token estimate also covers {@code system}, so a big system prompt is not under-reserved.
 */
@Component
public class LlmRequestNormalizer {

    public record Normalized(LlmRequest request, String hash, long estimatedInputTokens) {
    }

    private static final Set<String> TOP_LEVEL = Set.of("model", "system", "messages", "tools", "maxTokens", "temperature");
    private static final Set<String> MESSAGE_KEYS = Set.of("role", "content");
    private static final Set<String> TEXT_KEYS = Set.of("type", "text");
    private static final Set<String> TOOL_USE_KEYS = Set.of("type", "id", "name", "input");
    private static final Set<String> TOOL_RESULT_KEYS = Set.of("type", "toolUseId", "content");
    private static final Set<String> TOOL_KEYS = Set.of("name", "description", "inputSchema");
    private static final ObjectMapper CANONICAL =
            JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private final int maxOutputTokens;

    public LlmRequestNormalizer(@Value("${agentstore.llm.max-output-tokens:32000}") int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public Normalized normalize(Map<String, Object> raw) {
        if (raw == null) {
            throw invalid("A request body is required");
        }
        onlyKeys(raw, TOP_LEVEL, "the request");
        String model = requiredString(raw, "model", "the request");
        String system = raw.get("system") == null ? null : text(raw.get("system"), "system");
        int maxTokens = maxTokens(raw.get("maxTokens"));
        Double temperature = temperature(raw.get("temperature"));
        List<LlmRequest.Msg> messages = messages(raw.get("messages"));
        List<LlmRequest.Tool> tools = tools(raw.get("tools"));
        LlmRequest request = new LlmRequest(model, system, messages, tools, maxTokens, temperature);
        return new Normalized(request, sha256Hex(canonicalBytes(request, false)),
                ceilDiv3(canonicalBytes(request, true).length));
    }

    static long ceilDiv3(long n) {
        return (n + 2) / 3;
    }

    private int maxTokens(Object value) {
        boolean whole = value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte;
        if (!whole) {
            throw invalid("maxTokens is required and must be a whole number");
        }
        long n = ((Number) value).longValue();
        if (n < 1 || n > maxOutputTokens) {
            throw invalid("maxTokens must be between 1 and " + maxOutputTokens);
        }
        return (int) n;
    }

    private static Double temperature(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number n)) {
            throw invalid("temperature must be a number");
        }
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d) || d < 0) {
            throw invalid("temperature must be a finite number of at least 0");
        }
        return d;
    }

    private static List<LlmRequest.Msg> messages(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw invalid("messages must be a non-empty array");
        }
        List<LlmRequest.Msg> out = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> message = object(item, "a message");
            onlyKeys(message, MESSAGE_KEYS, "a message");
            String role = requiredString(message, "role", "a message");
            if (!role.equals("user") && !role.equals("assistant")) {
                throw invalid("message.role must be 'user' or 'assistant'");
            }
            out.add(new LlmRequest.Msg(role, blocks(message.get("content"))));
        }
        return out;
    }

    private static List<Map<String, Object>> blocks(Object content) {
        if (content instanceof String s) {
            return List.of(textBlock(s));
        }
        if (!(content instanceof List<?> list) || list.isEmpty()) {
            throw invalid("message.content must be a string or a non-empty array of blocks");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> block = object(item, "a content block");
            String type = requiredString(block, "type", "a content block");
            switch (type) {
                case "text" -> {
                    onlyKeys(block, TEXT_KEYS, "a text block");
                    out.add(textBlock(text(block.get("text"), "text")));
                }
                case "tool_use" -> {
                    onlyKeys(block, TOOL_USE_KEYS, "a tool_use block");
                    Map<String, Object> use = new LinkedHashMap<>();
                    use.put("type", "tool_use");
                    use.put("id", requiredString(block, "id", "a tool_use block"));
                    use.put("name", requiredString(block, "name", "a tool_use block"));
                    use.put("input", object(block.get("input"), "tool_use.input"));
                    out.add(use);
                }
                case "tool_result" -> {
                    onlyKeys(block, TOOL_RESULT_KEYS, "a tool_result block");
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("type", "tool_result");
                    result.put("toolUseId", requiredString(block, "toolUseId", "a tool_result block"));
                    result.put("content", text(block.get("content"), "tool_result.content"));
                    out.add(result);
                }
                default -> throw invalid("Unsupported content block type '" + shorten(type) + "'");
            }
        }
        return out;
    }

    private static List<LlmRequest.Tool> tools(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw invalid("tools must be an array");
        }
        List<LlmRequest.Tool> out = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> tool = object(item, "a tool");
            onlyKeys(tool, TOOL_KEYS, "a tool");
            String description = tool.get("description") == null ? null : text(tool.get("description"), "tool.description");
            out.add(new LlmRequest.Tool(requiredString(tool, "name", "a tool"), description,
                    object(tool.get("inputSchema"), "tool.inputSchema")));
        }
        return out;
    }

    private static Map<String, Object> textBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String what) {
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid(what + " must be an object");
        }
        for (Object key : map.keySet()) {
            if (!(key instanceof String)) {
                throw invalid(what + " has a non-string key");
            }
        }
        return (Map<String, Object>) map;
    }

    private static void onlyKeys(Map<String, Object> map, Set<String> allowed, String what) {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw invalid("Unknown field '" + shorten(key) + "' in " + what);
            }
        }
    }

    private static String requiredString(Map<String, Object> map, String key, String what) {
        Object value = map.get(key);
        if (value == null) {
            throw invalid(key + " is required in " + what);
        }
        if (!(value instanceof String s) || s.isBlank()) {
            throw invalid(key + " must be a non-blank string in " + what);
        }
        return s;
    }

    private static String text(Object value, String what) {
        if (!(value instanceof String s)) {
            throw invalid(what + " must be a string");
        }
        return s;
    }

    private static String shorten(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }

    private static RuntimeApiException invalid(String message) {
        return new RuntimeApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    /** The one place that decides what is hashed. Sorted keys come from the mapper; {@code system} only for the estimate. */
    private static Map<String, Object> canonical(LlmRequest r, boolean includeSystem) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", r.model());
        out.put("maxTokens", r.maxTokens());
        List<Map<String, Object>> turns = new ArrayList<>();
        for (LlmRequest.Msg message : r.messages()) {
            turns.add(Map.of("role", message.role(), "content", message.content()));
        }
        out.put("messages", turns);
        if (!r.tools().isEmpty()) {
            List<Map<String, Object>> declared = new ArrayList<>();
            for (LlmRequest.Tool tool : r.tools()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", tool.name());
                if (tool.description() != null) {
                    entry.put("description", tool.description());
                }
                entry.put("inputSchema", tool.inputSchema());
                declared.add(entry);
            }
            out.put("tools", declared);
        }
        if (r.temperature() != null) {
            out.put("temperature", r.temperature());
        }
        if (includeSystem && r.system() != null) {
            out.put("system", r.system());
        }
        return out;
    }

    private static byte[] canonicalBytes(LlmRequest request, boolean includeSystem) {
        try {
            return CANONICAL.writeValueAsBytes(canonical(request, includeSystem));
        } catch (JsonProcessingException e) {
            throw invalid("The request cannot be serialised");
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 6: Implement the budget**

In `execution/RunRepository.java`, add these two methods before the closing brace:

```java

    /**
     * Reserves LLM tokens for a call if they fit the run's budget: one conditional statement, so concurrent calls can
     * never oversubscribe {@code maxTokensPerRun}. Returns 1 when reserved, 0 when it does not fit.
     */
    @Transactional
    @Modifying
    @Query(value = "UPDATE run SET llm_tokens_reserved = llm_tokens_reserved + :tokens "
            + "WHERE id = :id AND llm_tokens_used + llm_tokens_reserved + :tokens <= :max", nativeQuery = true)
    int reserveLlmTokens(@Param("id") UUID id, @Param("tokens") int tokens, @Param("max") long max);

    /** Moves a reservation to actual usage in one statement (release = {@code used} of 0). */
    @Transactional
    @Modifying
    @Query(value = "UPDATE run SET llm_tokens_reserved = llm_tokens_reserved - :reserved, "
            + "llm_tokens_used = llm_tokens_used + :used WHERE id = :id", nativeQuery = true)
    int settleLlmTokens(@Param("id") UUID id, @Param("reserved") int reserved, @Param("used") int used);
```

`llm/LlmBudget.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.execution.RunRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * A run's LLM token budget. Reserving and settling are single atomic statements in their own short transactions; no
 * database transaction is held across a provider call.
 */
@Component
public class LlmBudget {

    private final RunRepository runs;

    public LlmBudget(RunRepository runs) {
        this.runs = runs;
    }

    /** True when {@code tokens} were reserved; false when they do not fit {@code maxTokensPerRun} (or are not positive). */
    public boolean reserve(UUID runId, long tokens, long maxTokensPerRun) {
        if (tokens < 1 || tokens > maxTokensPerRun) {
            return false;
        }
        return runs.reserveLlmTokens(runId, Math.toIntExact(tokens), maxTokensPerRun) == 1;
    }

    /** Turns a reservation into actual usage. The actual count may exceed the reservation. */
    public void settle(UUID runId, long reserved, long usedTokens) {
        int used = (int) Math.min(Integer.MAX_VALUE, Math.max(0, usedTokens));
        runs.settleLlmTokens(runId, Math.toIntExact(reserved), used);
    }

    /** Gives a reservation back; {@code used} does not change. */
    public void release(UUID runId, long reserved) {
        settle(runId, reserved, 0);
    }
}
```

- [ ] **Step 7: Run both tests to see them pass**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='LlmRequestNormalizerTest,LlmBudgetTest'`
Expected: PASS (14 normalizer tests, 6 budget tests).

- [ ] **Step 8: Commit**

```bash
git add backend
git commit -m "feat(llm): request normalizer with canonical hash, and atomic token budget"
```

---

### Task 4: Provider adapters and `ProviderClient`

**Files:**
- Modify: `backend/pom.xml`, `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/agentstore/platform/llm/LlmProperties.java`, `LlmProvider.java`, `ProviderCallException.java`, `ProviderHttp.java`, `AnthropicProvider.java`, `OpenAiProvider.java`, `ProviderClient.java`
- Test: `backend/src/test/java/com/agentstore/platform/llm/StubProviderServer.java`, `AnthropicProviderTest.java`, `OpenAiProviderTest.java`, `ProviderClientTest.java`

**Interfaces:**
- Consumes: `LlmRequest`, `LlmResponse` (Task 3); `LlmVendor` (Task 1); `RuntimeApiException`.
- Produces:
  - `LlmProperties` (`@Component`): `int maxOutputTokens()`, `Duration recordingTtl()`, `Duration retryBackoff()`, `Duration connectTimeout()`, `Duration readTimeout()`, `String baseUrl(LlmVendor)`, `Optional<String> platformKey(LlmVendor)`, `BreakerSettings breaker()` with `record BreakerSettings(int windowSize, int minimumCalls, float failureRatePercent, Duration openDuration)`. Its `toString()` never includes keys.
  - `interface LlmProvider { LlmVendor vendor(); LlmResponse chat(LlmRequest request, String apiKey) throws ProviderCallException; }`
  - `ProviderCallException extends Exception`: `Kind kind()` (`RATE_LIMITED, SERVER_ERROR, CLIENT_ERROR, TIMEOUT, IO, BAD_RESPONSE`), `int status()`, `Duration retryAfter()` (nullable), `boolean retryable()`, `boolean countsAsBreakerFailure()`. The message never contains a provider body.
  - `ProviderClient` (`@Component`): `LlmResponse chat(LlmVendor vendor, LlmRequest request, String apiKey)` throwing `RuntimeApiException` 502 `LLM_PROVIDER_ERROR` / 503 `LLM_PROVIDER_UNAVAILABLE` / 504 `LLM_PROVIDER_TIMEOUT`; `void resetBreakers()`; `CircuitBreaker breaker(LlmVendor)`; package-private `static Duration retryWait(ProviderCallException, Duration backoff)`; package-private constructor `ProviderClient(List<LlmProvider>, LlmProperties.BreakerSettings, Duration backoff, Consumer<Duration> sleeper)`.
  - Test helper `StubProviderServer` (JDK `HttpServer`): `static StubProviderServer start()`, `String baseUrl()`, `enqueue(Reply...)`, `always(Reply)`, `List<Received> received()`, `reset()`, `stop()`; `record Reply(int status, String body, Map<String,String> headers, long delayMillis)` with `Reply.ok(body)`, `Reply.status(status, body)`, `.withHeader(name, value)`, `.delayed(millis)`, `Reply.anthropicText(text, in, out)`, `Reply.openAiText(text, in, out)`; `record Received(String path, Map<String,String> headers, String body)` with `header(name)`.

- [ ] **Step 1: Add the dependency and the configuration**

In `backend/pom.xml`, add the property next to `bucket4j.version`:

```xml
    <resilience4j.version>2.2.0</resilience4j.version>
```

and, after the `commons-csv` dependency:

```xml
    <dependency>
      <groupId>io.github.resilience4j</groupId>
      <artifactId>resilience4j-circuitbreaker</artifactId>
      <version>${resilience4j.version}</version>
    </dependency>
```

Run: `cd backend && mvn -q dependency:resolve`
Expected: no error. (If `2.2.0` cannot be resolved, use the newest 2.x release from Maven Central; the API used here, `CircuitBreaker.of`, `tryAcquirePermission`, `onSuccess`, `onError`, `releasePermission`, `reset`, is stable across 2.x.)

In `backend/src/main/resources/application.yml`, replace

```yaml
    bucket: ${STORAGE_BUCKET:agentstore}

springdoc:
```

with this (the new `llm:` block stays under `agentstore:`, at two spaces of indent, and `springdoc:` is unchanged):

```yaml
    bucket: ${STORAGE_BUCKET:agentstore}
  llm:
    max-tokens-per-run-limit: 5000000
    max-output-tokens: 32000
    recording-ttl: P14D
    retention-interval: PT1H
    retry-backoff: PT0.25S
    connect-timeout: PT5S
    read-timeout: PT120S
    breaker:
      window-size: 20
      minimum-calls: 10
      failure-rate-percent: 50
      open-duration: PT30S
    providers:
      anthropic:
        base-url: https://api.anthropic.com
        api-key: ${ANTHROPIC_API_KEY:}
      openai:
        base-url: https://api.openai.com
        api-key: ${OPENAI_API_KEY:}

springdoc:
```

- [ ] **Step 2: Write the stub server and the failing adapter tests**

`backend/src/test/java/com/agentstore/platform/llm/StubProviderServer.java`:

```java
package com.agentstore.platform.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** A programmable LLM provider on a random local port (the JDK's HttpServer, so no new test dependency). */
public final class StubProviderServer {

    public record Received(String path, Map<String, String> headers, String body) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    public record Reply(int status, String body, Map<String, String> headers, long delayMillis) {

        public static Reply ok(String body) {
            return new Reply(200, body, Map.of(), 0);
        }

        public static Reply status(int status, String body) {
            return new Reply(status, body, Map.of(), 0);
        }

        public Reply withHeader(String name, String value) {
            Map<String, String> merged = new LinkedHashMap<>(headers);
            merged.put(name, value);
            return new Reply(status, body, merged, delayMillis);
        }

        public Reply delayed(long millis) {
            return new Reply(status, body, headers, millis);
        }

        public static Reply anthropicText(String text, int inputTokens, int outputTokens) {
            return ok("{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"test-claude\","
                    + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],\"stop_reason\":\"end_turn\","
                    + "\"usage\":{\"input_tokens\":" + inputTokens + ",\"output_tokens\":" + outputTokens + "}}");
        }

        public static Reply openAiText(String text, int promptTokens, int completionTokens) {
            return ok("{\"id\":\"chatcmpl-1\",\"model\":\"test-gpt\",\"choices\":[{\"index\":0,\"message\":"
                    + "{\"role\":\"assistant\",\"content\":\"" + text + "\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":" + promptTokens + ",\"completion_tokens\":" + completionTokens + "}}");
        }
    }

    private final HttpServer server;
    private final Queue<Reply> queued = new ConcurrentLinkedQueue<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Reply fallback;

    private StubProviderServer(HttpServer server) {
        this.server = server;
    }

    public static StubProviderServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            StubProviderServer stub = new StubProviderServer(server);
            server.createContext("/", stub::handle);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Replies used once each, in order, before {@link #always} applies. */
    public void enqueue(Reply... replies) {
        queued.addAll(List.of(replies));
    }

    /** The reply once the queue is empty (default: a 500). */
    public void always(Reply reply) {
        this.fallback = reply;
    }

    public List<Received> received() {
        return received;
    }

    public void reset() {
        queued.clear();
        received.clear();
        fallback = null;
    }

    public void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.get(0)));
        received.add(new Received(exchange.getRequestURI().getPath(), headers, body));

        Reply reply = queued.poll();
        if (reply == null) {
            reply = fallback;
        }
        if (reply == null) {
            reply = Reply.status(500, "{\"error\":\"stub has no reply queued\"}");
        }
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
```

`backend/src/test/java/com/agentstore/platform/llm/AnthropicProviderTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.llm.ProviderCallException.Kind;
import com.agentstore.platform.llm.StubProviderServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicProviderTest {

    static final StubProviderServer STUB = StubProviderServer.start();
    final ObjectMapper mapper = new ObjectMapper();

    @AfterAll
    static void stop() {
        STUB.stop();
    }

    @BeforeEach
    void reset() {
        STUB.reset();
    }

    AnthropicProvider provider(String baseUrl, Duration readTimeout) {
        return new AnthropicProvider(baseUrl, ProviderHttp.create(Duration.ofSeconds(2), readTimeout, mapper), mapper);
    }

    AnthropicProvider provider() {
        return provider(STUB.baseUrl(), Duration.ofSeconds(5));
    }

    static LlmRequest simple() {
        return new LlmRequest("claude-x", null,
                List.of(new LlmRequest.Msg("user", List.of(Map.of("type", "text", "text", "hello")))),
                List.of(), 256, null);
    }

    interface Call {
        void run() throws ProviderCallException;
    }

    void expect(Call call, Kind kind) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ProviderCallException.class,
                e -> assertThat(e.kind()).isEqualTo(kind));
    }

    @Test
    void translatesTheRequestToTheMessagesApi() throws Exception {
        STUB.enqueue(Reply.anthropicText("ok", 1, 1));
        LlmRequest request = new LlmRequest("claude-x", "be brief", List.of(
                new LlmRequest.Msg("user", List.of(Map.of("type", "text", "text", "hello"))),
                new LlmRequest.Msg("assistant", List.of(
                        Map.of("type", "tool_use", "id", "t1", "name", "lookup", "input", Map.of("q", "x")))),
                new LlmRequest.Msg("user", List.of(
                        Map.of("type", "tool_result", "toolUseId", "t1", "content", "42")))),
                List.of(new LlmRequest.Tool("lookup", "Looks things up", Map.of("type", "object"))), 256, 0.5);

        provider().chat(request, "sk-test");

        var received = STUB.received().get(0);
        JsonNode sent = mapper.readTree(received.body());
        assertThat(received.path()).isEqualTo("/v1/messages");
        assertThat(received.header("x-api-key")).isEqualTo("sk-test");
        assertThat(received.header("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(sent.get("model").asText()).isEqualTo("claude-x");
        assertThat(sent.get("max_tokens").asInt()).isEqualTo(256);
        assertThat(sent.get("system").asText()).isEqualTo("be brief");
        assertThat(sent.get("temperature").asDouble()).isEqualTo(0.5);
        assertThat(sent.at("/messages/0/role").asText()).isEqualTo("user");
        assertThat(sent.at("/messages/0/content/0/text").asText()).isEqualTo("hello");
        assertThat(sent.at("/messages/1/content/0/type").asText()).isEqualTo("tool_use");
        assertThat(sent.at("/messages/1/content/0/input/q").asText()).isEqualTo("x");
        assertThat(sent.at("/messages/2/content/0/type").asText()).isEqualTo("tool_result");
        assertThat(sent.at("/messages/2/content/0/tool_use_id").asText()).isEqualTo("t1");
        assertThat(sent.at("/messages/2/content/0/content").asText()).isEqualTo("42");
        assertThat(sent.at("/tools/0/name").asText()).isEqualTo("lookup");
        assertThat(sent.at("/tools/0/description").asText()).isEqualTo("Looks things up");
        assertThat(sent.at("/tools/0/input_schema/type").asText()).isEqualTo("object");
    }

    @Test
    void omitsOptionalFieldsThatWereNotSent() throws Exception {
        STUB.enqueue(Reply.anthropicText("ok", 1, 1));

        provider().chat(simple(), "sk-test");

        JsonNode sent = mapper.readTree(STUB.received().get(0).body());
        assertThat(sent.has("system")).isFalse();
        assertThat(sent.has("temperature")).isFalse();
        assertThat(sent.has("tools")).isFalse();
    }

    @Test
    void parsesTextAndToolUseAndSkipsOtherBlockTypes() throws Exception {
        STUB.enqueue(Reply.ok("""
                {"model":"claude-x","content":[
                  {"type":"thinking","thinking":"hmm"},
                  {"type":"text","text":"Hi there"},
                  {"type":"tool_use","id":"tu_1","name":"lookup","input":{"q":"y"}}],
                 "stop_reason":"tool_use","usage":{"input_tokens":21,"output_tokens":9}}"""));

        LlmResponse response = provider().chat(simple(), "sk-test");

        assertThat(response.model()).isEqualTo("claude-x");
        assertThat(response.content()).hasSize(2);
        assertThat(response.content().get(0)).containsEntry("type", "text").containsEntry("text", "Hi there");
        assertThat(response.content().get(1)).containsEntry("type", "tool_use").containsEntry("id", "tu_1")
                .containsEntry("name", "lookup").containsEntry("input", Map.of("q", "y"));
        assertThat(response.stopReason()).isEqualTo("tool_use");
        assertThat(response.inputTokens()).isEqualTo(21);
        assertThat(response.outputTokens()).isEqualTo(9);
    }

    @Test
    void mapsStopReasons() throws Exception {
        for (var pair : Map.of("end_turn", "end_turn", "max_tokens", "max_tokens", "tool_use", "tool_use",
                "stop_sequence", "other", "refusal", "other").entrySet()) {
            STUB.enqueue(Reply.ok("{\"content\":[],\"stop_reason\":\"" + pair.getKey()
                    + "\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"));
            assertThat(provider().chat(simple(), "k").stopReason()).isEqualTo(pair.getValue());
        }
    }

    @Test
    void classifiesFailuresWithoutTheirBodies() {
        STUB.enqueue(Reply.status(429, "{}").withHeader("Retry-After", "3"));
        assertThatThrownBy(() -> provider().chat(simple(), "k")).isInstanceOfSatisfying(ProviderCallException.class, e -> {
            assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED);
            assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(3));
            assertThat(e.retryable()).isTrue();
        });

        STUB.enqueue(Reply.status(529, "{}"));
        expect(() -> provider().chat(simple(), "k"), Kind.SERVER_ERROR);

        STUB.enqueue(Reply.status(400, "{}"));
        assertThatThrownBy(() -> provider().chat(simple(), "k")).isInstanceOfSatisfying(ProviderCallException.class, e -> {
            assertThat(e.kind()).isEqualTo(Kind.CLIENT_ERROR);
            assertThat(e.retryable()).isFalse();
            assertThat(e.countsAsBreakerFailure()).isFalse();
        });

        STUB.enqueue(Reply.ok("this is not json"));
        expect(() -> provider().chat(simple(), "k"), Kind.BAD_RESPONSE);

        STUB.enqueue(Reply.ok("{\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}"));
        expect(() -> provider().chat(simple(), "k"), Kind.BAD_RESPONSE);

        STUB.enqueue(Reply.ok("{\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"));
        expect(() -> provider().chat(simple(), "k"), Kind.BAD_RESPONSE);
    }

    @Test
    void aSlowProviderTimesOut() {
        STUB.enqueue(Reply.anthropicText("late", 1, 1).delayed(1500));

        expect(() -> provider(STUB.baseUrl(), Duration.ofMillis(200)).chat(simple(), "k"), Kind.TIMEOUT);
    }

    @Test
    void aRefusedConnectionIsAnIoError() {
        expect(() -> provider("http://127.0.0.1:1", Duration.ofSeconds(2)).chat(simple(), "k"), Kind.IO);
    }

    @Test
    void exceptionMessagesNeverCarryTheProviderBodyOrTheKey() {
        STUB.enqueue(Reply.status(401, "{\"error\":{\"message\":\"invalid x-api-key sk-secret-999 for prompt hello\"}}"));

        assertThatThrownBy(() -> provider().chat(simple(), "sk-secret-999"))
                .isInstanceOfSatisfying(ProviderCallException.class, e -> {
                    assertThat(e.getMessage()).doesNotContain("sk-secret-999").doesNotContain("invalid")
                            .doesNotContain("hello");
                    assertThat(e.getCause()).isNull();
                });
    }
}
```

`backend/src/test/java/com/agentstore/platform/llm/OpenAiProviderTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.llm.ProviderCallException.Kind;
import com.agentstore.platform.llm.StubProviderServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiProviderTest {

    static final StubProviderServer STUB = StubProviderServer.start();
    final ObjectMapper mapper = new ObjectMapper();

    @AfterAll
    static void stop() {
        STUB.stop();
    }

    @BeforeEach
    void reset() {
        STUB.reset();
    }

    OpenAiProvider provider() {
        return new OpenAiProvider(STUB.baseUrl(), ProviderHttp.create(Duration.ofSeconds(2), Duration.ofSeconds(5), mapper), mapper);
    }

    static LlmRequest simple() {
        return new LlmRequest("gpt-x", null,
                List.of(new LlmRequest.Msg("user", List.of(Map.of("type", "text", "text", "hello")))),
                List.of(), 128, null);
    }

    @Test
    void translatesTheRequestToChatCompletions() throws Exception {
        STUB.enqueue(Reply.openAiText("ok", 1, 1));
        LlmRequest request = new LlmRequest("gpt-x", "be brief", List.of(
                new LlmRequest.Msg("user", List.of(Map.of("type", "text", "text", "hello"))),
                new LlmRequest.Msg("assistant", List.of(
                        Map.of("type", "text", "text", "Let me check."),
                        Map.of("type", "tool_use", "id", "t1", "name", "lookup", "input", Map.of("q", "x")))),
                new LlmRequest.Msg("user", List.of(
                        Map.of("type", "tool_result", "toolUseId", "t1", "content", "42"),
                        Map.of("type", "text", "text", "thanks")))),
                List.of(new LlmRequest.Tool("lookup", "Looks things up", Map.of("type", "object"))), 128, 0.2);

        provider().chat(request, "sk-openai");

        var received = STUB.received().get(0);
        JsonNode sent = mapper.readTree(received.body());
        assertThat(received.path()).isEqualTo("/v1/chat/completions");
        assertThat(received.header("authorization")).isEqualTo("Bearer sk-openai");
        assertThat(sent.get("model").asText()).isEqualTo("gpt-x");
        assertThat(sent.get("max_completion_tokens").asInt()).isEqualTo(128);
        assertThat(sent.get("store").asBoolean()).isFalse();
        assertThat(sent.get("temperature").asDouble()).isEqualTo(0.2);
        assertThat(sent.at("/messages/0/role").asText()).isEqualTo("system");
        assertThat(sent.at("/messages/0/content").asText()).isEqualTo("be brief");
        assertThat(sent.at("/messages/1/role").asText()).isEqualTo("user");
        assertThat(sent.at("/messages/1/content").asText()).isEqualTo("hello");
        assertThat(sent.at("/messages/2/role").asText()).isEqualTo("assistant");
        assertThat(sent.at("/messages/2/content").asText()).isEqualTo("Let me check.");
        assertThat(sent.at("/messages/2/tool_calls/0/id").asText()).isEqualTo("t1");
        assertThat(sent.at("/messages/2/tool_calls/0/type").asText()).isEqualTo("function");
        assertThat(sent.at("/messages/2/tool_calls/0/function/name").asText()).isEqualTo("lookup");
        assertThat(mapper.readTree(sent.at("/messages/2/tool_calls/0/function/arguments").asText()).get("q").asText())
                .isEqualTo("x");
        assertThat(sent.at("/messages/3/role").asText()).isEqualTo("tool");
        assertThat(sent.at("/messages/3/tool_call_id").asText()).isEqualTo("t1");
        assertThat(sent.at("/messages/3/content").asText()).isEqualTo("42");
        assertThat(sent.at("/messages/4/role").asText()).isEqualTo("user");
        assertThat(sent.at("/messages/4/content").asText()).isEqualTo("thanks");
        assertThat(sent.at("/tools/0/type").asText()).isEqualTo("function");
        assertThat(sent.at("/tools/0/function/name").asText()).isEqualTo("lookup");
        assertThat(sent.at("/tools/0/function/description").asText()).isEqualTo("Looks things up");
        assertThat(sent.at("/tools/0/function/parameters/type").asText()).isEqualTo("object");
    }

    @Test
    void anAssistantTurnWithOnlyToolCallsSendsNullContent() throws Exception {
        STUB.enqueue(Reply.openAiText("ok", 1, 1));
        LlmRequest request = new LlmRequest("gpt-x", null, List.of(
                new LlmRequest.Msg("user", List.of(Map.of("type", "text", "text", "hello"))),
                new LlmRequest.Msg("assistant", List.of(
                        Map.of("type", "tool_use", "id", "t1", "name", "lookup", "input", Map.of())))),
                List.of(), 128, null);

        provider().chat(request, "k");

        JsonNode sent = mapper.readTree(STUB.received().get(0).body());
        assertThat(sent.at("/messages/1/content").isNull()).isTrue();
        assertThat(sent.at("/messages/1/tool_calls/0/function/arguments").asText()).isEqualTo("{}");
        assertThat(sent.has("temperature")).isFalse();
        assertThat(sent.has("tools")).isFalse();
    }

    @Test
    void parsesTextAndToolCalls() throws Exception {
        STUB.enqueue(Reply.ok("""
                {"model":"gpt-x","choices":[{"index":0,"message":{"role":"assistant","content":"Working on it",
                  "tool_calls":[{"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{\\"q\\":\\"y\\"}"}}]},
                  "finish_reason":"tool_calls"}],"usage":{"prompt_tokens":30,"completion_tokens":12}}"""));

        LlmResponse response = provider().chat(simple(), "k");

        assertThat(response.model()).isEqualTo("gpt-x");
        assertThat(response.content()).hasSize(2);
        assertThat(response.content().get(0)).containsEntry("type", "text").containsEntry("text", "Working on it");
        assertThat(response.content().get(1)).containsEntry("type", "tool_use").containsEntry("id", "call_1")
                .containsEntry("name", "lookup").containsEntry("input", Map.of("q", "y"));
        assertThat(response.stopReason()).isEqualTo("tool_use");
        assertThat(response.inputTokens()).isEqualTo(30);
        assertThat(response.outputTokens()).isEqualTo(12);
    }

    @Test
    void mapsFinishReasons() throws Exception {
        for (var pair : Map.of("stop", "end_turn", "length", "max_tokens", "tool_calls", "tool_use",
                "content_filter", "other").entrySet()) {
            STUB.enqueue(Reply.ok("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"x\"},\"finish_reason\":\""
                    + pair.getKey() + "\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"));
            assertThat(provider().chat(simple(), "k").stopReason()).isEqualTo(pair.getValue());
        }
    }

    @Test
    void classifiesFailuresAndUnparseableReplies() {
        STUB.enqueue(Reply.status(429, "{}"));
        assertThatThrownBy(() -> provider().chat(simple(), "k")).isInstanceOfSatisfying(ProviderCallException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED));

        STUB.enqueue(Reply.status(503, "{}"));
        assertThatThrownBy(() -> provider().chat(simple(), "k")).isInstanceOfSatisfying(ProviderCallException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.SERVER_ERROR));

        STUB.enqueue(Reply.status(401, "{\"error\":\"bad key sk-secret-1\"}"));
        assertThatThrownBy(() -> provider().chat(simple(), "sk-secret-1")).isInstanceOfSatisfying(ProviderCallException.class,
                e -> {
                    assertThat(e.kind()).isEqualTo(Kind.CLIENT_ERROR);
                    assertThat(e.getMessage()).doesNotContain("sk-secret-1");
                });

        STUB.enqueue(Reply.ok("{\"choices\":[]}"));
        assertThatThrownBy(() -> provider().chat(simple(), "k")).isInstanceOfSatisfying(ProviderCallException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE));

        STUB.enqueue(Reply.ok("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":"
                + "[{\"id\":\"c\",\"function\":{\"name\":\"n\",\"arguments\":\"not json\"}}]},\"finish_reason\":\"tool_calls\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"));
        assertThatThrownBy(() -> provider().chat(simple(), "k")).isInstanceOfSatisfying(ProviderCallException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE));
    }
}
```

- [ ] **Step 3: Write the failing `ProviderClientTest`**

`backend/src/test/java/com/agentstore/platform/llm/ProviderClientTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.llm.ProviderCallException.Kind;
import com.agentstore.platform.manifest.LlmVendor;
import com.agentstore.platform.runtime.RuntimeApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderClientTest {

    static final LlmRequest REQUEST = new LlmRequest("m", null,
            List.of(new LlmRequest.Msg("user", List.of(Map.of("type", "text", "text", "hi")))), List.of(), 100, null);
    static final LlmResponse RESPONSE = new LlmResponse("m", List.of(Map.of("type", "text", "text", "ok")), "end_turn", 10, 5);
    static final Duration BACKOFF = Duration.ofMillis(250);

    /** Plays back a script of outcomes (an LlmResponse or a ProviderCallException); succeeds once the script is empty. */
    static final class FakeProvider implements LlmProvider {
        final LlmVendor vendor;
        final Deque<Object> script = new ArrayDeque<>();
        int calls;

        FakeProvider(LlmVendor vendor) {
            this.vendor = vendor;
        }

        FakeProvider then(Object... outcomes) {
            script.addAll(List.of(outcomes));
            return this;
        }

        @Override
        public LlmVendor vendor() {
            return vendor;
        }

        @Override
        public LlmResponse chat(LlmRequest request, String apiKey) throws ProviderCallException {
            calls++;
            Object next = script.poll();
            if (next instanceof ProviderCallException e) {
                throw e;
            }
            return next == null ? RESPONSE : (LlmResponse) next;
        }
    }

    static ProviderCallException failure(Kind kind, int status) {
        return new ProviderCallException(kind, status, null);
    }

    static final LlmProperties.BreakerSettings SETTINGS = new LlmProperties.BreakerSettings(4, 2, 50f, Duration.ofSeconds(30));
    final List<Duration> sleeps = new ArrayList<>();

    ProviderClient client(LlmProvider... providers) {
        return new ProviderClient(List.of(providers), SETTINGS, BACKOFF, sleeps::add);
    }

    void assertApi(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(RuntimeApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    @Test
    void aSuccessfulCallIsNotRetried() {
        var anthropic = new FakeProvider(LlmVendor.ANTHROPIC);

        assertThat(client(anthropic).chat(LlmVendor.ANTHROPIC, REQUEST, "k")).isEqualTo(RESPONSE);

        assertThat(anthropic.calls).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aTransientFailureIsRetriedOnceAfterTheBackoff() {
        for (Kind kind : List.of(Kind.SERVER_ERROR, Kind.RATE_LIMITED, Kind.TIMEOUT, Kind.IO)) {
            sleeps.clear();
            var anthropic = new FakeProvider(LlmVendor.ANTHROPIC).then(failure(kind, 500));

            assertThat(client(anthropic).chat(LlmVendor.ANTHROPIC, REQUEST, "k")).isEqualTo(RESPONSE);

            assertThat(anthropic.calls).as(kind.name()).isEqualTo(2);
            assertThat(sleeps).containsExactly(BACKOFF);
        }
    }

    @Test
    void retryAfterIsHonouredAndCappedAtTwoSeconds() {
        assertThat(ProviderClient.retryWait(new ProviderCallException(Kind.RATE_LIMITED, 429, Duration.ofSeconds(1)), BACKOFF))
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(ProviderClient.retryWait(new ProviderCallException(Kind.RATE_LIMITED, 429, Duration.ofSeconds(30)), BACKOFF))
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(ProviderClient.retryWait(new ProviderCallException(Kind.SERVER_ERROR, 500, null), BACKOFF))
                .isEqualTo(BACKOFF);
        assertThat(ProviderClient.retryWait(new ProviderCallException(Kind.SERVER_ERROR, 500, null), Duration.ofSeconds(9)))
                .isEqualTo(Duration.ofSeconds(2));

        var anthropic = new FakeProvider(LlmVendor.ANTHROPIC)
                .then(new ProviderCallException(Kind.RATE_LIMITED, 429, Duration.ofSeconds(1)));
        client(anthropic).chat(LlmVendor.ANTHROPIC, REQUEST, "k");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void afterTheRetryEachKindMapsToItsErrorContract() {
        assertApi(() -> client(new FakeProvider(LlmVendor.ANTHROPIC).then(failure(Kind.SERVER_ERROR, 500), failure(Kind.SERVER_ERROR, 502)))
                .chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.BAD_GATEWAY, "LLM_PROVIDER_ERROR");
        assertApi(() -> client(new FakeProvider(LlmVendor.ANTHROPIC).then(failure(Kind.RATE_LIMITED, 429), failure(Kind.RATE_LIMITED, 429)))
                .chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.SERVICE_UNAVAILABLE, "LLM_PROVIDER_UNAVAILABLE");
        assertApi(() -> client(new FakeProvider(LlmVendor.ANTHROPIC).then(failure(Kind.TIMEOUT, 0), failure(Kind.TIMEOUT, 0)))
                .chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.GATEWAY_TIMEOUT, "LLM_PROVIDER_TIMEOUT");
        assertApi(() -> client(new FakeProvider(LlmVendor.ANTHROPIC).then(failure(Kind.IO, 0), failure(Kind.IO, 0)))
                .chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.BAD_GATEWAY, "LLM_PROVIDER_ERROR");
    }

    @Test
    void clientErrorsAndUnparseableRepliesAreNotRetried() {
        var rejected = new FakeProvider(LlmVendor.ANTHROPIC).then(failure(Kind.CLIENT_ERROR, 401));
        assertApi(() -> client(rejected).chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.BAD_GATEWAY, "LLM_PROVIDER_ERROR");
        assertThat(rejected.calls).isEqualTo(1);

        var garbled = new FakeProvider(LlmVendor.ANTHROPIC).then(failure(Kind.BAD_RESPONSE, 200));
        assertApi(() -> client(garbled).chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.BAD_GATEWAY, "LLM_PROVIDER_ERROR");
        assertThat(garbled.calls).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void repeatedTransientFailuresOpenTheBreakerAndLaterCallsFailFast() {
        var anthropic = new FakeProvider(LlmVendor.ANTHROPIC).then(failure(Kind.SERVER_ERROR, 500), failure(Kind.SERVER_ERROR, 500));
        ProviderClient client = client(anthropic);

        assertApi(() -> client.chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.BAD_GATEWAY, "LLM_PROVIDER_ERROR");
        assertThat(anthropic.calls).isEqualTo(2);

        assertApi(() -> client.chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.SERVICE_UNAVAILABLE, "LLM_PROVIDER_UNAVAILABLE");
        assertThat(anthropic.calls).as("an open circuit does not reach the provider").isEqualTo(2);
    }

    @Test
    void theBreakerIsPerProviderAndCanBeReset() {
        var anthropic = new FakeProvider(LlmVendor.ANTHROPIC).then(failure(Kind.SERVER_ERROR, 500), failure(Kind.SERVER_ERROR, 500));
        var openai = new FakeProvider(LlmVendor.OPENAI);
        ProviderClient client = client(anthropic, openai);
        assertApi(() -> client.chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.BAD_GATEWAY, "LLM_PROVIDER_ERROR");

        assertThat(client.chat(LlmVendor.OPENAI, REQUEST, "k")).isEqualTo(RESPONSE);
        assertApi(() -> client.chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.SERVICE_UNAVAILABLE, "LLM_PROVIDER_UNAVAILABLE");

        client.resetBreakers();
        assertThat(client.chat(LlmVendor.ANTHROPIC, REQUEST, "k")).isEqualTo(RESPONSE);
    }

    @Test
    void clientErrorsNeverOpenTheBreaker() {
        var anthropic = new FakeProvider(LlmVendor.ANTHROPIC);
        for (int i = 0; i < 10; i++) {
            anthropic.then(failure(Kind.CLIENT_ERROR, 400));
        }
        ProviderClient client = client(anthropic);

        for (int i = 0; i < 10; i++) {
            assertApi(() -> client.chat(LlmVendor.ANTHROPIC, REQUEST, "k"), HttpStatus.BAD_GATEWAY, "LLM_PROVIDER_ERROR");
        }

        assertThat(client.chat(LlmVendor.ANTHROPIC, REQUEST, "k")).isEqualTo(RESPONSE);
    }

    @Test
    void aVendorWithoutAnAdapterIsUnavailable() {
        assertApi(() -> client(new FakeProvider(LlmVendor.ANTHROPIC)).chat(LlmVendor.OPENAI, REQUEST, "k"),
                HttpStatus.SERVICE_UNAVAILABLE, "LLM_PROVIDER_UNAVAILABLE");
    }
}
```

- [ ] **Step 4: Run them to see them fail**

Run: `cd backend && mvn -q test -Dtest='AnthropicProviderTest,OpenAiProviderTest,ProviderClientTest'`
Expected: FAIL to compile (`LlmProperties`, `LlmProvider`, `ProviderCallException`, `ProviderHttp`, adapters and `ProviderClient` do not exist).

- [ ] **Step 5: Implement `LlmProperties`, `LlmProvider` and `ProviderCallException`**

`llm/LlmProperties.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.manifest.LlmVendor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** Every {@code agentstore.llm.*} setting in one place. Platform keys live here only; {@link #toString} never shows them. */
@Component
public class LlmProperties {

    public record BreakerSettings(int windowSize, int minimumCalls, float failureRatePercent, Duration openDuration) {
    }

    private final int maxOutputTokens;
    private final Duration recordingTtl;
    private final Duration retryBackoff;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Map<LlmVendor, String> baseUrls = new EnumMap<>(LlmVendor.class);
    private final Map<LlmVendor, String> platformKeys = new EnumMap<>(LlmVendor.class);
    private final BreakerSettings breaker;

    public LlmProperties(
            @Value("${agentstore.llm.max-output-tokens:32000}") int maxOutputTokens,
            @Value("${agentstore.llm.recording-ttl:P14D}") Duration recordingTtl,
            @Value("${agentstore.llm.retry-backoff:PT0.25S}") Duration retryBackoff,
            @Value("${agentstore.llm.connect-timeout:PT5S}") Duration connectTimeout,
            @Value("${agentstore.llm.read-timeout:PT120S}") Duration readTimeout,
            @Value("${agentstore.llm.providers.anthropic.base-url:https://api.anthropic.com}") String anthropicBaseUrl,
            @Value("${agentstore.llm.providers.anthropic.api-key:}") String anthropicKey,
            @Value("${agentstore.llm.providers.openai.base-url:https://api.openai.com}") String openAiBaseUrl,
            @Value("${agentstore.llm.providers.openai.api-key:}") String openAiKey,
            @Value("${agentstore.llm.breaker.window-size:20}") int breakerWindowSize,
            @Value("${agentstore.llm.breaker.minimum-calls:10}") int breakerMinimumCalls,
            @Value("${agentstore.llm.breaker.failure-rate-percent:50}") float breakerFailureRatePercent,
            @Value("${agentstore.llm.breaker.open-duration:PT30S}") Duration breakerOpenDuration) {
        this.maxOutputTokens = maxOutputTokens;
        this.recordingTtl = recordingTtl;
        this.retryBackoff = retryBackoff;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        baseUrls.put(LlmVendor.ANTHROPIC, stripTrailingSlashes(anthropicBaseUrl));
        baseUrls.put(LlmVendor.OPENAI, stripTrailingSlashes(openAiBaseUrl));
        platformKeys.put(LlmVendor.ANTHROPIC, anthropicKey == null ? "" : anthropicKey.trim());
        platformKeys.put(LlmVendor.OPENAI, openAiKey == null ? "" : openAiKey.trim());
        this.breaker = new BreakerSettings(breakerWindowSize, breakerMinimumCalls, breakerFailureRatePercent,
                breakerOpenDuration);
    }

    private static String stripTrailingSlashes(String url) {
        return url.replaceAll("/+$", "");
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    public Duration recordingTtl() {
        return recordingTtl;
    }

    public Duration retryBackoff() {
        return retryBackoff;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration readTimeout() {
        return readTimeout;
    }

    public String baseUrl(LlmVendor vendor) {
        return baseUrls.get(vendor);
    }

    /** The platform's own key for the provider, or empty when none is configured. */
    public Optional<String> platformKey(LlmVendor vendor) {
        String key = platformKeys.get(vendor);
        return key == null || key.isEmpty() ? Optional.empty() : Optional.of(key);
    }

    public BreakerSettings breaker() {
        return breaker;
    }

    @Override
    public String toString() {
        return "LlmProperties[baseUrls=" + baseUrls + ", maxOutputTokens=" + maxOutputTokens + "]";
    }
}
```

`llm/LlmProvider.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.manifest.LlmVendor;

/**
 * One provider's API, normalized in and out. Exactly one HTTP call per invocation: retries, the circuit breaker and
 * the error contract belong to {@link ProviderClient}.
 */
public interface LlmProvider {

    LlmVendor vendor();

    LlmResponse chat(LlmRequest request, String apiKey) throws ProviderCallException;
}
```

`llm/ProviderCallException.java`:

```java
package com.agentstore.platform.llm;

import java.time.Duration;

/**
 * One failed provider attempt. Deliberately carries no cause and no response body: bodies can echo the prompt or the
 * key, so only the kind, the HTTP status and a {@code Retry-After} hint survive.
 */
public class ProviderCallException extends Exception {

    public enum Kind { RATE_LIMITED, SERVER_ERROR, CLIENT_ERROR, TIMEOUT, IO, BAD_RESPONSE }

    private final Kind kind;
    private final int status;
    private final Duration retryAfter;

    public ProviderCallException(Kind kind, int status, Duration retryAfter) {
        super("LLM provider call failed: " + kind + (status > 0 ? " (HTTP " + status + ")" : ""));
        this.kind = kind;
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    public int status() {
        return status;
    }

    /** The provider's {@code Retry-After} in seconds, when it sent one. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** 429, 5xx, timeouts and I/O errors are retried once. */
    public boolean retryable() {
        return kind == Kind.RATE_LIMITED || kind == Kind.SERVER_ERROR || kind == Kind.TIMEOUT || kind == Kind.IO;
    }

    /** Only the retryable kinds count towards the circuit breaker; other 4xx and odd replies do not. */
    public boolean countsAsBreakerFailure() {
        return retryable();
    }
}
```

- [ ] **Step 6: Implement `ProviderHttp` and the two adapters**

`llm/ProviderHttp.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.llm.ProviderCallException.Kind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/** The JSON-over-HTTP part both adapters share. Classifies every outcome; never keeps a body or a cause. */
final class ProviderHttp {

    private final HttpClient client;
    private final Duration readTimeout;
    private final ObjectMapper mapper;

    ProviderHttp(HttpClient client, Duration readTimeout, ObjectMapper mapper) {
        this.client = client;
        this.readTimeout = readTimeout;
        this.mapper = mapper;
    }

    static ProviderHttp create(Duration connectTimeout, Duration readTimeout, ObjectMapper mapper) {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout).build();
        return new ProviderHttp(client, readTimeout, mapper);
    }

    JsonNode post(String url, Map<String, String> headers, JsonNode body) throws ProviderCallException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(readTimeout)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        headers.forEach(request::header);
        HttpResponse<byte[]> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new ProviderCallException(Kind.TIMEOUT, 0, null);
        } catch (IOException e) {
            throw new ProviderCallException(Kind.IO, 0, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderCallException(Kind.IO, 0, null);
        }
        int status = response.statusCode();
        if (status == 429) {
            throw new ProviderCallException(Kind.RATE_LIMITED, status, retryAfter(response));
        }
        if (status >= 500) {
            throw new ProviderCallException(Kind.SERVER_ERROR, status, null);
        }
        if (status >= 400) {
            throw new ProviderCallException(Kind.CLIENT_ERROR, status, null);
        }
        try {
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new ProviderCallException(Kind.BAD_RESPONSE, status, null);
        }
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("retry-after").map(value -> {
            try {
                long seconds = Long.parseLong(value.trim());
                return seconds < 0 ? null : Duration.ofSeconds(seconds);
            } catch (NumberFormatException e) {
                return null;   // an HTTP-date form is ignored; the configured backoff applies
            }
        }).orElse(null);
    }
}
```

`llm/AnthropicProvider.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.llm.ProviderCallException.Kind;
import com.agentstore.platform.manifest.LlmVendor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Anthropic {@code /v1/messages}. */
@Component
public class AnthropicProvider implements LlmProvider {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final String baseUrl;
    private final ProviderHttp http;
    private final ObjectMapper mapper;

    @Autowired
    public AnthropicProvider(LlmProperties properties, ObjectMapper mapper) {
        this(properties.baseUrl(LlmVendor.ANTHROPIC),
                ProviderHttp.create(properties.connectTimeout(), properties.readTimeout(), mapper), mapper);
    }

    AnthropicProvider(String baseUrl, ProviderHttp http, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public LlmVendor vendor() {
        return LlmVendor.ANTHROPIC;
    }

    @Override
    public LlmResponse chat(LlmRequest request, String apiKey) throws ProviderCallException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens());
        if (request.system() != null) {
            body.put("system", request.system());
        }
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        ArrayNode messages = body.putArray("messages");
        for (LlmRequest.Msg message : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role());
            ArrayNode content = node.putArray("content");
            for (Map<String, Object> block : message.content()) {
                content.add(toBlock(block));
            }
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (LlmRequest.Tool tool : request.tools()) {
                ObjectNode node = tools.addObject();
                node.put("name", tool.name());
                if (tool.description() != null) {
                    node.put("description", tool.description());
                }
                node.set("input_schema", mapper.valueToTree(tool.inputSchema()));
            }
        }
        JsonNode root = http.post(baseUrl + "/v1/messages",
                Map.of("x-api-key", apiKey, "anthropic-version", "2023-06-01"), body);
        return parse(root, request.model());
    }

    private JsonNode toBlock(Map<String, Object> block) {
        ObjectNode node = mapper.createObjectNode();
        switch ((String) block.get("type")) {
            case "text" -> {
                node.put("type", "text");
                node.put("text", (String) block.get("text"));
            }
            case "tool_use" -> {
                node.put("type", "tool_use");
                node.put("id", (String) block.get("id"));
                node.put("name", (String) block.get("name"));
                node.set("input", mapper.valueToTree(block.get("input")));
            }
            case "tool_result" -> {
                node.put("type", "tool_result");
                node.put("tool_use_id", (String) block.get("toolUseId"));
                node.put("content", (String) block.get("content"));
            }
            default -> throw new IllegalStateException("the normalizer let an unknown block type through");
        }
        return node;
    }

    private LlmResponse parse(JsonNode root, String requestedModel) throws ProviderCallException {
        JsonNode usage = root.path("usage");
        if (!root.path("content").isArray() || !usage.path("input_tokens").isNumber()
                || !usage.path("output_tokens").isNumber()) {
            throw new ProviderCallException(Kind.BAD_RESPONSE, 200, null);
        }
        List<Map<String, Object>> content = new ArrayList<>();
        for (JsonNode block : root.get("content")) {
            switch (block.path("type").asText()) {
                case "text" -> {
                    Map<String, Object> text = new LinkedHashMap<>();
                    text.put("type", "text");
                    text.put("text", block.path("text").asText(""));
                    content.add(text);
                }
                case "tool_use" -> {
                    Map<String, Object> use = new LinkedHashMap<>();
                    use.put("type", "tool_use");
                    use.put("id", block.path("id").asText());
                    use.put("name", block.path("name").asText());
                    try {
                        use.put("input", block.hasNonNull("input") ? mapper.convertValue(block.get("input"), MAP)
                                : new LinkedHashMap<String, Object>());
                    } catch (IllegalArgumentException e) {
                        throw new ProviderCallException(Kind.BAD_RESPONSE, 200, null);
                    }
                    content.add(use);
                }
                default -> {
                    // thinking and other block types are not part of the normalized shape
                }
            }
        }
        String model = root.path("model").asText(requestedModel);
        return new LlmResponse(model.isBlank() ? requestedModel : model, content,
                stopReason(root.path("stop_reason").asText()), usage.get("input_tokens").asInt(),
                usage.get("output_tokens").asInt());
    }

    private static String stopReason(String providerReason) {
        return switch (providerReason) {
            case "end_turn" -> "end_turn";
            case "max_tokens" -> "max_tokens";
            case "tool_use" -> "tool_use";
            default -> "other";
        };
    }
}
```

`llm/OpenAiProvider.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.llm.ProviderCallException.Kind;
import com.agentstore.platform.manifest.LlmVendor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenAI {@code /v1/chat/completions}, always with {@code store: false}. */
@Component
public class OpenAiProvider implements LlmProvider {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final String baseUrl;
    private final ProviderHttp http;
    private final ObjectMapper mapper;

    @Autowired
    public OpenAiProvider(LlmProperties properties, ObjectMapper mapper) {
        this(properties.baseUrl(LlmVendor.OPENAI),
                ProviderHttp.create(properties.connectTimeout(), properties.readTimeout(), mapper), mapper);
    }

    OpenAiProvider(String baseUrl, ProviderHttp http, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public LlmVendor vendor() {
        return LlmVendor.OPENAI;
    }

    @Override
    public LlmResponse chat(LlmRequest request, String apiKey) throws ProviderCallException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model());
        body.put("max_completion_tokens", request.maxTokens());
        body.put("store", false);
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        ArrayNode messages = body.putArray("messages");
        if (request.system() != null) {
            messages.addObject().put("role", "system").put("content", request.system());
        }
        for (LlmRequest.Msg message : request.messages()) {
            addMessage(messages, message);
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (LlmRequest.Tool tool : request.tools()) {
                ObjectNode node = tools.addObject();
                node.put("type", "function");
                ObjectNode function = node.putObject("function");
                function.put("name", tool.name());
                if (tool.description() != null) {
                    function.put("description", tool.description());
                }
                function.set("parameters", mapper.valueToTree(tool.inputSchema()));
            }
        }
        JsonNode root = http.post(baseUrl + "/v1/chat/completions", Map.of("authorization", "Bearer " + apiKey), body);
        return parse(root, request.model());
    }

    private void addMessage(ArrayNode messages, LlmRequest.Msg message) {
        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> toolUses = new ArrayList<>();
        List<Map<String, Object>> toolResults = new ArrayList<>();
        for (Map<String, Object> block : message.content()) {
            switch ((String) block.get("type")) {
                case "text" -> text.append((String) block.get("text"));
                case "tool_use" -> toolUses.add(block);
                case "tool_result" -> toolResults.add(block);
                default -> throw new IllegalStateException("the normalizer let an unknown block type through");
            }
        }
        if (message.role().equals("assistant")) {
            ObjectNode node = messages.addObject();
            node.put("role", "assistant");
            if (text.isEmpty() && !toolUses.isEmpty()) {
                node.putNull("content");
            } else {
                node.put("content", text.toString());
            }
            if (!toolUses.isEmpty()) {
                ArrayNode calls = node.putArray("tool_calls");
                for (Map<String, Object> use : toolUses) {
                    ObjectNode call = calls.addObject();
                    call.put("id", (String) use.get("id"));
                    call.put("type", "function");
                    ObjectNode function = call.putObject("function");
                    function.put("name", (String) use.get("name"));
                    JsonNode input = mapper.valueToTree(use.get("input"));
                    function.put("arguments", input.toString());
                }
            }
        } else {
            for (Map<String, Object> result : toolResults) {
                messages.addObject().put("role", "tool").put("tool_call_id", (String) result.get("toolUseId"))
                        .put("content", (String) result.get("content"));
            }
            if (!text.isEmpty() || toolResults.isEmpty()) {
                messages.addObject().put("role", "user").put("content", text.toString());
            }
        }
    }

    private LlmResponse parse(JsonNode root, String requestedModel) throws ProviderCallException {
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        JsonNode usage = root.path("usage");
        if (!message.isObject() || !usage.path("prompt_tokens").isNumber() || !usage.path("completion_tokens").isNumber()) {
            throw new ProviderCallException(Kind.BAD_RESPONSE, 200, null);
        }
        List<Map<String, Object>> content = new ArrayList<>();
        if (message.path("content").isTextual() && !message.get("content").asText().isEmpty()) {
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("type", "text");
            text.put("text", message.get("content").asText());
            content.add(text);
        }
        for (JsonNode call : message.path("tool_calls")) {
            Map<String, Object> use = new LinkedHashMap<>();
            use.put("type", "tool_use");
            use.put("id", call.path("id").asText());
            use.put("name", call.path("function").path("name").asText());
            try {
                JsonNode arguments = mapper.readTree(call.path("function").path("arguments").asText("{}"));
                if (!arguments.isObject()) {
                    throw new ProviderCallException(Kind.BAD_RESPONSE, 200, null);
                }
                use.put("input", mapper.convertValue(arguments, MAP));
            } catch (IOException | IllegalArgumentException e) {
                throw new ProviderCallException(Kind.BAD_RESPONSE, 200, null);
            }
            content.add(use);
        }
        String model = root.path("model").asText(requestedModel);
        return new LlmResponse(model.isBlank() ? requestedModel : model, content,
                stopReason(choice.path("finish_reason").asText()), usage.get("prompt_tokens").asInt(),
                usage.get("completion_tokens").asInt());
    }

    private static String stopReason(String finishReason) {
        return switch (finishReason) {
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            case "tool_calls" -> "tool_use";
            default -> "other";
        };
    }
}
```

- [ ] **Step 7: Implement `ProviderClient`**

`llm/ProviderClient.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.manifest.LlmVendor;
import com.agentstore.platform.runtime.RuntimeApiException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * What stands between the gateway and a provider: one retry on a transient failure, a circuit breaker per provider,
 * and the mapping from provider failures to our error contract. Only provider, model, outcome and latency are logged.
 */
@Component
public class ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(ProviderClient.class);
    private static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(2);

    private final Map<LlmVendor, LlmProvider> providers = new EnumMap<>(LlmVendor.class);
    private final Map<LlmVendor, CircuitBreaker> breakers = new EnumMap<>(LlmVendor.class);
    private final Duration backoff;
    private final Consumer<Duration> sleeper;

    @Autowired
    public ProviderClient(List<LlmProvider> adapters, LlmProperties properties) {
        this(adapters, properties.breaker(), properties.retryBackoff(), ProviderClient::sleep);
    }

    ProviderClient(List<LlmProvider> adapters, LlmProperties.BreakerSettings settings, Duration backoff,
                   Consumer<Duration> sleeper) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.windowSize())
                .minimumNumberOfCalls(settings.minimumCalls())
                .failureRateThreshold(settings.failureRatePercent())
                .waitDurationInOpenState(settings.openDuration())
                .build();
        for (LlmProvider adapter : adapters) {
            providers.put(adapter.vendor(), adapter);
            breakers.put(adapter.vendor(), CircuitBreaker.of("llm-" + adapter.vendor().wire(), config));
        }
        this.backoff = backoff;
        this.sleeper = sleeper;
    }

    public LlmResponse chat(LlmVendor vendor, LlmRequest request, String apiKey) {
        LlmProvider provider = providers.get(vendor);
        if (provider == null) {
            throw unavailable("No adapter is configured for " + vendor.wire());
        }
        ProviderCallException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return attempt(vendor, provider, request, apiKey);
            } catch (ProviderCallException e) {
                last = e;
                if (!e.retryable() || attempt == 2) {
                    break;
                }
                sleeper.accept(retryWait(e, backoff));
            }
        }
        throw map(last);
    }

    private LlmResponse attempt(LlmVendor vendor, LlmProvider provider, LlmRequest request, String apiKey)
            throws ProviderCallException {
        CircuitBreaker breaker = breakers.get(vendor);
        if (!breaker.tryAcquirePermission()) {
            throw unavailable("The " + vendor.wire() + " provider is temporarily unavailable");
        }
        long started = System.nanoTime();
        try {
            LlmResponse response = provider.chat(request, apiKey);
            long took = System.nanoTime() - started;
            breaker.onSuccess(took, TimeUnit.NANOSECONDS);
            log.info("llm provider call provider={} model={} outcome=ok latencyMs={}", vendor.wire(), request.model(),
                    TimeUnit.NANOSECONDS.toMillis(took));
            return response;
        } catch (ProviderCallException e) {
            long took = System.nanoTime() - started;
            if (e.countsAsBreakerFailure()) {
                breaker.onError(took, TimeUnit.NANOSECONDS, e);
            } else {
                breaker.releasePermission();
            }
            log.warn("llm provider call provider={} model={} outcome={} status={} latencyMs={}", vendor.wire(),
                    request.model(), e.kind(), e.status(), TimeUnit.NANOSECONDS.toMillis(took));
            throw e;
        } catch (RuntimeException e) {
            breaker.releasePermission();
            throw e;
        }
    }

    static Duration retryWait(ProviderCallException failure, Duration backoff) {
        Duration wait = failure.retryAfter() != null ? failure.retryAfter() : backoff;
        return wait.compareTo(MAX_RETRY_WAIT) > 0 ? MAX_RETRY_WAIT : wait;
    }

    private static RuntimeApiException map(ProviderCallException failure) {
        return switch (failure.kind()) {
            case RATE_LIMITED -> unavailable("The LLM provider is rate limiting requests");
            case TIMEOUT -> new RuntimeApiException(HttpStatus.GATEWAY_TIMEOUT, "LLM_PROVIDER_TIMEOUT",
                    "The LLM provider did not answer in time");
            case SERVER_ERROR, CLIENT_ERROR, IO, BAD_RESPONSE -> new RuntimeApiException(HttpStatus.BAD_GATEWAY,
                    "LLM_PROVIDER_ERROR", "The LLM provider call failed");
        };
    }

    private static RuntimeApiException unavailable(String message) {
        return new RuntimeApiException(HttpStatus.SERVICE_UNAVAILABLE, "LLM_PROVIDER_UNAVAILABLE", message);
    }

    /** Closes every breaker: for tests and for an operator who knows a provider is healthy again. */
    public void resetBreakers() {
        breakers.values().forEach(CircuitBreaker::reset);
    }

    public CircuitBreaker breaker(LlmVendor vendor) {
        return breakers.get(vendor);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 8: Run the three test classes**

Run: `cd backend && mvn -q test -Dtest='AnthropicProviderTest,OpenAiProviderTest,ProviderClientTest'`
Expected: PASS. If the JDK `HttpClient` rejects the lowercase `authorization` header name, keep the lowercase for the stub assertion (`Received.header` lower-cases both sides) and change the adapter's map key to `Authorization`.

- [ ] **Step 9: Commit**

```bash
git add backend
git commit -m "feat(llm): Anthropic and OpenAI adapters, provider client with retry and circuit breaker"
```

---

### Task 5: BYOK keys, submit-time verification, Tier B

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/llm/LlmApiException.java`, `LlmApiErrors.java`, `ByokKeyService.java`, `LlmKeyController.java`
- Modify: `backend/src/main/java/com/agentstore/platform/publishing/PublishingService.java`
- Test: `backend/src/test/java/com/agentstore/platform/llm/ByokKeyApiTest.java`, `ByokSubmitTest.java`; `backend/src/test/java/com/agentstore/platform/identity/LlmKeysAreNotKeyReachableTest.java`

**Interfaces:**
- Consumes: `ByokCredential`/`ByokCredentialRepository` (Task 2), `LlmVendor`, `Org.getDeveloperVerifiedAt()`, `AesGcmCipher.encrypt(byte[]) → Encrypted(ciphertext, nonce)` / `decrypt(byte[] ciphertext, byte[] nonce)`, `JwtService.Claims` (`orgId()`), `IdentityFixtures` (`member`, `tokenFor`, `buyerAdmin`), `LlmFixtures.verifyDeveloperOrg`.
- Produces:
  - `class LlmApiException extends RuntimeException` with `LlmApiException(HttpStatus, String code, String message)`, `status()`, `code()`; `LlmApiErrors` answers it as `{code, message}` for any controller.
  - `ByokKeyService` (`@Service`): `Summary put(UUID orgId, LlmVendor, String apiKey)` (403 `BYOK_NOT_VERIFIED` when the org is unverified, 400 `INVALID_API_KEY` for a blank/whitespace-containing/over-512-character value), `List<Summary> list(UUID orgId)`, `void delete(UUID orgId, LlmVendor)` (idempotent), `Optional<String> reveal(UUID orgId, LlmVendor)` (the plaintext, for the gateway and the launcher only); `record Summary(String provider, String last4, Instant createdAt)`.
  - HTTP: `PUT /api/v1/developer/llm-keys/{provider}` `{apiKey}`, `GET /api/v1/developer/llm-keys`, `DELETE /api/v1/developer/llm-keys/{provider}`; developer role only; developer API keys cannot reach them (`ApiKeyScope` is deny-by-default and is not edited).
  - `PublishingService.submit` throws `LlmApiException` 403 `BYOK_NOT_VERIFIED` for a `byok_*` manifest from an unverified org, and marks any `byok_*` version Tier B.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/agentstore/platform/identity/LlmKeysAreNotKeyReachableTest.java`:

```java
package com.agentstore.platform.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Developer API keys stay on the publishing allowlist; the LLM key store is JWT-only. */
class LlmKeysAreNotKeyReachableTest {

    @Test
    void keysCannotManageLlmKeys() {
        assertThat(ApiKeyScope.allows("GET", "/api/v1/developer/llm-keys")).isFalse();
        assertThat(ApiKeyScope.allows("PUT", "/api/v1/developer/llm-keys/anthropic")).isFalse();
        assertThat(ApiKeyScope.allows("DELETE", "/api/v1/developer/llm-keys/openai")).isFalse();
    }
}
```

`backend/src/test/java/com/agentstore/platform/llm/ByokKeyApiTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.IdentityFixtures;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgMembership;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.manifest.LlmVendor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ByokKeyApiTest extends AbstractIntegrationTest {

    static final String PATH = "/api/v1/developer/llm-keys";
    static final String KEY = "sk-ant-api03-abcdefghijklmnop1234";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired IdentityFixtures identity;
    @Autowired OrgRepository orgs;
    @Autowired LlmFixtures fx;
    @Autowired ByokKeyService keys;
    @Autowired JdbcTemplate jdbc;

    record Dev(Org org, String token) {}

    Dev developer(boolean verified) {
        Org org = orgs.save(new Org(null, "Dev " + UUID.randomUUID(), OrgType.DEVELOPER));
        if (verified) {
            fx.verifyDeveloperOrg(org);
        }
        OrgMembership membership = identity.member(org, "dev-" + UUID.randomUUID() + "@example.com", UserRole.DEVELOPER, "Dev");
        return new Dev(org, identity.tokenFor(membership));
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<List> list(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange("http://localhost:" + port + PATH, HttpMethod.GET, new HttpEntity<>(headers), List.class);
    }

    @Test
    void anUnverifiedOrgCannotStoreAKey() {
        Dev dev = developer(false);

        var response = call(HttpMethod.PUT, PATH + "/anthropic", dev.token(), Map.of("apiKey", KEY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("BYOK_NOT_VERIFIED");
        assertThat(keys.list(dev.org().getId())).isEmpty();
        assertThat(response.getBody().toString()).doesNotContain(KEY);
    }

    @Test
    void aVerifiedDeveloperStoresAKeyEncryptedAtRestAndNeverGetsItBack() {
        Dev dev = developer(true);

        var put = call(HttpMethod.PUT, PATH + "/anthropic", dev.token(), Map.of("apiKey", KEY));

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put.getBody().get("provider")).isEqualTo("anthropic");
        assertThat(put.getBody().get("last4")).isEqualTo("1234");
        assertThat(put.getBody().get("createdAt")).isNotNull();
        assertThat(put.getBody().toString()).doesNotContain(KEY);

        byte[] stored = jdbc.queryForObject("SELECT encrypted_key FROM byok_credential WHERE developer_org_id = ?",
                byte[].class, dev.org().getId());
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(KEY).doesNotContain("abcdefghijklmnop");
        assertThat(keys.reveal(dev.org().getId(), LlmVendor.ANTHROPIC)).contains(KEY);

        var listed = list(dev.token());
        assertThat(listed.getBody()).hasSize(1);
        Map<?, ?> item = (Map<?, ?>) listed.getBody().get(0);
        assertThat(item.keySet()).containsExactlyInAnyOrder("provider", "last4", "createdAt");
        assertThat(listed.getBody().toString()).doesNotContain(KEY);
    }

    @Test
    void storingAgainReplacesTheKey() {
        Dev dev = developer(true);
        call(HttpMethod.PUT, PATH + "/anthropic", dev.token(), Map.of("apiKey", KEY));

        var second = call(HttpMethod.PUT, PATH + "/Anthropic", dev.token(), Map.of("apiKey", "sk-ant-second-key-9999"));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("last4")).isEqualTo("9999");
        assertThat(keys.list(dev.org().getId())).hasSize(1);
        assertThat(keys.reveal(dev.org().getId(), LlmVendor.ANTHROPIC)).contains("sk-ant-second-key-9999");
    }

    @Test
    void aTinyKeyIsNeverFullyEchoedAsLast4() {
        Dev dev = developer(true);

        var put = call(HttpMethod.PUT, PATH + "/openai", dev.token(), Map.of("apiKey", "abc"));

        assertThat(put.getBody().get("last4")).isEqualTo("****");
    }

    @Test
    void deletingRemovesTheKeyAndIsIdempotent() {
        Dev dev = developer(true);
        call(HttpMethod.PUT, PATH + "/anthropic", dev.token(), Map.of("apiKey", KEY));

        assertThat(call(HttpMethod.DELETE, PATH + "/anthropic", dev.token(), null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(HttpMethod.DELETE, PATH + "/anthropic", dev.token(), null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(list(dev.token()).getBody()).isEmpty();
        assertThat(keys.reveal(dev.org().getId(), LlmVendor.ANTHROPIC)).isEmpty();
    }

    @Test
    void anUnverifiedOrgCanStillDeleteItsKeys() {
        Dev dev = developer(true);
        keys.put(dev.org().getId(), LlmVendor.OPENAI, KEY);
        jdbc.update("UPDATE org SET developer_verified_at = NULL WHERE id = ?", dev.org().getId());

        assertThat(call(HttpMethod.DELETE, PATH + "/openai", dev.token(), null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(keys.list(dev.org().getId())).isEmpty();
    }

    @Test
    void badKeysAreRejectedWithoutEchoingThem() {
        Dev dev = developer(true);

        for (String bad : List.of("", "   ", "has a space", "tab\tkey", "x".repeat(513))) {
            var response = call(HttpMethod.PUT, PATH + "/anthropic", dev.token(), Map.of("apiKey", bad));
            assertThat(response.getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("code")).isEqualTo("INVALID_API_KEY");
            if (bad.length() > 3) {
                assertThat(response.getBody().toString()).doesNotContain(bad);
            }
        }
        assertThat(call(HttpMethod.PUT, PATH + "/anthropic", dev.token(), Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.PUT, PATH + "/anthropic", dev.token(), null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(keys.list(dev.org().getId())).isEmpty();
        // 512 characters is the largest key that is accepted
        assertThat(call(HttpMethod.PUT, PATH + "/anthropic", dev.token(), Map.of("apiKey", "x".repeat(512))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void anUnknownProviderIsRejected() {
        Dev dev = developer(true);

        var response = call(HttpMethod.PUT, PATH + "/google", dev.token(), Map.of("apiKey", KEY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("UNKNOWN_PROVIDER");
        assertThat(call(HttpMethod.DELETE, PATH + "/google", dev.token(), null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void oneOrgNeverSeesOrTouchesAnothersKeys() {
        Dev a = developer(true);
        Dev b = developer(true);
        call(HttpMethod.PUT, PATH + "/anthropic", a.token(), Map.of("apiKey", KEY));

        assertThat(list(b.token()).getBody()).isEmpty();
        call(HttpMethod.DELETE, PATH + "/anthropic", b.token(), null);

        assertThat(keys.reveal(a.org().getId(), LlmVendor.ANTHROPIC)).contains(KEY);
        assertThat(keys.reveal(b.org().getId(), LlmVendor.ANTHROPIC)).isEmpty();
    }

    @Test
    void onlyDevelopersMayUseIt() {
        String buyer = identity.tokenFor(identity.buyerAdmin("buyer-" + UUID.randomUUID() + "@example.com"));

        assertThat(call(HttpMethod.GET, PATH, buyer, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.PUT, PATH + "/anthropic", buyer, Map.of("apiKey", KEY)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, PATH, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

`backend/src/test/java/com/agentstore/platform/llm/ByokSubmitTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.Tier;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.identity.IdentityFixtures;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgMembership;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.publishing.PublishingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ByokSubmitTest extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired PublishingService publishingService;
    @Autowired OrgRepository orgs;
    @Autowired AgentRepository agents;
    @Autowired AgentVersionRepository versions;
    @Autowired LlmFixtures fx;
    @Autowired IdentityFixtures identity;

    Org developer(boolean verified) {
        Org org = orgs.save(new Org(null, "Dev " + UUID.randomUUID(), OrgType.DEVELOPER));
        if (verified) {
            fx.verifyDeveloperOrg(org);
        }
        return org;
    }

    AgentVersion draft(Org developer, String manifestFile) throws Exception {
        Agent agent = agents.save(new Agent(null, developer, "Agent " + UUID.randomUUID(), "reconciliation", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/" + manifestFile));
        return versions.save(new AgentVersion(null, agent, "1.0.0", manifest, "template-v1", VersionStatus.DRAFT, null, "cl"));
    }

    @Test
    void anUnverifiedOrgCannotSubmitAByokManifest() throws Exception {
        for (String manifest : new String[]{"valid-llm-byok-proxy.json", "valid-llm-byok-direct.json"}) {
            Org org = developer(false);
            AgentVersion version = draft(org, manifest);

            assertThatThrownBy(() -> publishingService.submit(version.getId(), org.getId()))
                    .isInstanceOfSatisfying(LlmApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(e.code()).isEqualTo("BYOK_NOT_VERIFIED");
                    });
            assertThat(versions.findById(version.getId()).orElseThrow().getStatus()).isEqualTo(VersionStatus.DRAFT);
        }
    }

    @Test
    void aVerifiedOrgsByokVersionIsTierBAndWaitsForReview() throws Exception {
        for (String manifest : new String[]{"valid-llm-byok-proxy.json", "valid-llm-byok-direct.json"}) {
            Org org = developer(true);
            AgentVersion version = draft(org, manifest);

            AgentVersion result = publishingService.submit(version.getId(), org.getId());

            assertThat(result.getTier()).isEqualTo(Tier.TIER_B);
            assertThat(result.getStatus()).isEqualTo(VersionStatus.IN_REVIEW);
            assertThat(result.getApprovedAt()).isNull();
        }
    }

    @Test
    void aPlatformLlmManifestNeedsNoVerificationAndStaysTierA() throws Exception {
        Org org = developer(false);
        AgentVersion version = draft(org, "valid-llm-platform.json");

        AgentVersion result = publishingService.submit(version.getId(), org.getId());

        assertThat(result.getTier()).isEqualTo(Tier.TIER_A);
        assertThat(result.getStatus()).isEqualTo(VersionStatus.LIVE);
    }

    @Test
    void theSubmitEndpointAnswers403WithTheCode() throws Exception {
        Org org = developer(false);
        AgentVersion version = draft(org, "valid-llm-byok-proxy.json");
        OrgMembership membership = identity.member(org, "dev-" + UUID.randomUUID() + "@example.com", UserRole.DEVELOPER, "Dev");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(identity.tokenFor(membership));

        var response = rest.exchange("http://localhost:" + port + "/api/v1/agents/" + version.getAgent().getId()
                + "/versions/" + version.getId() + "/submit", HttpMethod.POST, new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("BYOK_NOT_VERIFIED");
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='ByokKeyApiTest,ByokSubmitTest,LlmKeysAreNotKeyReachableTest'`
Expected: FAIL to compile (`ByokKeyService`, `LlmApiException` do not exist). `LlmKeysAreNotKeyReachableTest` already passes once it compiles.

- [ ] **Step 3: Implement the exception, the service and the controller**

`llm/LlmApiException.java`:

```java
package com.agentstore.platform.llm;

import org.springframework.http.HttpStatus;

/** A developer-facing LLM failure with a stable code (BYOK verification, key validation). Never carries a key. */
public class LlmApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public LlmApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
```

`llm/LlmApiErrors.java`:

```java
package com.agentstore.platform.llm;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** The app hides {@code ResponseStatusException} reasons, so LLM developer endpoints answer {@code {code, message}}. */
@RestControllerAdvice
public class LlmApiErrors {

    @ExceptionHandler(LlmApiException.class)
    ResponseEntity<Map<String, Object>> api(LlmApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code(), "message", e.getMessage()));
    }
}
```

`llm/ByokKeyService.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.common.AesGcmCipher;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.manifest.LlmVendor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A developer org's own LLM keys. Stored AES-GCM encrypted with the same key as connection secrets; only
 * {@link #reveal} returns the plaintext, and only the gateway and the launcher call it.
 */
@Service
public class ByokKeyService {

    static final int MAX_KEY_LENGTH = 512;

    public record Summary(String provider, String last4, Instant createdAt) {
    }

    private final ByokCredentialRepository credentials;
    private final OrgRepository orgs;
    private final AesGcmCipher cipher;

    public ByokKeyService(ByokCredentialRepository credentials, OrgRepository orgs, AesGcmCipher cipher) {
        this.credentials = credentials;
        this.orgs = orgs;
        this.cipher = cipher;
    }

    /** Stores or replaces the org's key for the provider. Does not test the key against the provider. */
    @Transactional
    public Summary put(UUID orgId, LlmVendor vendor, String apiKey) {
        Org org = orgs.findById(orgId)
                .orElseThrow(() -> new LlmApiException(HttpStatus.NOT_FOUND, "ORG_NOT_FOUND", "Organisation not found"));
        if (org.getDeveloperVerifiedAt() == null) {
            throw notVerified();
        }
        validate(apiKey);
        AesGcmCipher.Encrypted encrypted = cipher.encrypt(apiKey.getBytes(StandardCharsets.UTF_8));
        ByokCredential credential = credentials.findByDeveloperOrgIdAndProvider(orgId, vendor)
                .orElseGet(() -> new ByokCredential(orgId, vendor));
        credential.setEncryptedKey(encrypted.ciphertext());
        credential.setNonce(encrypted.nonce());
        credential.setLast4(last4(apiKey));
        credential.setCreatedAt(Instant.now());
        return summary(credentials.save(credential));
    }

    @Transactional(readOnly = true)
    public List<Summary> list(UUID orgId) {
        return credentials.findByDeveloperOrgIdOrderByProviderAsc(orgId).stream().map(ByokKeyService::summary).toList();
    }

    /** Idempotent: deleting a key that is not there is not an error. Allowed for any org (clean-up). */
    @Transactional
    public void delete(UUID orgId, LlmVendor vendor) {
        credentials.deleteByDeveloperOrgIdAndProvider(orgId, vendor);
    }

    /** The plaintext key. Never log it, return it, or put it in an exception message. */
    @Transactional(readOnly = true)
    public Optional<String> reveal(UUID orgId, LlmVendor vendor) {
        return credentials.findByDeveloperOrgIdAndProvider(orgId, vendor)
                .map(c -> new String(cipher.decrypt(c.getEncryptedKey(), c.getNonce()), StandardCharsets.UTF_8));
    }

    static LlmApiException notVerified() {
        return new LlmApiException(HttpStatus.FORBIDDEN, "BYOK_NOT_VERIFIED",
                "Only verified developer organisations can bring their own LLM key");
    }

    private static void validate(String apiKey) {
        if (apiKey == null || apiKey.isBlank() || apiKey.length() > MAX_KEY_LENGTH
                || apiKey.chars().anyMatch(Character::isWhitespace)) {
            throw new LlmApiException(HttpStatus.BAD_REQUEST, "INVALID_API_KEY",
                    "apiKey must be a non-blank value without whitespace, at most " + MAX_KEY_LENGTH + " characters");
        }
    }

    /** The last four characters, unless the key is so short that they would give most of it away. */
    private static String last4(String apiKey) {
        return apiKey.length() < 8 ? "****" : apiKey.substring(apiKey.length() - 4);
    }

    private static Summary summary(ByokCredential credential) {
        return new Summary(credential.getProvider().wire(), credential.getLast4(), credential.getCreatedAt());
    }
}
```

`llm/LlmKeyController.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.manifest.LlmVendor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/** The developer's LLM key store. JWT-only: {@code ApiKeyScope} keeps API keys away from it. */
@RestController
@RequestMapping("/api/v1/developer/llm-keys")
public class LlmKeyController {

    /** Whatever logs a request must not see the key. */
    public record PutRequest(String apiKey) {
        @Override
        public String toString() {
            return "PutRequest[apiKey=****]";
        }
    }

    private final ByokKeyService keys;

    public LlmKeyController(ByokKeyService keys) {
        this.keys = keys;
    }

    @PutMapping("/{provider}")
    @PreAuthorize("hasRole('DEVELOPER')")
    public ByokKeyService.Summary put(@PathVariable String provider, @RequestBody PutRequest request) {
        return keys.put(claims().orgId(), vendor(provider), request.apiKey());
    }

    @GetMapping
    @PreAuthorize("hasRole('DEVELOPER')")
    public List<ByokKeyService.Summary> list() {
        return keys.list(claims().orgId());
    }

    @DeleteMapping("/{provider}")
    @PreAuthorize("hasRole('DEVELOPER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String provider) {
        keys.delete(claims().orgId(), vendor(provider));
    }

    private static LlmVendor vendor(String provider) {
        return LlmVendor.fromWire(provider.toLowerCase(Locale.ROOT)).orElseThrow(() ->
                new LlmApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_PROVIDER", "provider must be 'anthropic' or 'openai'"));
    }

    private static JwtService.Claims claims() {
        return (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
```

- [ ] **Step 4: Verify at submit and mark BYOK as Tier B**

In `publishing/PublishingService.java`, add imports:

```java
import com.agentstore.platform.llm.LlmApiException;
import org.springframework.http.HttpStatus;
```

and replace these two lines in `submit`:

```java
        var manifest = manifestParser.parse(version.getManifestJson());
        boolean isTierB = manifest.scopes().stream().anyMatch(s -> s.type() == ScopeType.WRITE);
```

with:

```java
        var manifest = manifestParser.parse(version.getManifestJson());
        boolean byok = manifest.llm() != null && manifest.llm().mode().isByok();
        if (byok && version.getAgent().getDeveloperOrg().getDeveloperVerifiedAt() == null) {
            throw new LlmApiException(HttpStatus.FORBIDDEN, "BYOK_NOT_VERIFIED",
                    "Only verified developer organisations can use llm.mode " + manifest.llm().mode().wire());
        }
        boolean isTierB = byok || manifest.scopes().stream().anyMatch(s -> s.type() == ScopeType.WRITE);
```

(`SubmissionController` catches only `SecurityException`, `IllegalStateException` and `IllegalArgumentException`, so the `LlmApiException` reaches `LlmApiErrors` untouched.)

- [ ] **Step 5: Run the new tests and the publishing and identity suites**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='ByokKeyApiTest,ByokSubmitTest,LlmKeysAreNotKeyReachableTest,com.agentstore.platform.publishing.*Test,com.agentstore.platform.identity.*Test'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat(llm): BYOK key store and API, submit-time verification, BYOK is Tier B"
```

---

### Task 6: `GatewayLlmBackend` — platform and `byok_proxy`, metering, steps, error contract

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/llm/LlmKeyResolver.java`, `LlmCost.java`, `RunLlmContext.java`, `RunLlmContextLoader.java`, `LlmCallLog.java`, `GatewayLlmBackend.java`
- Modify: `backend/src/main/java/com/agentstore/platform/runtime/RuntimeLlmController.java`; `backend/src/test/java/com/agentstore/platform/runtime/RuntimeResourceEndpointsTest.java` (the 501 test only)
- Delete: `backend/src/main/java/com/agentstore/platform/runtime/UnavailableLlmBackend.java`
- Test: `backend/src/test/java/com/agentstore/platform/llm/AbstractLlmIntegrationTest.java`, `LlmCostTest.java`, `LlmKeyResolverTest.java`, `GatewayPlatformCallTest.java`, `GatewayBudgetTest.java`, `GatewayErrorContractTest.java`, `GatewayByokProxyTest.java`, `GatewayKeyLeakTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–5; `LlmBackend.chat(RunPrincipal, Map<String,Object>)`; `RunStepRecorder.record(RunPrincipal, RunStepKind, Map)`; `RunPrincipal` (`runId()`, `mode()`, `manifest()`).
- Produces:
  - `GatewayLlmBackend` (`@Service implements LlmBackend`): the only `LlmBackend` in production. Task 8 adds mock/replay dispatch and recording to it.
  - `LlmKeyResolver.resolve(LlmMode, LlmVendor, UUID developerOrgId) → String` (throws `RuntimeApiException` 503 `LLM_PROVIDER_UNAVAILABLE` for an unconfigured platform key, 400 `LLM_KEY_MISSING` for a missing developer key).
  - `LlmCost.of(LlmSource, LlmModel, int inputTokens, int outputTokens) → BigDecimal` (package-private).
  - `RunLlmContext(UUID developerOrgId, RunLlmMode llmMode, UUID replayRunId, String llmMockJson)`; `RunLlmContextLoader.load(UUID runId)`.
  - `LlmCallLog.record(RunPrincipal, LlmCall)`: saves the row and records the `LLM_CALL` step `{block:"llm", calls:1, tokens, inputTokens, outputTokens, model, source, status[, errorCode]}`.
  - Test base `AbstractLlmIntegrationTest` (stub provider wired in, models ensured, breakers reset) with `chat(token, body)`, `request(model, text, maxTokens)`, `llmSteps(runId)`, and the fields `port`, `rest`, `fx`, `providerClient`, `llmCalls`, `runSteps`, `jdbc`, `json`, constant `STUB`, `PLATFORM_ANTHROPIC_KEY`, `PLATFORM_OPENAI_KEY`.

- [ ] **Step 1: Write the shared test base**

`backend/src/test/java/com/agentstore/platform/llm/AbstractLlmIntegrationTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.RunStep;
import com.agentstore.platform.execution.RunStepKind;
import com.agentstore.platform.execution.RunStepRepository;
import com.agentstore.platform.runtime.RuntimeFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Gateway integration tests: Postgres, Redis and SeaweedFS from the parents, plus a stub LLM provider. */
public abstract class AbstractLlmIntegrationTest extends AbstractStorageIntegrationTest {

    public static final String PLATFORM_ANTHROPIC_KEY = "platform-anthropic-key-0001";
    public static final String PLATFORM_OPENAI_KEY = "platform-openai-key-0001";

    protected static final StubProviderServer STUB = StubProviderServer.start();

    @DynamicPropertySource
    static void llmProperties(DynamicPropertyRegistry registry) {
        registry.add("agentstore.llm.providers.anthropic.base-url", STUB::baseUrl);
        registry.add("agentstore.llm.providers.anthropic.api-key", () -> PLATFORM_ANTHROPIC_KEY);
        registry.add("agentstore.llm.providers.openai.base-url", STUB::baseUrl);
        registry.add("agentstore.llm.providers.openai.api-key", () -> PLATFORM_OPENAI_KEY);
        registry.add("agentstore.llm.retry-backoff", () -> "PT0S");
    }

    @LocalServerPort protected int port;
    @Autowired protected TestRestTemplate rest;
    @Autowired protected LlmFixtures fx;
    @Autowired protected ProviderClient providerClient;
    @Autowired protected LlmCallRepository llmCalls;
    @Autowired protected RunStepRepository runSteps;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper json;

    @BeforeEach
    void resetLlmState() {
        STUB.reset();
        providerClient.resetBreakers();
        fx.ensureModels();
    }

    @SuppressWarnings("rawtypes")
    protected ResponseEntity<Map> chat(String token, Object body) {
        return rest.exchange("http://localhost:" + port + "/runtime/v1/llm/chat", HttpMethod.POST,
                new HttpEntity<>(body, RuntimeFixtures.bearer(token)), Map.class);
    }

    protected static Map<String, Object> request(String model, String text, int maxTokens) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", List.of(Map.of("role", "user", "content", text)));
        request.put("maxTokens", maxTokens);
        return request;
    }

    /** The details of the run's LLM_CALL steps, oldest first. */
    protected List<JsonNode> llmSteps(UUID runId) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (RunStep step : runSteps.findByRunIdOrderByCreatedAtAsc(runId)) {
            if (step.getKind() == RunStepKind.LLM_CALL) {
                out.add(json.readTree(step.getDetail()));
            }
        }
        return out;
    }
}
```

- [ ] **Step 2: Write the failing unit tests**

`backend/src/test/java/com/agentstore/platform/llm/LlmCostTest.java`:

```java
package com.agentstore.platform.llm;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LlmCostTest {

    static LlmModel model(String in, String out) {
        LlmModel model = new LlmModel();
        model.setId("m");
        model.setInputPricePerMtok(new BigDecimal(in));
        model.setOutputPricePerMtok(new BigDecimal(out));
        model.setCurrency("USD");
        return model;
    }

    @Test
    void aPlatformCallCostsTokensTimesThePricePerMillion() {
        assertThat(LlmCost.of(LlmSource.PLATFORM, model("3.0000", "15.0000"), 100, 40)).isEqualByComparingTo("0.0009");
        assertThat(LlmCost.of(LlmSource.PLATFORM, model("3.0000", "15.0000"), 1_000_000, 1_000_000))
                .isEqualByComparingTo("18.0000");
    }

    @Test
    void theCostIsRoundedHalfUpToFourPlaces() {
        // 10 * 2.5 + 5 * 10 = 75 per million = 0.000075
        BigDecimal cost = LlmCost.of(LlmSource.PLATFORM, model("2.5000", "10.0000"), 10, 5);

        assertThat(cost).isEqualByComparingTo("0.0001");
        assertThat(cost.scale()).isEqualTo(4);
    }

    @Test
    void everyOtherSourceCostsNothing() {
        for (LlmSource source : new LlmSource[]{LlmSource.BYOK_PROXY, LlmSource.MOCK, LlmSource.REPLAY}) {
            assertThat(LlmCost.of(source, model("3.0000", "15.0000"), 5000, 5000)).isEqualByComparingTo("0");
        }
    }

    @Test
    void zeroPricesAndZeroTokensCostNothing() {
        assertThat(LlmCost.of(LlmSource.PLATFORM, model("0", "0"), 5000, 5000)).isEqualByComparingTo("0");
        assertThat(LlmCost.of(LlmSource.PLATFORM, model("3.0000", "15.0000"), 0, 0)).isEqualByComparingTo("0");
    }
}
```

`backend/src/test/java/com/agentstore/platform/llm/LlmKeyResolverTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.manifest.LlmMode;
import com.agentstore.platform.manifest.LlmVendor;
import com.agentstore.platform.runtime.RuntimeApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmKeyResolverTest {

    static LlmProperties props(String anthropicKey, String openAiKey) {
        return new LlmProperties(32_000, Duration.ofDays(14), Duration.ofMillis(250), Duration.ofSeconds(5),
                Duration.ofSeconds(120), "http://anthropic.test", anthropicKey, "http://openai.test", openAiKey,
                20, 10, 50f, Duration.ofSeconds(30));
    }

    final ByokKeyService byok = mock(ByokKeyService.class);
    final UUID orgId = UUID.randomUUID();

    @Test
    void platformCallsUseThePlatformKeyForTheProvider() {
        var resolver = new LlmKeyResolver(props("platform-a", "platform-o"), byok);

        assertThat(resolver.resolve(LlmMode.PLATFORM, LlmVendor.ANTHROPIC, orgId)).isEqualTo("platform-a");
        assertThat(resolver.resolve(LlmMode.PLATFORM, LlmVendor.OPENAI, orgId)).isEqualTo("platform-o");
    }

    @Test
    void anUnconfiguredPlatformKeyIsServiceUnavailable() {
        var resolver = new LlmKeyResolver(props("", "  "), byok);

        for (LlmVendor vendor : LlmVendor.values()) {
            assertThatThrownBy(() -> resolver.resolve(LlmMode.PLATFORM, vendor, orgId))
                    .isInstanceOfSatisfying(RuntimeApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(e.code()).isEqualTo("LLM_PROVIDER_UNAVAILABLE");
                    });
        }
    }

    @Test
    void byokProxyUsesTheDevelopersKeyAndNeverThePlatformKey() {
        when(byok.reveal(orgId, LlmVendor.ANTHROPIC)).thenReturn(Optional.of("dev-key"));
        var resolver = new LlmKeyResolver(props("platform-a", "platform-o"), byok);

        assertThat(resolver.resolve(LlmMode.BYOK_PROXY, LlmVendor.ANTHROPIC, orgId)).isEqualTo("dev-key");
    }

    @Test
    void aMissingDeveloperKeyIsABadRequestNotAFallbackToThePlatformKey() {
        when(byok.reveal(orgId, LlmVendor.ANTHROPIC)).thenReturn(Optional.empty());
        var resolver = new LlmKeyResolver(props("platform-a", "platform-o"), byok);

        assertThatThrownBy(() -> resolver.resolve(LlmMode.BYOK_PROXY, LlmVendor.ANTHROPIC, orgId))
                .isInstanceOfSatisfying(RuntimeApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("LLM_KEY_MISSING");
                });
    }

    @Test
    void thePropertiesNeverPrintTheirKeys() {
        assertThat(props("platform-a-secret", "platform-o-secret").toString())
                .doesNotContain("platform-a-secret").doesNotContain("platform-o-secret");
    }
}
```

- [ ] **Step 3: Write the failing gateway integration tests**

`backend/src/test/java/com/agentstore/platform/llm/GatewayPlatformCallTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.llm.StubProviderServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayPlatformCallTest extends AbstractLlmIntegrationTest {

    @Test
    void aPlatformCallIsForwardedMeteredAndTraced() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        STUB.enqueue(Reply.anthropicText("hello", 100, 40));

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 500));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body.get("model")).isEqualTo("test-claude");
        assertThat(body.get("stopReason")).isEqualTo("end_turn");
        assertThat(body.get("usage")).isEqualTo(Map.of("inputTokens", 100, "outputTokens", 40));
        assertThat(((List<?>) body.get("content")).get(0)).isEqualTo(Map.of("type", "text", "text", "hello"));

        assertThat(STUB.received()).hasSize(1);
        var received = STUB.received().get(0);
        assertThat(received.path()).isEqualTo("/v1/messages");
        assertThat(received.header("x-api-key")).isEqualTo(PLATFORM_ANTHROPIC_KEY);
        JsonNode sent = json.readTree(received.body());
        assertThat(sent.get("model").asText()).isEqualTo("test-claude");
        assertThat(sent.get("max_tokens").asInt()).isEqualTo(500);

        var calls = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId());
        assertThat(calls).hasSize(1);
        LlmCall call = calls.get(0);
        assertThat(call.getStatus()).isEqualTo(LlmCallStatus.OK);
        assertThat(call.getSource()).isEqualTo(LlmSource.PLATFORM);
        assertThat(call.getModelId()).isEqualTo("test-claude");
        assertThat(call.getInputTokens()).isEqualTo(100);
        assertThat(call.getOutputTokens()).isEqualTo(40);
        assertThat(call.getCost()).isEqualByComparingTo("0.0009");   // 100/1e6*3 + 40/1e6*15
        assertThat(call.getRequestHash()).hasSize(64);
        assertThat(call.getRequestKey()).as("production runs never store prompts").isNull();
        assertThat(call.getErrorCode()).isNull();
        assertThat(fx.tokens(agent.runId())).containsExactly(140, 0);

        var steps = llmSteps(agent.runId());
        assertThat(steps).hasSize(1);
        JsonNode step = steps.get(0);
        assertThat(step.get("block").asText()).isEqualTo("llm");
        assertThat(step.get("calls").asInt()).isEqualTo(1);
        assertThat(step.get("tokens").asInt()).isEqualTo(140);
        assertThat(step.get("inputTokens").asInt()).isEqualTo(100);
        assertThat(step.get("outputTokens").asInt()).isEqualTo(40);
        assertThat(step.get("model").asText()).isEqualTo("test-claude");
        assertThat(step.get("source").asText()).isEqualTo("PLATFORM");
        assertThat(step.get("status").asText()).isEqualTo("OK");
    }

    @Test
    void anOpenAiModelGoesToTheOpenAiAdapterWithThePlatformOpenAiKey() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        STUB.enqueue(Reply.openAiText("hey", 10, 5));

        var response = chat(agent.token(), request(LlmFixtures.GPT, "hi", 200));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var received = STUB.received().get(0);
        assertThat(received.path()).isEqualTo("/v1/chat/completions");
        assertThat(received.header("authorization")).isEqualTo("Bearer " + PLATFORM_OPENAI_KEY);
        assertThat(json.readTree(received.body()).get("store").asBoolean()).isFalse();
        var call = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId()).get(0);
        assertThat(call.getCost()).isEqualByComparingTo("0.0001");   // 75 per million, rounded half up to 4 places
        assertThat(fx.tokens(agent.runId())).containsExactly(15, 0);
    }

    @Test
    void aModelOutsideTheManifestIsRejectedWithoutMetering() throws Exception {
        var agent = fx.runningAgent("valid-llm-small-budget.json");   // lists only test-claude

        var response = chat(agent.token(), request(LlmFixtures.GPT, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("MODEL_NOT_ALLOWED");
        assertNothingWasMetered(agent.runId());
    }

    @Test
    void aDisabledModelIsRejectedEvenWhenTheManifestListsIt() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");   // lists test-disabled

        var response = chat(agent.token(), request(LlmFixtures.DISABLED, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("MODEL_NOT_ALLOWED");
        assertNothingWasMetered(agent.runId());
    }

    @Test
    void aModelTheOperatorDisablesLaterStopsWorkingWithoutResubmitting() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        STUB.enqueue(Reply.anthropicText("ok", 1, 1));
        assertThat(chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100)).getStatusCode()).isEqualTo(HttpStatus.OK);

        jdbc.update("UPDATE llm_model SET enabled = FALSE WHERE id = ?", LlmFixtures.CLAUDE);

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("MODEL_NOT_ALLOWED");
    }

    @Test
    void anAgentWithoutAnLlmBlockGets400() throws Exception {
        var agent = fx.runningAgent("valid-runtime-agent.json");

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("LLM_NOT_DECLARED");
        assertNothingWasMetered(agent.runId());
    }

    @Test
    void aByokDirectAgentMustCallTheProviderItself() throws Exception {
        var agent = fx.runningAgent("valid-llm-byok-direct.json");

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("USE_DIRECT_PROVIDER");
        assertNothingWasMetered(agent.runId());
    }

    @Test
    void invalidRequestsWriteNoRowAndReserveNothing() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");

        var missingMaxTokens = chat(agent.token(), Map.of("model", "test-claude",
                "messages", List.of(Map.of("role", "user", "content", "hi"))));
        var unknownKey = chat(agent.token(), Map.of("model", "test-claude", "maxTokens", 100, "stream", true,
                "messages", List.of(Map.of("role", "user", "content", "hi"))));

        for (var response : List.of(missingMaxTokens, unknownKey)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("code")).isEqualTo("INVALID_REQUEST");
        }
        assertNothingWasMetered(agent.runId());
    }

    private void assertNothingWasMetered(java.util.UUID runId) throws Exception {
        assertThat(STUB.received()).isEmpty();
        assertThat(llmCalls.findByRunIdOrderByCreatedAtAsc(runId)).isEmpty();
        assertThat(llmSteps(runId)).isEmpty();
        assertThat(fx.tokens(runId)).containsExactly(0, 0);
    }
}
```

`backend/src/test/java/com/agentstore/platform/llm/GatewayBudgetTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.llm.StubProviderServer.Reply;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** valid-llm-small-budget.json allows 1,000 tokens per run. A request this small estimates about 36 input tokens. */
class GatewayBudgetTest extends AbstractLlmIntegrationTest {

    @Test
    void aCallThatWouldExceedTheBudgetIsRejectedAfterAnEarlierOneUsedSome() throws Exception {
        var agent = fx.runningAgent("valid-llm-small-budget.json");
        STUB.enqueue(Reply.anthropicText("ok", 30, 20));

        var first = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 950));   // reserves about 986 of 1000
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fx.tokens(agent.runId())).containsExactly(50, 0);

        var second = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 950));  // 50 used + about 986 > 1000

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(second.getBody().get("code")).isEqualTo("BUDGET_EXCEEDED");
        assertThat(STUB.received()).as("the rejected call never reached the provider").hasSize(1);
        assertThat(fx.tokens(agent.runId())).containsExactly(50, 0);
        var calls = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId());
        assertThat(calls).extracting(LlmCall::getStatus).containsExactly(LlmCallStatus.OK, LlmCallStatus.BUDGET_REJECTED);
        assertThat(calls.get(1).getErrorCode()).isEqualTo("BUDGET_EXCEEDED");
        var steps = llmSteps(agent.runId());
        assertThat(steps).hasSize(2);
        assertThat(steps.get(1).get("status").asText()).isEqualTo("BUDGET_REJECTED");
        assertThat(steps.get(1).get("tokens").asInt()).isZero();
    }

    @Test
    void aCallLargerThanTheWholeBudgetIsRejected() throws Exception {
        var agent = fx.runningAgent("valid-llm-small-budget.json");

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 1000));   // maxTokens alone is the whole budget

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(response.getBody().get("code")).isEqualTo("BUDGET_EXCEEDED");
        assertThat(STUB.received()).isEmpty();
        assertThat(fx.tokens(agent.runId())).containsExactly(0, 0);
        assertThat(llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId()))
                .extracting(LlmCall::getStatus).containsExactly(LlmCallStatus.BUDGET_REJECTED);
    }

    @Test
    void aFailedCallReleasesItsReservation() throws Exception {
        var agent = fx.runningAgent("valid-llm-small-budget.json");
        STUB.always(Reply.status(500, "{\"error\":\"boom\"}"));

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 900));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(fx.tokens(agent.runId())).as("nothing reserved, nothing used").containsExactly(0, 0);
        var calls = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId());
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).getStatus()).isEqualTo(LlmCallStatus.ERROR);
        assertThat(calls.get(0).getErrorCode()).isEqualTo("LLM_PROVIDER_ERROR");
        assertThat(calls.get(0).getInputTokens()).isZero();
        var steps = llmSteps(agent.runId());
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).get("status").asText()).isEqualTo("ERROR");
        assertThat(steps.get(0).get("errorCode").asText()).isEqualTo("LLM_PROVIDER_ERROR");

        // the released budget is available again
        STUB.reset();
        STUB.enqueue(Reply.anthropicText("ok", 10, 10));
        assertThat(chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 900)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void concurrentCallsCannotOversubscribeTheBudget() throws Exception {
        var agent = fx.runningAgent("valid-llm-small-budget.json");
        // each call reserves about 436 and really uses 400; the provider is slow so calls overlap
        STUB.always(Reply.anthropicText("ok", 200, 200).delayed(400));
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 400)).getStatusCode().value();
            }));
        }
        start.countDown();
        int served = 0;
        int rejected = 0;
        for (Future<Integer> result : results) {
            int status = result.get(60, TimeUnit.SECONDS);
            if (status == 200) {
                served++;
            } else if (status == 402) {
                rejected++;
            }
        }
        pool.shutdown();

        assertThat(served).isEqualTo(2);
        assertThat(rejected).isEqualTo(4);
        assertThat(STUB.received()).hasSize(2);
        assertThat(fx.tokens(agent.runId())).containsExactly(800, 0);
        assertThat(llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId())).extracting(LlmCall::getStatus)
                .containsOnly(LlmCallStatus.OK, LlmCallStatus.BUDGET_REJECTED);
    }
}
```

`backend/src/test/java/com/agentstore/platform/llm/GatewayErrorContractTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.llm.StubProviderServer.Reply;
import com.agentstore.platform.manifest.LlmVendor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/** The provider timeout (504) is covered where the clock can be controlled: AnthropicProviderTest and ProviderClientTest. */
class GatewayErrorContractTest extends AbstractLlmIntegrationTest {

    @Test
    void aRateLimitedCallIsRetriedOnceAndThenSucceeds() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        STUB.enqueue(Reply.status(429, "{}").withHeader("Retry-After", "0"), Reply.anthropicText("ok", 10, 5));

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(STUB.received()).hasSize(2);
        assertThat(llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId())).hasSize(1);
        assertThat(fx.tokens(agent.runId())).containsExactly(15, 0);
    }

    @Test
    void rateLimitedTwiceIsServiceUnavailable() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        STUB.always(Reply.status(429, "{}"));

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().get("code")).isEqualTo("LLM_PROVIDER_UNAVAILABLE");
        assertThat(STUB.received()).hasSize(2);
        assertFailedRow(agent.runId(), "LLM_PROVIDER_UNAVAILABLE");
    }

    @Test
    void aServerErrorTwiceIsBadGateway() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        STUB.always(Reply.status(500, "{\"error\":\"secret-provider-detail\"}"));

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().get("code")).isEqualTo("LLM_PROVIDER_ERROR");
        assertThat(response.getBody().get("status")).isEqualTo(502);
        assertThat(response.getBody().toString()).doesNotContain("secret-provider-detail");
        assertThat(STUB.received()).hasSize(2);
        assertFailedRow(agent.runId(), "LLM_PROVIDER_ERROR");
    }

    @Test
    void aProviderRejectingTheKeyIsBadGatewayAndIsNotRetried() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        STUB.always(Reply.status(401, "{\"error\":\"invalid api key\"}"));

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().get("code")).isEqualTo("LLM_PROVIDER_ERROR");
        assertThat(STUB.received()).hasSize(1);
        assertFailedRow(agent.runId(), "LLM_PROVIDER_ERROR");
    }

    @Test
    void anUnparseableReplyIsBadGateway() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        STUB.always(Reply.ok("<html>not json</html>"));

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().get("code")).isEqualTo("LLM_PROVIDER_ERROR");
        assertFailedRow(agent.runId(), "LLM_PROVIDER_ERROR");
    }

    @Test
    void anOpenCircuitFailsFastWithoutCallingTheProviderAndReleasesTheReservation() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        providerClient.breaker(LlmVendor.ANTHROPIC).transitionToOpenState();

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().get("code")).isEqualTo("LLM_PROVIDER_UNAVAILABLE");
        assertThat(STUB.received()).isEmpty();
        assertFailedRow(agent.runId(), "LLM_PROVIDER_UNAVAILABLE");

        // the OpenAI breaker is separate
        STUB.enqueue(Reply.openAiText("ok", 1, 1));
        assertThat(chat(agent.token(), request(LlmFixtures.GPT, "hi", 100)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void assertFailedRow(java.util.UUID runId, String errorCode) throws Exception {
        var calls = llmCalls.findByRunIdOrderByCreatedAtAsc(runId);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).getStatus()).isEqualTo(LlmCallStatus.ERROR);
        assertThat(calls.get(0).getErrorCode()).isEqualTo(errorCode);
        assertThat(fx.tokens(runId)).containsExactly(0, 0);
        var steps = llmSteps(runId);
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).get("status").asText()).isEqualTo("ERROR");
    }
}
```

`backend/src/test/java/com/agentstore/platform/llm/GatewayByokProxyTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.llm.StubProviderServer.Reply;
import com.agentstore.platform.manifest.LlmVendor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayByokProxyTest extends AbstractLlmIntegrationTest {

    @Autowired ByokKeyService keys;

    @Test
    void theProviderSeesTheDevelopersKeyAndTheCallCostsNothing() throws Exception {
        var agent = fx.runningAgent("valid-llm-byok-proxy.json");
        fx.verifyDeveloperOrg(agent.developerOrg());
        keys.put(agent.developerOrg().getId(), LlmVendor.ANTHROPIC, "sk-dev-own-key-0001");
        STUB.enqueue(Reply.anthropicText("hello", 100, 40));

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 500));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(STUB.received().get(0).header("x-api-key")).isEqualTo("sk-dev-own-key-0001")
                .isNotEqualTo(PLATFORM_ANTHROPIC_KEY);
        var call = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId()).get(0);
        assertThat(call.getSource()).isEqualTo(LlmSource.BYOK_PROXY);
        assertThat(call.getInputTokens()).isEqualTo(100);
        assertThat(call.getOutputTokens()).isEqualTo(40);
        assertThat(call.getCost()).as("the developer's provider bill, not ours").isEqualByComparingTo("0");
        assertThat(fx.tokens(agent.runId())).containsExactly(140, 0);
        assertThat(llmSteps(agent.runId()).get(0).get("source").asText()).isEqualTo("BYOK_PROXY");
    }

    @Test
    void withoutAStoredKeyTheCallIsABadRequestAndNeverFallsBackToThePlatformKey() throws Exception {
        var agent = fx.runningAgent("valid-llm-byok-proxy.json");

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("LLM_KEY_MISSING");
        assertThat(STUB.received()).isEmpty();
        assertThat(llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId())).isEmpty();
        assertThat(llmSteps(agent.runId())).isEmpty();
        assertThat(fx.tokens(agent.runId())).containsExactly(0, 0);
    }

    @Test
    void deletingTheKeyStopsLaterCallsAndReplacingItChangesWhatTheProviderSees() throws Exception {
        var agent = fx.runningAgent("valid-llm-byok-proxy.json");
        fx.verifyDeveloperOrg(agent.developerOrg());
        var orgId = agent.developerOrg().getId();
        keys.put(orgId, LlmVendor.ANTHROPIC, "sk-dev-first-key-0001");
        STUB.always(Reply.anthropicText("ok", 1, 1));
        assertThat(chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100)).getStatusCode()).isEqualTo(HttpStatus.OK);

        keys.put(orgId, LlmVendor.ANTHROPIC, "sk-dev-second-key-0002");
        assertThat(chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(STUB.received().get(1).header("x-api-key")).isEqualTo("sk-dev-second-key-0002");

        keys.delete(orgId, LlmVendor.ANTHROPIC);
        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("LLM_KEY_MISSING");
    }
}
```

`backend/src/test/java/com/agentstore/platform/llm/GatewayKeyLeakTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.llm.StubProviderServer.Reply;
import com.agentstore.platform.manifest.LlmVendor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A provider that echoes the key and the prompt in its error body must not get either onto any surface we control. */
@ExtendWith(OutputCaptureExtension.class)
class GatewayKeyLeakTest extends AbstractLlmIntegrationTest {

    static final String KEY = "sk-dev-leak-check-7777";
    static final String PROMPT = "PROMPT-MARKER-42";
    static final String BODY_MARKER = "provider-body-marker";

    @Autowired ByokKeyService keys;

    @Test
    void neitherTheKeyNorTheProviderBodyNorThePromptLeaks(CapturedOutput output) {
        var agent = fx.runningAgent("valid-llm-byok-proxy.json");
        fx.verifyDeveloperOrg(agent.developerOrg());
        keys.put(agent.developerOrg().getId(), LlmVendor.ANTHROPIC, KEY);
        STUB.always(Reply.status(401, "{\"error\":{\"message\":\"invalid x-api-key " + KEY + "; " + BODY_MARKER
                + "; prompt: " + PROMPT + "\"}}"));

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, PROMPT + " please", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        List<String> surfaces = List.of(
                String.valueOf(response.getBody()),
                jdbc.queryForList("SELECT * FROM llm_call WHERE run_id = ?", agent.runId()).toString(),
                jdbc.queryForList("SELECT * FROM run_step WHERE run_id = ?", agent.runId()).toString(),
                output.getAll());
        for (String surface : surfaces) {
            assertThat(surface).doesNotContain(KEY).doesNotContain(BODY_MARKER).doesNotContain(PROMPT);
        }
        assertThat(output.getAll()).as("the capture works and the call was logged").contains("llm provider call provider=anthropic");
    }
}
```

- [ ] **Step 4: Update the old 501 test**

In `backend/src/test/java/com/agentstore/platform/runtime/RuntimeResourceEndpointsTest.java`, replace the whole `theLlmEndpointIsNotImplementedYet` test (the last one in the class) with:

```java
    @Test
    void anAgentWithoutAnLlmBlockCannotUseTheLlmEndpoint() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = call(HttpMethod.POST, "/runtime/v1/llm/chat", agent.token(),
                Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("LLM_NOT_DECLARED");
    }
```

- [ ] **Step 5: Run everything new to see it fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='LlmCostTest,LlmKeyResolverTest,Gateway*Test,RuntimeResourceEndpointsTest'`
Expected: FAIL to compile (`LlmCost`, `LlmKeyResolver` do not exist) and, once they exist, the gateway tests fail with `501`.

- [ ] **Step 6: Implement the small units**

`llm/LlmCost.java`:

```java
package com.agentstore.platform.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** {@code in/1e6 × input price + out/1e6 × output price}, scale 4. Only PLATFORM calls cost us anything. */
final class LlmCost {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private LlmCost() {
    }

    static BigDecimal of(LlmSource source, LlmModel model, int inputTokens, int outputTokens) {
        if (source != LlmSource.PLATFORM) {
            return BigDecimal.ZERO.setScale(4);
        }
        return BigDecimal.valueOf(inputTokens).multiply(model.getInputPricePerMtok())
                .add(BigDecimal.valueOf(outputTokens).multiply(model.getOutputPricePerMtok()))
                .divide(MILLION, 4, RoundingMode.HALF_UP);
    }
}
```

`llm/LlmKeyResolver.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.manifest.LlmMode;
import com.agentstore.platform.manifest.LlmVendor;
import com.agentstore.platform.runtime.RuntimeApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Picks the key for an outgoing call: the platform's own, or the developer org's. Never falls back from one to the other. */
@Component
public class LlmKeyResolver {

    private final LlmProperties properties;
    private final ByokKeyService byokKeys;

    public LlmKeyResolver(LlmProperties properties, ByokKeyService byokKeys) {
        this.properties = properties;
        this.byokKeys = byokKeys;
    }

    public String resolve(LlmMode mode, LlmVendor vendor, UUID developerOrgId) {
        if (mode == LlmMode.PLATFORM) {
            return properties.platformKey(vendor).orElseThrow(() -> new RuntimeApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "LLM_PROVIDER_UNAVAILABLE",
                    "The platform has no " + vendor.wire() + " key configured"));
        }
        return byokKeys.reveal(developerOrgId, vendor).orElseThrow(() -> new RuntimeApiException(
                HttpStatus.BAD_REQUEST, "LLM_KEY_MISSING",
                "No " + vendor.wire() + " key is stored for this agent's developer organisation"));
    }
}
```

`llm/RunLlmContext.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.execution.RunLlmMode;

import java.util.UUID;

/** What the gateway needs from the run row and its agent: whose key, and how the calls are served. */
public record RunLlmContext(UUID developerOrgId, RunLlmMode llmMode, UUID replayRunId, String llmMockJson) {
}
```

`llm/RunLlmContextLoader.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.runtime.RuntimeApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Reads the run's LLM settings in one short read-only transaction (open-in-view is off). */
@Component
public class RunLlmContextLoader {

    private final RunRepository runs;

    public RunLlmContextLoader(RunRepository runs) {
        this.runs = runs;
    }

    @Transactional(readOnly = true)
    public RunLlmContext load(UUID runId) {
        Run run = runs.findById(runId)
                .orElseThrow(() -> new RuntimeApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Run not found"));
        return new RunLlmContext(run.getAgentVersion().getAgent().getDeveloperOrg().getId(), run.getLlmMode(),
                run.getReplayRunId(), run.getLlmMockJson());
    }
}
```

`llm/LlmCallLog.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.execution.RunStepKind;
import com.agentstore.platform.runtime.RunPrincipal;
import com.agentstore.platform.runtime.RunStepRecorder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Writes the {@code llm_call} row and the run's {@code LLM_CALL} step for every call the gateway metered. */
@Component
public class LlmCallLog {

    private final LlmCallRepository calls;
    private final RunStepRecorder steps;

    public LlmCallLog(LlmCallRepository calls, RunStepRecorder steps) {
        this.calls = calls;
        this.steps = steps;
    }

    /**
     * Saves and flushes the row first (a unique-index conflict on a replay ordinal surfaces here, before any step is
     * written), then records the step. {@code run_step} has no block column until WS1b, so the block rides in the detail.
     */
    public void record(RunPrincipal principal, LlmCall call) {
        calls.saveAndFlush(call);
        steps.record(principal, RunStepKind.LLM_CALL, detail(call));
    }

    static Map<String, Object> detail(LlmCall call) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("block", "llm");
        detail.put("calls", 1);
        detail.put("tokens", call.getInputTokens() + call.getOutputTokens());
        detail.put("inputTokens", call.getInputTokens());
        detail.put("outputTokens", call.getOutputTokens());
        detail.put("model", call.getModelId());
        detail.put("source", call.getSource().name());
        detail.put("status", call.getStatus().name());
        if (call.getErrorCode() != null) {
            detail.put("errorCode", call.getErrorCode());
        }
        return detail;
    }
}
```

- [ ] **Step 7: Implement the gateway**

`llm/GatewayLlmBackend.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.manifest.LlmDecl;
import com.agentstore.platform.manifest.LlmMode;
import com.agentstore.platform.runtime.LlmBackend;
import com.agentstore.platform.runtime.RunPrincipal;
import com.agentstore.platform.runtime.RuntimeApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * {@code POST /runtime/v1/llm/chat}. Validates, checks the model, reserves budget, calls the provider through
 * {@link ProviderClient}, settles, and writes the call row and step. No database transaction is held across the
 * provider call: {@link LlmBudget}, {@link LlmCallLog} and {@link RunLlmContextLoader} each use their own short one.
 */
@Service
public class GatewayLlmBackend implements LlmBackend {

    private final LlmRequestNormalizer normalizer;
    private final LlmModelRepository models;
    private final RunLlmContextLoader contexts;
    private final LlmKeyResolver keys;
    private final LlmBudget budget;
    private final ProviderClient providers;
    private final LlmCallLog callLog;

    public GatewayLlmBackend(LlmRequestNormalizer normalizer, LlmModelRepository models, RunLlmContextLoader contexts,
                             LlmKeyResolver keys, LlmBudget budget, ProviderClient providers, LlmCallLog callLog) {
        this.normalizer = normalizer;
        this.models = models;
        this.contexts = contexts;
        this.keys = keys;
        this.budget = budget;
        this.providers = providers;
        this.callLog = callLog;
    }

    @Override
    public Map<String, Object> chat(RunPrincipal principal, Map<String, Object> rawRequest) {
        LlmDecl llm = principal.manifest().llm();
        if (llm == null) {
            throw new RuntimeApiException(HttpStatus.BAD_REQUEST, "LLM_NOT_DECLARED",
                    "This agent's manifest does not declare an llm block");
        }
        if (llm.mode() == LlmMode.BYOK_DIRECT) {
            throw new RuntimeApiException(HttpStatus.BAD_REQUEST, "USE_DIRECT_PROVIDER",
                    "This agent calls its LLM provider directly; /llm/chat is not available for llm.mode byok_direct");
        }
        LlmRequestNormalizer.Normalized normalized = normalizer.normalize(rawRequest);
        LlmModel model = allowedModel(llm, normalized.request().model());
        RunLlmContext context = contexts.load(principal.runId());
        return callProvider(principal, llm, context, model, normalized).toMap();
    }

    private LlmModel allowedModel(LlmDecl llm, String modelId) {
        if (!llm.models().contains(modelId)) {
            throw modelNotAllowed(modelId);
        }
        return models.findByIdAndEnabledTrue(modelId).orElseThrow(() -> modelNotAllowed(modelId));
    }

    private static RuntimeApiException modelNotAllowed(String modelId) {
        return new RuntimeApiException(HttpStatus.BAD_REQUEST, "MODEL_NOT_ALLOWED",
                "Model '" + modelId + "' is not available to this agent");
    }

    private LlmResponse callProvider(RunPrincipal principal, LlmDecl llm, RunLlmContext context, LlmModel model,
                                     LlmRequestNormalizer.Normalized normalized) {
        UUID runId = principal.runId();
        LlmRequest request = normalized.request();
        LlmSource source = llm.mode() == LlmMode.PLATFORM ? LlmSource.PLATFORM : LlmSource.BYOK_PROXY;
        long started = System.nanoTime();

        String apiKey;
        try {
            apiKey = keys.resolve(llm.mode(), model.getProvider(), context.developerOrgId());
        } catch (RuntimeApiException e) {
            if (e.status().is5xxServerError()) {
                callLog.record(principal, failed(runId, model, source, normalized.hash(), e.code(), started));
            }
            throw e;
        }

        long reservation = normalized.estimatedInputTokens() + request.maxTokens();
        if (!budget.reserve(runId, reservation, llm.maxTokensPerRun())) {
            LlmCall rejected = new LlmCall(UUID.randomUUID(), runId, model.getId(), source,
                    LlmCallStatus.BUDGET_REJECTED, normalized.hash());
            rejected.setErrorCode("BUDGET_EXCEEDED");
            rejected.setLatencyMs(millisSince(started));
            callLog.record(principal, rejected);
            throw new RuntimeApiException(HttpStatus.PAYMENT_REQUIRED, "BUDGET_EXCEEDED",
                    "This call does not fit the run's LLM token budget of " + llm.maxTokensPerRun() + " tokens");
        }

        LlmResponse response;
        try {
            response = providers.chat(model.getProvider(), request, apiKey);
        } catch (RuntimeApiException e) {
            budget.release(runId, reservation);
            callLog.record(principal, failed(runId, model, source, normalized.hash(), e.code(), started));
            throw e;
        } catch (RuntimeException e) {
            budget.release(runId, reservation);
            callLog.record(principal, failed(runId, model, source, normalized.hash(), "LLM_PROVIDER_ERROR", started));
            throw new RuntimeApiException(HttpStatus.BAD_GATEWAY, "LLM_PROVIDER_ERROR", "The LLM provider call failed");
        }

        budget.settle(runId, reservation, (long) response.inputTokens() + response.outputTokens());
        LlmCall call = new LlmCall(UUID.randomUUID(), runId, model.getId(), source, LlmCallStatus.OK, normalized.hash());
        call.setInputTokens(response.inputTokens());
        call.setOutputTokens(response.outputTokens());
        call.setCost(LlmCost.of(source, model, response.inputTokens(), response.outputTokens()));
        call.setLatencyMs(millisSince(started));
        callLog.record(principal, call);
        return response;
    }

    private static LlmCall failed(UUID runId, LlmModel model, LlmSource source, String hash, String errorCode,
                                  long startedNanos) {
        LlmCall call = new LlmCall(UUID.randomUUID(), runId, model.getId(), source, LlmCallStatus.ERROR, hash);
        call.setErrorCode(errorCode);
        call.setLatencyMs(millisSince(startedNanos));
        return call;
    }

    private static int millisSince(long startedNanos) {
        return (int) ((System.nanoTime() - startedNanos) / 1_000_000);
    }
}
```

Replace the whole of `runtime/RuntimeLlmController.java` with:

```java
package com.agentstore.platform.runtime;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The gateway records its own run steps (so failed calls are traced too); this only hands the call over. */
@RestController
@RequestMapping("/runtime/v1/llm")
public class RuntimeLlmController {

    private final LlmBackend backend;

    public RuntimeLlmController(LlmBackend backend) {
        this.backend = backend;
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> request) {
        return backend.chat(RunPrincipal.current(), request);
    }
}
```

Delete the 501 stub:

```bash
git rm backend/src/main/java/com/agentstore/platform/runtime/UnavailableLlmBackend.java
```

- [ ] **Step 8: Run the new tests and every runtime test**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='LlmCostTest,LlmKeyResolverTest,Gateway*Test,com.agentstore.platform.runtime.*Test'`
Expected: PASS. (`RuntimeContractTest` still passes: the request/response schema of `/llm/chat` is unchanged; the contract changes in Task 9.) If `concurrentCallsCannotOversubscribeTheBudget` is flaky on a very slow machine, raise the stub delay, not the assertion.

- [ ] **Step 9: Commit**

```bash
git add backend
git commit -m "feat(llm): gateway backend with metering, budget, steps and the error contract"
```

---

### Task 7: `byok_direct` — key injection at launch

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/llm/LlmKeyMissingException.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunLauncher.java`
- Test: `backend/src/test/java/com/agentstore/platform/llm/ByokDirectLaunchTest.java`

**Interfaces:**
- Consumes: `ByokKeyService.reveal(UUID, LlmVendor)` (Task 5), `LlmDecl`/`LlmMode`/`LlmVendor` (Task 1), `FakeSandboxProvider.specForRun(UUID)` (existing, `!runner` profile), `ExecutionFixtures.run(...)`, `SecretService.store(connectionId, name, value)`.
- Produces: `LlmKeyMissingException(LlmVendor)` (unchecked); `RunLauncher` puts `AGENT_SECRET_<PROVIDER>_API_KEY` (e.g. `AGENT_SECRET_ANTHROPIC_API_KEY`) in the sandbox environment for `byok_direct` runs, and fails a launch with no stored credential as `400 LLM_KEY_MISSING` (run `FAILED`, step code `LLM_KEY_MISSING`, no sandbox created).

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/llm/ByokDirectLaunchTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.FakeSandboxProvider;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunLauncher;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.execution.RunStep;
import com.agentstore.platform.execution.RunStepKind;
import com.agentstore.platform.execution.RunStepRepository;
import com.agentstore.platform.execution.SecretService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.manifest.LlmVendor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ByokDirectLaunchTest extends AbstractIntegrationTest {

    static final String DEV_KEY = "sk-dev-direct-key-0001";

    @Autowired LlmFixtures fx;
    @Autowired ExecutionFixtures executionFixtures;
    @Autowired RunLauncher launcher;
    @Autowired FakeSandboxProvider sandbox;
    @Autowired ByokKeyService keys;
    @Autowired SecretService secrets;
    @Autowired RunRepository runs;
    @Autowired RunStepRepository steps;

    ExecutionFixtures.Scenario scenario(String manifest) {
        return executionFixtures.liveWithManifest(manifest, List.of("bank_statement"), new BigDecimal("500.00"),
                new BigDecimal("1000.00"));
    }

    /** A PENDING run, the way the trigger leaves it just before the launcher starts the sandbox. */
    Run pendingRun(ExecutionFixtures.Scenario scenario) {
        return executionFixtures.run(scenario, RunStatus.PENDING, null, "tok-" + UUID.randomUUID());
    }

    Org developerOf(ExecutionFixtures.Scenario scenario) {
        return scenario.version().getAgent().getDeveloperOrg();
    }

    @Test
    void aByokDirectRunGetsTheDevelopersKeyInItsEnvironment() {
        var scenario = scenario("valid-llm-byok-direct.json");
        fx.verifyDeveloperOrg(developerOf(scenario));
        keys.put(developerOf(scenario).getId(), LlmVendor.ANTHROPIC, DEV_KEY);
        Run run = pendingRun(scenario);

        launcher.launch(run.getId());

        var env = sandbox.specForRun(run.getId()).envVars();
        assertThat(env).containsEntry("AGENT_SECRET_ANTHROPIC_API_KEY", DEV_KEY);
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void aBuyerSecretWithTheSameNameCannotReplaceTheDevelopersKey() {
        var scenario = scenario("valid-llm-byok-direct.json");
        fx.verifyDeveloperOrg(developerOf(scenario));
        keys.put(developerOf(scenario).getId(), LlmVendor.ANTHROPIC, DEV_KEY);
        secrets.store(scenario.connection().getId(), "ANTHROPIC_API_KEY", "buyer-supplied-value");
        Run run = pendingRun(scenario);

        launcher.launch(run.getId());

        assertThat(sandbox.specForRun(run.getId()).envVars()).containsEntry("AGENT_SECRET_ANTHROPIC_API_KEY", DEV_KEY);
    }

    @Test
    void withoutAStoredKeyTheLaunchFailsWithLlmKeyMissingAndNoSandboxIsCreated() {
        var scenario = scenario("valid-llm-byok-direct.json");
        Run run = pendingRun(scenario);

        assertThatThrownBy(() -> launcher.launch(run.getId())).isInstanceOfSatisfying(ResponseStatusException.class, e -> {
            assertThat(e.getStatusCode().value()).isEqualTo(400);
            assertThat(e.getReason()).isEqualTo("LLM_KEY_MISSING");
        });

        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
        RunStep failed = steps.findByRunIdOrderByCreatedAtAsc(run.getId()).stream()
                .filter(s -> s.getKind() == RunStepKind.FAILED).findFirst().orElseThrow();
        assertThat(failed.getDetail()).contains("LLM_KEY_MISSING");
        assertThatThrownBy(() -> sandbox.specForRun(run.getId())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void platformAndProxyRunsGetNoLlmKeyInTheirEnvironment() {
        var platform = scenario("valid-llm-platform.json");
        var proxy = scenario("valid-llm-byok-proxy.json");
        fx.verifyDeveloperOrg(developerOf(proxy));
        keys.put(developerOf(proxy).getId(), LlmVendor.ANTHROPIC, DEV_KEY);
        Run platformRun = pendingRun(platform);
        Run proxyRun = pendingRun(proxy);

        launcher.launch(platformRun.getId());
        launcher.launch(proxyRun.getId());

        for (Run run : List.of(platformRun, proxyRun)) {
            var env = sandbox.specForRun(run.getId()).envVars();
            assertThat(env.keySet()).noneMatch(k -> k.startsWith("AGENT_SECRET_ANTHROPIC") || k.startsWith("AGENT_SECRET_OPENAI"));
            assertThat(env.values()).doesNotContain(DEV_KEY);
        }
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=ByokDirectLaunchTest`
Expected: FAIL (the environment has no `AGENT_SECRET_ANTHROPIC_API_KEY`; the missing-key launch is a 500, not a 400).

- [ ] **Step 3: Implement**

`llm/LlmKeyMissingException.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.manifest.LlmVendor;

/** A {@code byok_direct} launch found no stored credential for the provider. Carries no key material. */
public class LlmKeyMissingException extends RuntimeException {

    public LlmKeyMissingException(LlmVendor vendor) {
        super("No " + vendor.wire() + " key is stored for this agent's developer organisation");
    }
}
```

In `execution/RunLauncher.java`:

1. Add imports:

```java
import com.agentstore.platform.llm.ByokKeyService;
import com.agentstore.platform.llm.LlmKeyMissingException;
import com.agentstore.platform.manifest.LlmMode;
import com.agentstore.platform.manifest.LlmVendor;
```

2. Add the field and constructor parameter. Replace

```java
    private final RunLifecycleService runLifecycleService;
    private final String callbackBaseUrl;
```

with

```java
    private final RunLifecycleService runLifecycleService;
    private final ByokKeyService byokKeys;
    private final String callbackBaseUrl;
```

replace

```java
                       RunLifecycleService runLifecycleService,
                       @Value("${agentstore.callback.base-url}") String callbackBaseUrl,
```

with

```java
                       RunLifecycleService runLifecycleService, ByokKeyService byokKeys,
                       @Value("${agentstore.callback.base-url}") String callbackBaseUrl,
```

and replace `        this.runLifecycleService = runLifecycleService;` with

```java
        this.runLifecycleService = runLifecycleService;
        this.byokKeys = byokKeys;
```

3. Inject the key after the buyer's secrets. Replace

```java
            for (Secret secret : secretRepository.findByConnectionId(connection.getId())) {
                envVars.put("AGENT_SECRET_" + secret.getName(), secretService.reveal(connection.getId(), secret.getName()));
            }
```

with

```java
            for (Secret secret : secretRepository.findByConnectionId(connection.getId())) {
                envVars.put("AGENT_SECRET_" + secret.getName(), secretService.reveal(connection.getId(), secret.getName()));
            }
            // After the buyer's secrets, so a connection secret of the same name can never replace the developer's key.
            if (manifest.llm() != null && manifest.llm().mode() == LlmMode.BYOK_DIRECT) {
                LlmVendor vendor = manifest.llm().provider();
                UUID developerOrgId = version.getAgent().getDeveloperOrg().getId();
                envVars.put("AGENT_SECRET_" + vendor.name() + "_API_KEY", byokKeys.reveal(developerOrgId, vendor)
                        .orElseThrow(() -> new LlmKeyMissingException(vendor)));
            }
```

4. Fail the launch cleanly. Replace the first catch

```java
        } catch (RuntimeException e) {
            runLifecycleService.terminate(runId, RunStatus.PENDING, RunStatus.FAILED, RunStepKind.FAILED,
                    Map.of("summary", "Run could not be launched"));
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Run could not be launched", e);
        }
```

with

```java
        } catch (LlmKeyMissingException e) {
            runLifecycleService.terminate(runId, RunStatus.PENDING, RunStatus.FAILED, RunStepKind.FAILED,
                    Map.of("summary", "The agent cannot start because its LLM key is not configured",
                            "code", "LLM_KEY_MISSING"));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "LLM_KEY_MISSING", e);
        } catch (RuntimeException e) {
            runLifecycleService.terminate(runId, RunStatus.PENDING, RunStatus.FAILED, RunStepKind.FAILED,
                    Map.of("summary", "Run could not be launched"));
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Run could not be launched", e);
        }
```

- [ ] **Step 4: Run the new test and the execution suites**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='ByokDirectLaunchTest,com.agentstore.platform.execution.*Test,EndToEndTier*Test'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(llm): inject the developer's key for byok_direct runs and fail launches without one"
```

---

### Task 8: Mock, replay, recording, retention

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/llm/LlmRecorder.java`, `MockResponder.java`, `ReplayResponder.java`, `LlmRecordingRetentionService.java`, `LlmRecordingRetentionJob.java`
- Modify: `llm/LlmCallRepository.java`, `llm/GatewayLlmBackend.java`
- Test: `backend/src/test/java/com/agentstore/platform/llm/MockResponderTest.java`, `LlmRecorderTest.java`, `GatewayMockTest.java`, `GatewayReplayTest.java`, `GatewayRecordingTest.java`, `LlmRecordingRetentionTest.java`

**Interfaces:**
- Consumes: `LlmFixtures.configureRun(runId, RunMode, RunLlmMode, replayRunId, mockJson)`, `AbstractLlmIntegrationTest` (Task 6), `ObjectStorage` (`put`, `open`, `size`, `deleteQuietly`), `StorageException`, `LlmCallLog`, `LlmResponse.fromMap`, `LlmRequest.toMap`, `RunMode`/`RunLlmMode`.
- Produces:
  - `LlmRecorder` (`@Component`): `void attach(LlmCall, LlmRequest, LlmResponse)` (writes `llm/{runId}/{callId}/request.json|response.json`, sets `requestKey`, `responseKey`, `recordingExpiresAt = now + agentstore.llm.recording-ttl`; a storage failure is logged and leaves the keys null), `LlmResponse readResponse(String key, String fallbackModel)`, `void discard(LlmCall)`, `void delete(String key)`.
  - `MockResponder.respond(String mockJson, LlmRequest, String hash) → LlmResponse` (exact hash, then `default`, then the fixed stub `[mock response]`).
  - `ReplayResponder.serve(RunPrincipal, UUID sourceRunId, LlmModel, LlmRequestNormalizer.Normalized) → LlmResponse` (throws 409 `REPLAY_MISS` carrying `errors: [{requestHash}]`, 410 `REPLAY_EXPIRED`).
  - `LlmRecordingRetentionService.purgeOnce()` / `purgeOnce(Instant now) → int`; `LlmRecordingRetentionJob` (hourly, ShedLock `llmRecordingRetention`).
  - `GatewayLlmBackend` dispatches on `Run.llmMode` (MOCK/REPLAY skip key resolution, budget and the provider) and records SANDBOX real calls.

- [ ] **Step 1: Write the failing unit tests**

`backend/src/test/java/com/agentstore/platform/llm/MockResponderTest.java`:

```java
package com.agentstore.platform.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MockResponderTest {

    static final String HASH = "a".repeat(64);
    static final LlmRequest REQUEST = new LlmRequest("m", null,
            List.of(new LlmRequest.Msg("user", List.of(Map.of("type", "text", "text", "hi")))), List.of(), 100, null);

    final MockResponder responder = new MockResponder(new ObjectMapper());

    static String text(LlmResponse response) {
        return (String) response.content().get(0).get("text");
    }

    @Test
    void anExactHashWinsOverTheDefault() {
        String json = """
                {"responses":{"%s":{"content":[{"type":"text","text":"exact"}],"stopReason":"max_tokens",
                   "usage":{"inputTokens":7,"outputTokens":3}}},
                 "default":{"content":[{"type":"text","text":"default"}]}}""".formatted(HASH);

        LlmResponse response = responder.respond(json, REQUEST, HASH);

        assertThat(text(response)).isEqualTo("exact");
        assertThat(response.stopReason()).isEqualTo("max_tokens");
        assertThat(response.inputTokens()).isEqualTo(7);
        assertThat(response.outputTokens()).isEqualTo(3);
        assertThat(response.model()).isEqualTo("m");
    }

    @Test
    void theDefaultAnswersEverythingElse() {
        String json = """
                {"responses":{"%s":{"content":[{"type":"text","text":"exact"}]}},
                 "default":{"content":[{"type":"text","text":"default"}],"usage":{"inputTokens":1,"outputTokens":2}}}"""
                .formatted("b".repeat(64));

        LlmResponse response = responder.respond(json, REQUEST, HASH);

        assertThat(text(response)).isEqualTo("default");
        assertThat(response.inputTokens()).isEqualTo(1);
        assertThat(response.outputTokens()).isEqualTo(2);
    }

    @Test
    void aMockRunNeverFailsForWantOfAnEntry() {
        for (String json : new String[]{null, "", "  ", "{}", "not json", "[]", "{\"responses\":{}}",
                "{\"responses\":\"nope\",\"default\":7}"}) {
            LlmResponse response = responder.respond(json, REQUEST, HASH);

            assertThat(text(response)).as(String.valueOf(json)).isEqualTo("[mock response]");
            assertThat(response.stopReason()).isEqualTo("end_turn");
            assertThat(response.inputTokens()).isZero();
            assertThat(response.model()).isEqualTo("m");
        }
    }

    @Test
    void aMalformedEntryIsTreatedAsAbsent() {
        String json = """
                {"responses":{"%s":{"content":"not an array"}},
                 "default":{"content":[{"type":"text","text":"default"}]}}""".formatted(HASH);

        assertThat(text(responder.respond(json, REQUEST, HASH))).isEqualTo("default");
        assertThat(text(responder.respond("{\"default\":{\"content\":[{\"type\":\"image\"}]}}", REQUEST, HASH)))
                .isEqualTo("[mock response]");
    }

    @Test
    void aCannedToolUseBlockIsServed() {
        String json = """
                {"default":{"content":[{"type":"tool_use","id":"t1","name":"lookup","input":{"q":"x"}}],
                            "stopReason":"tool_use"}}""";

        LlmResponse response = responder.respond(json, REQUEST, HASH);

        assertThat(response.content().get(0)).containsEntry("type", "tool_use").containsEntry("name", "lookup");
        assertThat(response.stopReason()).isEqualTo("tool_use");
    }
}
```

`backend/src/test/java/com/agentstore/platform/llm/LlmRecorderTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.storage.ObjectStorage;
import com.agentstore.platform.storage.StorageException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LlmRecorderTest {

    final ObjectStorage storage = mock(ObjectStorage.class);
    final LlmRecorder recorder = new LlmRecorder(storage, new ObjectMapper(), LlmKeyResolverTest.props("", ""));
    final LlmRequest request = new LlmRequest("m", null,
            List.of(new LlmRequest.Msg("user", List.of(Map.of("type", "text", "text", "hi")))), List.of(), 100, null);
    final LlmResponse response = new LlmResponse("m", List.of(Map.of("type", "text", "text", "ok")), "end_turn", 1, 1);

    LlmCall call() {
        return new LlmCall(UUID.randomUUID(), UUID.randomUUID(), "m", LlmSource.PLATFORM, LlmCallStatus.OK, "a".repeat(64));
    }

    @Test
    void aStorageFailureNeverFailsTheCallAndLeavesNoKeys() {
        doThrow(new StorageException("down", null)).when(storage).put(anyString(), any(byte[].class));
        LlmCall call = call();

        recorder.attach(call, request, response);

        assertThat(call.getRequestKey()).isNull();
        assertThat(call.getResponseKey()).isNull();
        assertThat(call.getRecordingExpiresAt()).isNull();
        verify(storage).deleteQuietly("llm/" + call.getRunId() + "/" + call.getId() + "/request.json");
        verify(storage).deleteQuietly("llm/" + call.getRunId() + "/" + call.getId() + "/response.json");
    }

    @Test
    void aSuccessfulAttachSetsTheKeysAndAFourteenDayExpiry() {
        LlmCall call = call();

        recorder.attach(call, request, response);

        assertThat(call.getRequestKey()).isEqualTo("llm/" + call.getRunId() + "/" + call.getId() + "/request.json");
        assertThat(call.getResponseKey()).isEqualTo("llm/" + call.getRunId() + "/" + call.getId() + "/response.json");
        assertThat(call.getRecordingExpiresAt()).isAfter(java.time.Instant.now().plus(java.time.Duration.ofDays(14)).minusSeconds(60))
                .isBefore(java.time.Instant.now().plus(java.time.Duration.ofDays(14)).plusSeconds(60));
    }
}
```

- [ ] **Step 2: Write the failing integration tests**

`backend/src/test/java/com/agentstore/platform/llm/GatewayMockTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.execution.RunLlmMode;
import com.agentstore.platform.execution.RunMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayMockTest extends AbstractLlmIntegrationTest {

    @Autowired LlmRequestNormalizer normalizer;

    String hashOf(String text) {
        return normalizer.normalize(request(LlmFixtures.CLAUDE, text, 100)).hash();
    }

    static String textOf(org.springframework.http.ResponseEntity<Map> response) {
        return (String) ((Map<?, ?>) ((List<?>) response.getBody().get("content")).get(0)).get("text");
    }

    LlmFixtures.LlmAgent mockAgent(String manifest, String mockJson) {
        var agent = fx.runningAgent(manifest);
        fx.configureRun(agent.runId(), RunMode.SANDBOX, RunLlmMode.MOCK, null, mockJson);
        return agent;
    }

    @Test
    void anExactHashHitIsServedFreeAndTraced() throws Exception {
        String json = """
                {"responses":{"%s":{"content":[{"type":"text","text":"canned!"}],"usage":{"inputTokens":7,"outputTokens":3}}},
                 "default":{"content":[{"type":"text","text":"default!"}]}}""".formatted(hashOf("hi"));
        var agent = mockAgent("valid-llm-platform.json", json);

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(textOf(response)).isEqualTo("canned!");
        assertThat(response.getBody().get("usage")).isEqualTo(Map.of("inputTokens", 7, "outputTokens", 3));
        assertThat(STUB.received()).as("a mock run never reaches a provider").isEmpty();
        var call = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId()).get(0);
        assertThat(call.getSource()).isEqualTo(LlmSource.MOCK);
        assertThat(call.getStatus()).isEqualTo(LlmCallStatus.OK);
        assertThat(call.getInputTokens()).isEqualTo(7);
        assertThat(call.getOutputTokens()).isEqualTo(3);
        assertThat(call.getCost()).isEqualByComparingTo("0");
        assertThat(call.getRequestKey()).as("mock calls are never recorded").isNull();
        assertThat(fx.tokens(agent.runId())).as("never reserves or uses budget").containsExactly(0, 0);
        var step = llmSteps(agent.runId()).get(0);
        assertThat(step.get("source").asText()).isEqualTo("MOCK");
        assertThat(step.get("status").asText()).isEqualTo("OK");
    }

    @Test
    void otherRequestsGetTheDefaultAndThenTheGenericStub() throws Exception {
        String withDefault = "{\"default\":{\"content\":[{\"type\":\"text\",\"text\":\"default!\"}]}}";
        var a = mockAgent("valid-llm-platform.json", withDefault);
        var b = mockAgent("valid-llm-platform.json", null);
        var c = mockAgent("valid-llm-platform.json", "{\"responses\":{}}");

        assertThat(textOf(chat(a.token(), request(LlmFixtures.CLAUDE, "anything", 100)))).isEqualTo("default!");
        assertThat(textOf(chat(b.token(), request(LlmFixtures.CLAUDE, "anything", 100)))).isEqualTo("[mock response]");
        assertThat(textOf(chat(c.token(), request(LlmFixtures.CLAUDE, "anything", 100)))).isEqualTo("[mock response]");
    }

    @Test
    void aMockCallIgnoresTheTokenBudgetButNotTheModelAllowlistOrRequestValidation() throws Exception {
        var agent = mockAgent("valid-llm-small-budget.json", null);   // 1,000 tokens per run

        var big = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 20_000));
        var wrongModel = chat(agent.token(), request(LlmFixtures.GPT, "hi", 100));
        var invalid = chat(agent.token(), Map.of("model", "test-claude",
                "messages", List.of(Map.of("role", "user", "content", "hi"))));

        assertThat(big.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(wrongModel.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(wrongModel.getBody().get("code")).isEqualTo("MODEL_NOT_ALLOWED");
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody().get("code")).isEqualTo("INVALID_REQUEST");
        assertThat(fx.tokens(agent.runId())).containsExactly(0, 0);
        assertThat(llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId())).hasSize(1);
    }

    @Test
    void aMockRunNeedsNoKeyEvenForByokProxy() throws Exception {
        var agent = mockAgent("valid-llm-byok-proxy.json", null);   // no developer key stored

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(textOf(response)).isEqualTo("[mock response]");
    }
}
```

`backend/src/test/java/com/agentstore/platform/llm/GatewayReplayTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.execution.RunLlmMode;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.llm.StubProviderServer.Reply;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayReplayTest extends AbstractLlmIntegrationTest {

    @Autowired LlmRequestNormalizer normalizer;
    @Autowired LlmRecordingRetentionService retention;

    @SuppressWarnings("rawtypes")
    static String textOf(ResponseEntity<Map> response) {
        return (String) ((Map<?, ?>) ((List<?>) response.getBody().get("content")).get(0)).get("text");
    }

    String hashOf(String text) {
        return normalizer.normalize(request(LlmFixtures.CLAUDE, text, 100)).hash();
    }

    /** A SANDBOX run whose real calls are recorded: "same" twice (first, second) and "different" once (other). */
    LlmFixtures.LlmAgent recordedSource(RunMode mode) {
        var source = fx.runningAgent("valid-llm-platform.json");
        fx.configureRun(source.runId(), mode, null, null, null);
        STUB.enqueue(Reply.anthropicText("first", 10, 5), Reply.anthropicText("second", 20, 6),
                Reply.anthropicText("other", 30, 7));
        chat(source.token(), request(LlmFixtures.CLAUDE, "same", 100));
        chat(source.token(), request(LlmFixtures.CLAUDE, "same", 100));
        chat(source.token(), request(LlmFixtures.CLAUDE, "different", 100));
        return source;
    }

    LlmFixtures.LlmAgent replayer(UUID sourceRunId) {
        var replay = fx.runningAgent("valid-llm-platform.json");
        fx.configureRun(replay.runId(), RunMode.SANDBOX, RunLlmMode.REPLAY, sourceRunId, null);
        return replay;
    }

    @Test
    void duplicatesAreConsumedInRecordedOrderForFree() throws Exception {
        var source = recordedSource(RunMode.SANDBOX);
        int providerCalls = STUB.received().size();
        assertThat(providerCalls).isEqualTo(3);
        var replay = replayer(source.runId());

        assertThat(textOf(chat(replay.token(), request(LlmFixtures.CLAUDE, "same", 100)))).isEqualTo("first");
        assertThat(textOf(chat(replay.token(), request(LlmFixtures.CLAUDE, "same", 100)))).isEqualTo("second");
        assertThat(textOf(chat(replay.token(), request(LlmFixtures.CLAUDE, "different", 100)))).isEqualTo("other");

        assertThat(STUB.received()).as("replay never reaches a provider").hasSize(providerCalls);
        var rows = llmCalls.findByRunIdOrderByCreatedAtAsc(replay.runId());
        assertThat(rows).extracting(LlmCall::getSource).containsOnly(LlmSource.REPLAY);
        assertThat(rows).extracting(LlmCall::getStatus).containsOnly(LlmCallStatus.OK);
        assertThat(rows).extracting(LlmCall::getReplayOrdinal).containsExactly(1, 2, 1);
        assertThat(rows).extracting(LlmCall::getInputTokens).containsExactly(10, 20, 30);
        assertThat(rows).extracting(LlmCall::getOutputTokens).containsExactly(5, 6, 7);
        assertThat(rows).allSatisfy(r -> assertThat(r.getCost()).isEqualByComparingTo("0"));
        assertThat(rows).as("a replayed call in a SANDBOX run is itself recorded").allSatisfy(r -> {
            assertThat(r.getRequestKey()).isNotNull();
            assertThat(r.getRecordingExpiresAt()).isNotNull();
        });
        assertThat(fx.tokens(replay.runId())).as("replayed tokens do not count towards the budget").containsExactly(0, 0);
        assertThat(llmSteps(replay.runId())).extracting(s -> s.get("source").asText()).containsOnly("REPLAY");
    }

    @Test
    void theRequestAfterTheLastRecordedDuplicateIsAMissNeverAPaidCall() throws Exception {
        var source = recordedSource(RunMode.SANDBOX);
        int providerCalls = STUB.received().size();
        var replay = replayer(source.runId());
        chat(replay.token(), request(LlmFixtures.CLAUDE, "same", 100));
        chat(replay.token(), request(LlmFixtures.CLAUDE, "same", 100));

        var third = chat(replay.token(), request(LlmFixtures.CLAUDE, "same", 100));
        var fourth = chat(replay.token(), request(LlmFixtures.CLAUDE, "same", 100));

        for (var miss : List.of(third, fourth)) {
            assertThat(miss.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(miss.getBody().get("code")).isEqualTo("REPLAY_MISS");
            assertThat(miss.getBody().get("detail").toString()).contains(hashOf("same"));
            assertThat(((Map<?, ?>) ((List<?>) miss.getBody().get("errors")).get(0)).get("requestHash"))
                    .isEqualTo(hashOf("same"));
        }
        assertThat(STUB.received()).hasSize(providerCalls);
        var rows = llmCalls.findByRunIdOrderByCreatedAtAsc(replay.runId());
        assertThat(rows).extracting(LlmCall::getStatus).containsExactly(LlmCallStatus.OK, LlmCallStatus.OK,
                LlmCallStatus.REPLAY_MISS, LlmCallStatus.REPLAY_MISS);
        assertThat(rows.get(2).getReplayOrdinal()).as("a miss never consumes an ordinal").isNull();
        assertThat(rows.get(2).getErrorCode()).isEqualTo("REPLAY_MISS");
        assertThat(llmSteps(replay.runId()).get(2).get("status").asText()).isEqualTo("REPLAY_MISS");
    }

    @Test
    void aRequestThatWasNeverRecordedIsAMiss() throws Exception {
        var source = recordedSource(RunMode.SANDBOX);
        int providerCalls = STUB.received().size();
        var replay = replayer(source.runId());

        var response = chat(replay.token(), request(LlmFixtures.CLAUDE, "never seen", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("REPLAY_MISS");
        assertThat(STUB.received()).hasSize(providerCalls);
    }

    @Test
    void aReplayRunWithoutASourceIsAMissToo() {
        var replay = replayer(null);

        var response = chat(replay.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("REPLAY_MISS");
        assertThat(STUB.received()).isEmpty();
    }

    @Test
    void aSourceThatNeverRecordedIsAMiss() {
        var source = recordedSource(RunMode.PRODUCTION);   // production calls are never recorded
        int providerCalls = STUB.received().size();
        var replay = replayer(source.runId());

        var response = chat(replay.token(), request(LlmFixtures.CLAUDE, "same", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("REPLAY_MISS");
        assertThat(STUB.received()).hasSize(providerCalls);
    }

    @Test
    void anExpiredRecordingIsGone() throws Exception {
        var source = recordedSource(RunMode.SANDBOX);
        jdbc.update("UPDATE llm_call SET recording_expires_at = now() - interval '1 day' WHERE run_id = ?", source.runId());
        var replay = replayer(source.runId());

        var response = chat(replay.token(), request(LlmFixtures.CLAUDE, "same", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody().get("code")).isEqualTo("REPLAY_EXPIRED");
        var row = llmCalls.findByRunIdOrderByCreatedAtAsc(replay.runId()).get(0);
        assertThat(row.getStatus()).isEqualTo(LlmCallStatus.ERROR);
        assertThat(row.getErrorCode()).isEqualTo("REPLAY_EXPIRED");
        assertThat(row.getReplayOrdinal()).isNull();
    }

    @Test
    void aPurgedRecordingIsGoneToo() {
        var source = recordedSource(RunMode.SANDBOX);
        jdbc.update("UPDATE llm_call SET recording_expires_at = now() - interval '1 day' WHERE run_id = ?", source.runId());
        retention.purgeOnce();
        var replay = replayer(source.runId());

        var response = chat(replay.token(), request(LlmFixtures.CLAUDE, "same", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody().get("code")).isEqualTo("REPLAY_EXPIRED");
    }

    @Test
    void concurrentDuplicatesNeverShareAnOrdinal() throws Exception {
        var source = recordedSource(RunMode.SANDBOX);   // two recordings of "same"
        var replay = replayer(source.runId());
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<Map>>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return chat(replay.token(), request(LlmFixtures.CLAUDE, "same", 100));
            }));
        }
        start.countDown();
        List<String> served = new ArrayList<>();
        int misses = 0;
        for (Future<ResponseEntity<Map>> result : results) {
            ResponseEntity<Map> response = result.get(60, TimeUnit.SECONDS);
            if (response.getStatusCode() == HttpStatus.OK) {
                served.add(textOf(response));
            } else if (response.getStatusCode() == HttpStatus.CONFLICT) {
                misses++;
            }
        }
        pool.shutdown();

        assertThat(served).containsExactlyInAnyOrder("first", "second");
        assertThat(misses).isEqualTo(2);
        var ordinals = llmCalls.findByRunIdOrderByCreatedAtAsc(replay.runId()).stream()
                .filter(c -> c.getStatus() == LlmCallStatus.OK).map(LlmCall::getReplayOrdinal).toList();
        assertThat(ordinals).containsExactlyInAnyOrder(1, 2);
    }
}
```

`backend/src/test/java/com/agentstore/platform/llm/GatewayRecordingTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.execution.RunLlmMode;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.llm.StubProviderServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRecordingTest extends AbstractLlmIntegrationTest {

    @Test
    void aSandboxCallIsRecordedForFourteenDays() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        fx.configureRun(agent.runId(), RunMode.SANDBOX, null, null, null);
        STUB.enqueue(Reply.anthropicText("recorded answer", 12, 8));

        chat(agent.token(), request(LlmFixtures.CLAUDE, "recorded question", 100));

        LlmCall call = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId()).get(0);
        String base = "llm/" + agent.runId() + "/" + call.getId() + "/";
        assertThat(call.getRequestKey()).isEqualTo(base + "request.json");
        assertThat(call.getResponseKey()).isEqualTo(base + "response.json");
        assertThat(call.getRecordingExpiresAt()).isBetween(Instant.now().plus(Duration.ofDays(14)).minusSeconds(120),
                Instant.now().plus(Duration.ofDays(14)).plusSeconds(120));
        assertThat(call.getRecordingPurgedAt()).isNull();

        String requestBlob = new String(storage.open(call.getRequestKey()).readAllBytes(), StandardCharsets.UTF_8);
        String responseBlob = new String(storage.open(call.getResponseKey()).readAllBytes(), StandardCharsets.UTF_8);
        JsonNode sent = json.readTree(requestBlob);
        assertThat(sent.get("model").asText()).isEqualTo("test-claude");
        assertThat(sent.at("/messages/0/content/0/text").asText()).isEqualTo("recorded question");
        JsonNode answered = json.readTree(responseBlob);
        assertThat(answered.at("/content/0/text").asText()).isEqualTo("recorded answer");
        assertThat(answered.at("/usage/inputTokens").asInt()).isEqualTo(12);
        assertThat(requestBlob + responseBlob).as("a key never lands in a recording").doesNotContain(PLATFORM_ANTHROPIC_KEY);
    }

    @Test
    void productionTrialAndReviewCallsNeverStorePrompts() throws Exception {
        for (RunMode mode : new RunMode[]{RunMode.PRODUCTION, RunMode.TRIAL, RunMode.REVIEW}) {
            var agent = fx.runningAgent("valid-llm-platform.json");
            fx.configureRun(agent.runId(), mode, null, null, null);
            STUB.enqueue(Reply.anthropicText("secret answer", 1, 1));

            chat(agent.token(), request(LlmFixtures.CLAUDE, "secret question", 100));

            LlmCall call = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId()).get(0);
            assertThat(call.getRequestKey()).as(mode.name()).isNull();
            assertThat(call.getResponseKey()).isNull();
            assertThat(call.getRecordingExpiresAt()).isNull();
            assertThat(storage.size("llm/" + agent.runId() + "/" + call.getId() + "/request.json")).isEmpty();
            assertThat(storage.size("llm/" + agent.runId() + "/" + call.getId() + "/response.json")).isEmpty();
        }
    }

    @Test
    void mockCallsAreNeverRecorded() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        fx.configureRun(agent.runId(), RunMode.SANDBOX, RunLlmMode.MOCK, null, null);

        chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        LlmCall call = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId()).get(0);
        assertThat(call.getSource()).isEqualTo(LlmSource.MOCK);
        assertThat(call.getRequestKey()).isNull();
        assertThat(call.getRecordingExpiresAt()).isNull();
    }

    @Test
    void aFailedSandboxCallLeavesNoRecording() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        fx.configureRun(agent.runId(), RunMode.SANDBOX, null, null, null);
        STUB.always(Reply.status(500, "{}"));

        chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        LlmCall call = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId()).get(0);
        assertThat(call.getStatus()).isEqualTo(LlmCallStatus.ERROR);
        assertThat(call.getRequestKey()).isNull();
    }
}
```

`backend/src/test/java/com/agentstore/platform/llm/LlmRecordingRetentionTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.llm.StubProviderServer.Reply;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LlmRecordingRetentionTest extends AbstractLlmIntegrationTest {

    @Autowired LlmRecordingRetentionService retention;

    LlmCall recordedCall() {
        var agent = fx.runningAgent("valid-llm-platform.json");
        fx.configureRun(agent.runId(), RunMode.SANDBOX, null, null, null);
        STUB.enqueue(Reply.anthropicText("answer", 1, 1));
        chat(agent.token(), request(LlmFixtures.CLAUDE, "question " + UUID.randomUUID(), 100));
        return llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId()).get(0);
    }

    void expire(LlmCall call) {
        jdbc.update("UPDATE llm_call SET recording_expires_at = now() - interval '1 hour' WHERE id = ?", call.getId());
    }

    @Test
    void anExpiredRecordingIsDeletedAndStampedAndAnUnexpiredOneSurvives() {
        LlmCall expired = recordedCall();
        LlmCall fresh = recordedCall();
        expire(expired);
        assertThat(storage.size(expired.getRequestKey())).isPresent();

        int purged = retention.purgeOnce();

        assertThat(purged).isGreaterThanOrEqualTo(1);
        LlmCall after = llmCalls.findById(expired.getId()).orElseThrow();
        assertThat(after.getRecordingPurgedAt()).isNotNull();
        assertThat(after.getRequestKey()).isNull();
        assertThat(after.getResponseKey()).isNull();
        assertThat(storage.size(expired.getRequestKey())).isEmpty();
        assertThat(storage.size(expired.getResponseKey())).isEmpty();
        assertThat(after.getStatus()).as("the call row itself stays").isEqualTo(LlmCallStatus.OK);

        LlmCall untouched = llmCalls.findById(fresh.getId()).orElseThrow();
        assertThat(untouched.getRecordingPurgedAt()).isNull();
        assertThat(storage.size(fresh.getRequestKey())).isPresent();
        assertThat(storage.size(fresh.getResponseKey())).isPresent();
    }

    @Test
    void purgingTwiceDoesNothingTheSecondTime() {
        LlmCall call = recordedCall();
        expire(call);
        retention.purgeOnce();
        Instant stamp = llmCalls.findById(call.getId()).orElseThrow().getRecordingPurgedAt();

        assertThat(retention.purgeOnce()).isZero();

        assertThat(llmCalls.findById(call.getId()).orElseThrow().getRecordingPurgedAt()).isEqualTo(stamp);
    }

    @Test
    void callsWithoutARecordingAreIgnored() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");   // PRODUCTION: nothing recorded
        STUB.enqueue(Reply.anthropicText("answer", 1, 1));
        chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        retention.purgeOnce(Instant.now().plus(Duration.ofDays(30)));

        LlmCall call = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId()).get(0);
        assertThat(call.getRecordingPurgedAt()).isNull();
    }

    @Test
    void theClockIsTheBoundary() {
        retention.purgeOnce();   // the database is shared: clear whatever earlier tests left expired
        LlmCall call = recordedCall();

        assertThat(retention.purgeOnce(Instant.now().plus(Duration.ofDays(13)))).isZero();
        assertThat(llmCalls.findById(call.getId()).orElseThrow().getRecordingPurgedAt()).isNull();

        assertThat(retention.purgeOnce(Instant.now().plus(Duration.ofDays(15)))).isGreaterThanOrEqualTo(1);
        assertThat(llmCalls.findById(call.getId()).orElseThrow().getRecordingPurgedAt()).isNotNull();
    }
}
```

- [ ] **Step 3: Run them to see them fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='MockResponderTest,LlmRecorderTest,GatewayMockTest,GatewayReplayTest,GatewayRecordingTest,LlmRecordingRetentionTest'`
Expected: FAIL to compile (`MockResponder`, `LlmRecorder`, `LlmRecordingRetentionService` do not exist).

- [ ] **Step 4: Add the replay and purge queries**

Replace the whole of `llm/LlmCallRepository.java` with:

```java
package com.agentstore.platform.llm;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LlmCallRepository extends JpaRepository<LlmCall, UUID> {

    List<LlmCall> findByRunIdOrderByCreatedAtAsc(UUID runId);

    /** The highest replay ordinal this run has already consumed for a request hash (0 when none). */
    @Query("select coalesce(max(c.replayOrdinal), 0) from LlmCall c where c.runId = :runId and c.requestHash = :hash")
    int maxReplayOrdinal(@Param("runId") UUID runId, @Param("hash") String hash);

    /**
     * The source run's recorded, successful calls for a hash in recorded order (created_at, id), skipping mock calls
     * and anything that was never recorded. Page {@code n-1} of size 1 is the n-th recorded call.
     */
    @Query("select c from LlmCall c where c.runId = :runId and c.requestHash = :hash and c.status = :status "
            + "and c.source <> :excluded and c.recordingExpiresAt is not null order by c.createdAt asc, c.id asc")
    List<LlmCall> findReplayable(@Param("runId") UUID runId, @Param("hash") String hash,
                                 @Param("status") LlmCallStatus status, @Param("excluded") LlmSource excluded,
                                 Pageable pageable);

    @Query("select c from LlmCall c where c.recordingExpiresAt < :now and c.recordingPurgedAt is null "
            + "order by c.recordingExpiresAt asc")
    List<LlmCall> findRecordingsDueForPurge(@Param("now") Instant now, Pageable pageable);
}
```

- [ ] **Step 5: Implement the recorder, mock and replay responders**

`llm/LlmRecorder.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.storage.ObjectStorage;
import com.agentstore.platform.storage.StorageException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;

/**
 * Request and response blobs of SANDBOX calls, in the object store under {@code llm/{runId}/{callId}/}. Callers decide
 * which calls are recorded; this class never sees a key (only normalized requests and responses) and never fails a call.
 */
@Component
public class LlmRecorder {

    private static final Logger log = LoggerFactory.getLogger(LlmRecorder.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ObjectStorage storage;
    private final ObjectMapper mapper;
    private final LlmProperties properties;

    public LlmRecorder(ObjectStorage storage, ObjectMapper mapper, LlmProperties properties) {
        this.storage = storage;
        this.mapper = mapper;
        this.properties = properties;
    }

    /** Writes both blobs and sets the call's keys and expiry; on any failure logs, cleans up and leaves the keys null. */
    public void attach(LlmCall call, LlmRequest request, LlmResponse response) {
        String base = "llm/" + call.getRunId() + "/" + call.getId() + "/";
        String requestKey = base + "request.json";
        String responseKey = base + "response.json";
        try {
            storage.put(requestKey, mapper.writeValueAsBytes(request.toMap()));
            storage.put(responseKey, mapper.writeValueAsBytes(response.toMap()));
        } catch (StorageException | JsonProcessingException e) {
            storage.deleteQuietly(requestKey);
            storage.deleteQuietly(responseKey);
            log.warn("Could not record LLM call {} of run {}", call.getId(), call.getRunId());
            return;
        }
        call.setRequestKey(requestKey);
        call.setResponseKey(responseKey);
        call.setRecordingExpiresAt(Instant.now().plus(properties.recordingTtl()));
    }

    public LlmResponse readResponse(String key, String fallbackModel) {
        try (InputStream in = storage.open(key)) {
            return LlmResponse.fromMap(mapper.readValue(in, MAP), fallbackModel);
        } catch (IOException | IllegalArgumentException e) {
            throw new StorageException("Could not read a recorded response", e);
        }
    }

    /** Removes a recording that was written for a row that will not be saved. */
    public void discard(LlmCall call) {
        delete(call.getRequestKey());
        delete(call.getResponseKey());
        call.setRequestKey(null);
        call.setResponseKey(null);
        call.setRecordingExpiresAt(null);
    }

    public void delete(String key) {
        if (key != null) {
            storage.deleteQuietly(key);
        }
    }
}
```

`llm/MockResponder.java`:

```java
package com.agentstore.platform.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Canned answers for MOCK runs: {@code {"responses": {"<hash>": {…}}, "default": {…}}}. Lookup is the exact request
 * hash, then {@code default}, then a fixed stub, so a mock run never fails for want of an entry. A malformed entry
 * counts as absent.
 */
@Component
public class MockResponder {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    public MockResponder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public LlmResponse respond(String mockJson, LlmRequest request, String hash) {
        Map<String, Object> root = parse(mockJson);
        if (root.get("responses") instanceof Map<?, ?> byHash) {
            LlmResponse exact = canned(byHash.get(hash), request.model());
            if (exact != null) {
                return exact;
            }
        }
        LlmResponse fallback = canned(root.get("default"), request.model());
        return fallback != null ? fallback : genericStub(request.model());
    }

    static LlmResponse genericStub(String model) {
        return new LlmResponse(model, List.of(Map.of("type", "text", "text", "[mock response]")), "end_turn", 0, 0);
    }

    @SuppressWarnings("unchecked")
    private static LlmResponse canned(Object entry, String model) {
        if (!(entry instanceof Map<?, ?> map)) {
            return null;
        }
        try {
            return LlmResponse.fromMap((Map<String, Object>) map, model);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
```

`llm/ReplayResponder.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.runtime.RunPrincipal;
import com.agentstore.platform.runtime.RuntimeApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Serves REPLAY runs from a source run's recordings. The n-th call with a given request hash gets the n-th recorded OK
 * call with that hash (ordered by created_at, id); the unique index on (run, hash, ordinal) makes a concurrent
 * duplicate retry with the next n. A miss never falls through to a provider.
 */
@Component
public class ReplayResponder {

    private static final int MAX_CLAIM_ATTEMPTS = 5;

    private final LlmCallRepository calls;
    private final LlmCallLog callLog;
    private final LlmRecorder recorder;

    public ReplayResponder(LlmCallRepository calls, LlmCallLog callLog, LlmRecorder recorder) {
        this.calls = calls;
        this.callLog = callLog;
        this.recorder = recorder;
    }

    public LlmResponse serve(RunPrincipal principal, UUID sourceRunId, LlmModel model,
                             LlmRequestNormalizer.Normalized normalized) {
        UUID runId = principal.runId();
        String hash = normalized.hash();
        long started = System.nanoTime();
        if (sourceRunId == null) {
            throw miss(principal, model, hash, started);
        }
        for (int attempt = 0; attempt < MAX_CLAIM_ATTEMPTS; attempt++) {
            int ordinal = calls.maxReplayOrdinal(runId, hash) + 1;
            List<LlmCall> recorded = calls.findReplayable(sourceRunId, hash, LlmCallStatus.OK, LlmSource.MOCK,
                    PageRequest.of(ordinal - 1, 1));
            if (recorded.isEmpty()) {
                throw miss(principal, model, hash, started);
            }
            LlmCall source = recorded.get(0);
            if (source.getRecordingPurgedAt() != null || source.getRecordingExpiresAt().isBefore(Instant.now())) {
                throw expired(principal, model, hash, started);
            }
            LlmResponse response = recorder.readResponse(source.getResponseKey(), model.getId());
            LlmCall call = new LlmCall(UUID.randomUUID(), runId, model.getId(), LlmSource.REPLAY, LlmCallStatus.OK, hash);
            call.setReplayOrdinal(ordinal);
            call.setInputTokens(source.getInputTokens());
            call.setOutputTokens(source.getOutputTokens());
            call.setLatencyMs(millisSince(started));
            if (principal.mode() == RunMode.SANDBOX) {
                recorder.attach(call, normalized.request(), response);
            }
            try {
                callLog.record(principal, call);
                return response;
            } catch (DataIntegrityViolationException e) {
                recorder.discard(call);   // another call took this ordinal first: try the next one
            }
        }
        throw new RuntimeApiException(HttpStatus.CONFLICT, "REPLAY_MISS",
                "Could not claim a recorded response for request " + hash + "; retry");
    }

    private RuntimeApiException miss(RunPrincipal principal, LlmModel model, String hash, long started) {
        LlmCall row = new LlmCall(UUID.randomUUID(), principal.runId(), model.getId(), LlmSource.REPLAY,
                LlmCallStatus.REPLAY_MISS, hash);
        row.setErrorCode("REPLAY_MISS");
        row.setLatencyMs(millisSince(started));
        callLog.record(principal, row);
        return new RuntimeApiException(HttpStatus.CONFLICT, "REPLAY_MISS", "No recorded response for request " + hash,
                List.of(Map.of("requestHash", hash)));
    }

    private RuntimeApiException expired(RunPrincipal principal, LlmModel model, String hash, long started) {
        LlmCall row = new LlmCall(UUID.randomUUID(), principal.runId(), model.getId(), LlmSource.REPLAY,
                LlmCallStatus.ERROR, hash);
        row.setErrorCode("REPLAY_EXPIRED");
        row.setLatencyMs(millisSince(started));
        callLog.record(principal, row);
        return new RuntimeApiException(HttpStatus.GONE, "REPLAY_EXPIRED",
                "The recording for request " + hash + " has expired");
    }

    private static int millisSince(long startedNanos) {
        return (int) ((System.nanoTime() - startedNanos) / 1_000_000);
    }
}
```

- [ ] **Step 6: Dispatch and record in the gateway**

In `llm/GatewayLlmBackend.java` make these edits.

Imports: replace `import com.agentstore.platform.manifest.LlmDecl;` with

```java
import com.agentstore.platform.execution.RunLlmMode;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.manifest.LlmDecl;
```

Fields and constructor: replace

```java
    private final LlmCallLog callLog;

    public GatewayLlmBackend(LlmRequestNormalizer normalizer, LlmModelRepository models, RunLlmContextLoader contexts,
                             LlmKeyResolver keys, LlmBudget budget, ProviderClient providers, LlmCallLog callLog) {
        this.normalizer = normalizer;
        this.models = models;
        this.contexts = contexts;
        this.keys = keys;
        this.budget = budget;
        this.providers = providers;
        this.callLog = callLog;
    }
```

with

```java
    private final LlmCallLog callLog;
    private final MockResponder mock;
    private final ReplayResponder replay;
    private final LlmRecorder recorder;

    public GatewayLlmBackend(LlmRequestNormalizer normalizer, LlmModelRepository models, RunLlmContextLoader contexts,
                             LlmKeyResolver keys, LlmBudget budget, ProviderClient providers, LlmCallLog callLog,
                             MockResponder mock, ReplayResponder replay, LlmRecorder recorder) {
        this.normalizer = normalizer;
        this.models = models;
        this.contexts = contexts;
        this.keys = keys;
        this.budget = budget;
        this.providers = providers;
        this.callLog = callLog;
        this.mock = mock;
        this.replay = replay;
        this.recorder = recorder;
    }
```

Dispatch: replace

```java
        return callProvider(principal, llm, context, model, normalized).toMap();
    }
```

with

```java
        if (context.llmMode() == RunLlmMode.MOCK) {
            return serveMock(principal, context, model, normalized).toMap();
        }
        if (context.llmMode() == RunLlmMode.REPLAY) {
            return replay.serve(principal, context.replayRunId(), model, normalized).toMap();
        }
        return callProvider(principal, llm, context, model, normalized).toMap();
    }

    /** No key, no budget, no provider: a canned answer, recorded as a MOCK call with its canned token counts. */
    private LlmResponse serveMock(RunPrincipal principal, RunLlmContext context, LlmModel model,
                                  LlmRequestNormalizer.Normalized normalized) {
        long started = System.nanoTime();
        LlmResponse response = mock.respond(context.llmMockJson(), normalized.request(), normalized.hash());
        LlmCall call = new LlmCall(UUID.randomUUID(), principal.runId(), model.getId(), LlmSource.MOCK,
                LlmCallStatus.OK, normalized.hash());
        call.setInputTokens(response.inputTokens());
        call.setOutputTokens(response.outputTokens());
        call.setLatencyMs(millisSince(started));
        callLog.record(principal, call);
        return response;
    }
```

Recording (the `setCost` line makes the match unique; `serveMock` above ends with similar lines): replace

```java
        call.setCost(LlmCost.of(source, model, response.inputTokens(), response.outputTokens()));
        call.setLatencyMs(millisSince(started));
        callLog.record(principal, call);
```

with

```java
        call.setCost(LlmCost.of(source, model, response.inputTokens(), response.outputTokens()));
        call.setLatencyMs(millisSince(started));
        if (principal.mode() == RunMode.SANDBOX) {
            recorder.attach(call, request, response);   // production, trial and review runs never store prompts
        }
        callLog.record(principal, call);
```

- [ ] **Step 7: Implement the retention job**

`llm/LlmRecordingRetentionService.java`:

```java
package com.agentstore.platform.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Deletes the blobs of recordings whose 14 days are up and stamps {@code recording_purged_at}. The call row stays
 * (its tokens and cost are still the run's history); replay treats a purged recording as expired.
 */
@Service
public class LlmRecordingRetentionService {

    private static final Logger log = LoggerFactory.getLogger(LlmRecordingRetentionService.class);
    private static final int BATCH = 500;

    private final LlmCallRepository calls;
    private final LlmRecorder recorder;

    public LlmRecordingRetentionService(LlmCallRepository calls, LlmRecorder recorder) {
        this.calls = calls;
        this.recorder = recorder;
    }

    public int purgeOnce() {
        return purgeOnce(Instant.now());
    }

    /** Returns how many recordings were purged; the next run takes the next batch if more than 500 were due. */
    public int purgeOnce(Instant now) {
        int purged = 0;
        for (LlmCall call : calls.findRecordingsDueForPurge(now, PageRequest.of(0, BATCH))) {
            try {
                recorder.delete(call.getRequestKey());
                recorder.delete(call.getResponseKey());
                call.setRequestKey(null);
                call.setResponseKey(null);
                call.setRecordingPurgedAt(Instant.now());
                calls.save(call);
                purged++;
            } catch (RuntimeException e) {
                log.warn("Could not purge the recording of LLM call {}", call.getId());   // the next run retries it
            }
        }
        return purged;
    }
}
```

`llm/LlmRecordingRetentionJob.java`:

```java
package com.agentstore.platform.llm;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class LlmRecordingRetentionJob {

    private final LlmRecordingRetentionService retention;

    public LlmRecordingRetentionJob(LlmRecordingRetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${agentstore.llm.retention-interval:PT1H}",
            initialDelayString = "${agentstore.llm.retention-interval:PT1H}")
    @SchedulerLock(name = "llmRecordingRetention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        retention.purgeOnce();
    }
}
```

- [ ] **Step 8: Run the Task 8 tests and the whole `llm` package**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='com.agentstore.platform.llm.*Test'`
Expected: PASS (every test in the `llm` test package, Tasks 2–8). If `GatewayReplayTest.concurrentDuplicatesNeverShareAnOrdinal` shows a served text twice, the unique index or the retry loop is wrong: fix the code, not the test.

- [ ] **Step 9: Commit**

```bash
git add backend
git commit -m "feat(llm): mock and replay runs, SANDBOX recordings, and the recording retention job"
```

---

### Task 9: `/context` LLM limits, contract regeneration

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/runtime/ContextResponse.java`, `RuntimeContextController.java`; `backend/src/test/java/com/agentstore/platform/runtime/RuntimeContractTest.java`; `contracts/runtime-v1.yaml` (regenerated)
- Test: `backend/src/test/java/com/agentstore/platform/llm/RuntimeContextLlmLimitsTest.java`

**Interfaces:**
- Consumes: `Run.getLlmTokensUsed()/getLlmTokensReserved()` (Task 2), `LlmDecl` (Task 1), `AbstractLlmIntegrationTest`, `LlmFixtures.setTokens`.
- Produces: `GET /runtime/v1/context` returns `limits.llm = {mode, models, tokensRemaining}` when the manifest declares `llm` (`tokensRemaining = max(0, maxTokensPerRun − used − reserved)`; `mode` is the wire value, e.g. `"platform"`), and omits `limits.llm` otherwise. `ContextResponse.Limits(Instant deadlineAt, BigDecimal costCapRemaining, String currency, Llm llm)` and `ContextResponse.Llm(String mode, List<String> models, long tokensRemaining)`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/agentstore/platform/llm/RuntimeContextLlmLimitsTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.llm.StubProviderServer.Reply;
import com.agentstore.platform.runtime.RuntimeFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeContextLlmLimitsTest extends AbstractLlmIntegrationTest {

    @SuppressWarnings("rawtypes")
    Map<?, ?> limits(String token) {
        ResponseEntity<Map> response = rest.exchange("http://localhost:" + port + "/runtime/v1/context", HttpMethod.GET,
                new HttpEntity<>(RuntimeFixtures.bearer(token)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (Map<?, ?>) response.getBody().get("limits");
    }

    @Test
    void aPlatformAgentSeesItsModelsAndRemainingBudgetShrinkAsItSpends() {
        var agent = fx.runningAgent("valid-llm-platform.json");

        Map<?, ?> before = (Map<?, ?>) limits(agent.token()).get("llm");
        assertThat(before.get("mode")).isEqualTo("platform");
        assertThat((List<?>) before.get("models")).containsExactly("test-claude", "test-gpt", "test-disabled");
        assertThat(before.get("tokensRemaining")).isEqualTo(100_000);

        STUB.enqueue(Reply.anthropicText("hello", 100, 40));
        assertThat(chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 500)).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(((Map<?, ?>) limits(agent.token()).get("llm")).get("tokensRemaining")).isEqualTo(99_860);
    }

    @Test
    void inFlightReservationsAreAlreadyTakenOff() {
        var agent = fx.runningAgent("valid-llm-platform.json");
        fx.setTokens(agent.runId(), 1000, 500);

        assertThat(((Map<?, ?>) limits(agent.token()).get("llm")).get("tokensRemaining")).isEqualTo(98_500);
    }

    @Test
    void theRemainingBudgetNeverGoesNegativeEvenAfterAnOvershoot() {
        var agent = fx.runningAgent("valid-llm-platform.json");
        fx.setTokens(agent.runId(), 100_400, 0);   // actual usage may exceed the estimate by one call

        assertThat(((Map<?, ?>) limits(agent.token()).get("llm")).get("tokensRemaining")).isEqualTo(0);
    }

    @Test
    void aByokDirectAgentSeesItsModeAndTheFullBudget() {
        var agent = fx.runningAgent("valid-llm-byok-direct.json");

        Map<?, ?> llm = (Map<?, ?>) limits(agent.token()).get("llm");

        assertThat(llm.get("mode")).isEqualTo("byok_direct");
        assertThat(llm.get("tokensRemaining")).isEqualTo(100_000);
    }

    @Test
    void anAgentWithoutAnLlmBlockHasNoLlmLimits() {
        var agent = fx.runningAgent("valid-runtime-agent.json");

        Map<?, ?> limits = limits(agent.token());

        assertThat(limits.containsKey("llm")).isFalse();
        assertThat(limits.get("currency")).isEqualTo("INR");
    }
}
```

In `backend/src/test/java/com/agentstore/platform/runtime/RuntimeContractTest.java`, add this test before the closing brace:

```java

    @Test
    void theContractDescribesTheLlmLimitsInTheContext() throws Exception {
        JsonNode generated = new ObjectMapper().readTree(rest.getForObject("http://localhost:" + port + "/v3/api-docs", String.class));

        assertThat(generated.at("/components/schemas/Limits/properties").has("llm")).isTrue();
        assertThat(generated.at("/components/schemas/Llm/properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("mode", "models", "tokensRemaining");
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='RuntimeContextLlmLimitsTest,RuntimeContractTest'`
Expected: FAIL (`limits.llm` is absent; the committed contract does not match once the code changes, and the new contract test fails now).

- [ ] **Step 3: Implement**

Replace the whole of `runtime/ContextResponse.java` with:

```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.manifest.ScopeType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ContextResponse(UUID runId, RunMode mode, AgentVersionInfo agentVersion, Map<String, Object> input,
                              List<GrantedScope> grantedScopes, Limits limits) {

    public record AgentVersionInfo(UUID id, String versionNumber) {}

    public record GrantedScope(ScopeType type, String resource, boolean requiresApproval) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Limits(Instant deadlineAt, BigDecimal costCapRemaining, String currency, Llm llm) {}

    /** Present when the manifest declares an llm block: how the agent reaches an LLM and how much token budget is left. */
    public record Llm(String mode, List<String> models, long tokensRemaining) {}
}
```

In `runtime/RuntimeContextController.java`, add `import com.agentstore.platform.manifest.LlmDecl;` and replace

```java
        return new ContextResponse(run.getId(), run.getMode(),
                new ContextResponse.AgentVersionInfo(run.getAgentVersion().getId(), run.getAgentVersion().getVersionNumber()),
                input, scopes,
                new ContextResponse.Limits(run.getDeadlineAt(), remaining, principal.manifest().costEnvelope().currency()));
```

with

```java
        ContextResponse.Llm llm = null;
        LlmDecl declared = principal.manifest().llm();
        if (declared != null) {
            long tokensRemaining = Math.max(0,
                    declared.maxTokensPerRun() - run.getLlmTokensUsed() - run.getLlmTokensReserved());
            llm = new ContextResponse.Llm(declared.mode().wire(), declared.models(), tokensRemaining);
        }
        return new ContextResponse(run.getId(), run.getMode(),
                new ContextResponse.AgentVersionInfo(run.getAgentVersion().getId(), run.getAgentVersion().getVersionNumber()),
                input, scopes,
                new ContextResponse.Limits(run.getDeadlineAt(), remaining, principal.manifest().costEnvelope().currency(), llm));
```

- [ ] **Step 4: Run the context tests, then regenerate the contract**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=RuntimeContextLlmLimitsTest`
Expected: PASS.

Regenerate the contract (never hand-edit it):

```bash
cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=RuntimeContractTest -Dcontract.update=true
```

Then look at what changed:

```bash
git diff --stat -- ../contracts
git diff -- ../contracts
```

Expected: only an `llm` property on `Limits` and a new `Llm` schema (`mode`, `models`, `tokensRemaining`). Any other change (for example to `/runtime/v1/llm/chat`) means an unintended controller change: find it before continuing.

- [ ] **Step 5: Run the runtime suite and the contract test without the update flag**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='com.agentstore.platform.runtime.*Test,RuntimeContextLlmLimitsTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend contracts
git commit -m "feat(llm): LLM limits in /runtime/v1/context and the regenerated runtime contract"
```

---

### Task 10: Docs and ontology, the acceptance test, full-suite run

**Files:**
- Modify: `ONTOLOGY.md`
- Test: `backend/src/test/java/com/agentstore/platform/llm/LlmGatewayAcceptanceTest.java`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing new; this task proves the whole flow and records it in the ontology.

- [ ] **Step 1: Write the acceptance test**

`backend/src/test/java/com/agentstore/platform/llm/LlmGatewayAcceptanceTest.java`:

```java
package com.agentstore.platform.llm;

import com.agentstore.platform.execution.RunLlmMode;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.llm.StubProviderServer.Reply;
import com.agentstore.platform.manifest.LlmVendor;
import com.agentstore.platform.runtime.RuntimeFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The whole gateway, end to end, through the public runtime API. */
class LlmGatewayAcceptanceTest extends AbstractLlmIntegrationTest {

    @Autowired ByokKeyService keys;
    @Autowired LlmRecordingRetentionService retention;

    @SuppressWarnings("rawtypes")
    static String textOf(ResponseEntity<Map> response) {
        return (String) ((Map<?, ?>) ((List<?>) response.getBody().get("content")).get(0)).get("text");
    }

    @SuppressWarnings("rawtypes")
    Map<?, ?> context(String token) {
        ResponseEntity<Map> response = rest.exchange("http://localhost:" + port + "/runtime/v1/context", HttpMethod.GET,
                new HttpEntity<>(RuntimeFixtures.bearer(token)), Map.class);
        return (Map<?, ?>) ((Map<?, ?>) response.getBody().get("limits")).get("llm");
    }

    @Test
    void theWholeGatewayFlow() throws Exception {
        // 1. A platform run: forwarded with the platform key, metered, costed, traced, visible in /context.
        var platform = fx.runningAgent("valid-llm-platform.json");
        STUB.enqueue(Reply.anthropicText("hello", 100, 40));
        assertThat(chat(platform.token(), request(LlmFixtures.CLAUDE, "hi", 500)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(STUB.received().get(0).header("x-api-key")).isEqualTo(PLATFORM_ANTHROPIC_KEY);
        assertThat(fx.tokens(platform.runId())).containsExactly(140, 0);
        assertThat(llmCalls.findByRunIdOrderByCreatedAtAsc(platform.runId()).get(0).getCost()).isEqualByComparingTo("0.0009");
        assertThat(llmSteps(platform.runId())).hasSize(1);
        assertThat(context(platform.token()).get("tokensRemaining")).isEqualTo(99_860);

        // 2. A verified developer brings their own key (byok_proxy): the provider sees it, and it costs the platform nothing.
        var proxy = fx.runningAgent("valid-llm-byok-proxy.json");
        fx.verifyDeveloperOrg(proxy.developerOrg());
        keys.put(proxy.developerOrg().getId(), LlmVendor.ANTHROPIC, "sk-dev-acceptance-0001");
        STUB.enqueue(Reply.anthropicText("proxied", 10, 5));
        assertThat(chat(proxy.token(), request(LlmFixtures.CLAUDE, "hi", 500)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(STUB.received().get(1).header("x-api-key")).isEqualTo("sk-dev-acceptance-0001");
        var proxied = llmCalls.findByRunIdOrderByCreatedAtAsc(proxy.runId()).get(0);
        assertThat(proxied.getSource()).isEqualTo(LlmSource.BYOK_PROXY);
        assertThat(proxied.getCost()).isEqualByComparingTo("0");

        // 3. A sandbox run records its real call; a replay run serves it back free; a mock run needs no provider at all.
        var sandbox = fx.runningAgent("valid-llm-platform.json");
        fx.configureRun(sandbox.runId(), RunMode.SANDBOX, null, null, null);
        STUB.enqueue(Reply.anthropicText("recorded", 20, 10));
        assertThat(chat(sandbox.token(), request(LlmFixtures.CLAUDE, "recorded question", 100)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(llmCalls.findByRunIdOrderByCreatedAtAsc(sandbox.runId()).get(0).getRequestKey()).isNotNull();
        int providerCalls = STUB.received().size();

        var replay = fx.runningAgent("valid-llm-platform.json");
        fx.configureRun(replay.runId(), RunMode.SANDBOX, RunLlmMode.REPLAY, sandbox.runId(), null);
        assertThat(textOf(chat(replay.token(), request(LlmFixtures.CLAUDE, "recorded question", 100)))).isEqualTo("recorded");
        var miss = chat(replay.token(), request(LlmFixtures.CLAUDE, "recorded question", 100));
        assertThat(miss.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(miss.getBody().get("code")).isEqualTo("REPLAY_MISS");

        var mock = fx.runningAgent("valid-llm-platform.json");
        fx.configureRun(mock.runId(), RunMode.SANDBOX, RunLlmMode.MOCK, null, null);
        assertThat(textOf(chat(mock.token(), request(LlmFixtures.CLAUDE, "anything", 100)))).isEqualTo("[mock response]");
        assertThat(STUB.received()).as("neither replay nor mock reached a provider").hasSize(providerCalls);
        assertThat(fx.tokens(replay.runId())).containsExactly(0, 0);
        assertThat(fx.tokens(mock.runId())).containsExactly(0, 0);

        // 4. After the retention window the recording is purged and replaying it is gone.
        jdbc.update("UPDATE llm_call SET recording_expires_at = now() - interval '1 hour' WHERE run_id = ?", sandbox.runId());
        retention.purgeOnce();
        var late = fx.runningAgent("valid-llm-platform.json");
        fx.configureRun(late.runId(), RunMode.SANDBOX, RunLlmMode.REPLAY, sandbox.runId(), null);
        var gone = chat(late.token(), request(LlmFixtures.CLAUDE, "recorded question", 100));
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(gone.getBody().get("code")).isEqualTo("REPLAY_EXPIRED");

        // 5. A production run can never be mocked or replayed.
        assertThatThrownBy(() -> fx.configureRun(platform.runId(), RunMode.PRODUCTION, RunLlmMode.MOCK, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 6. The token budget holds: a second big call on a small budget is refused and never reaches the provider.
        var small = fx.runningAgent("valid-llm-small-budget.json");
        STUB.enqueue(Reply.anthropicText("ok", 30, 20));
        assertThat(chat(small.token(), request(LlmFixtures.CLAUDE, "hi", 950)).getStatusCode()).isEqualTo(HttpStatus.OK);
        int beforeRejection = STUB.received().size();
        var refused = chat(small.token(), request(LlmFixtures.CLAUDE, "hi", 950));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(refused.getBody().get("code")).isEqualTo("BUDGET_EXCEEDED");
        assertThat(STUB.received()).hasSize(beforeRejection);
        assertThat(fx.tokens(small.runId())).containsExactly(50, 0);
    }
}
```

- [ ] **Step 2: Run it**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=LlmGatewayAcceptanceTest`
Expected: PASS. (A failure here is a real integration bug between earlier tasks: fix it at its cause.)

- [ ] **Step 3: Document in `ONTOLOGY.md`**

Read `ONTOLOGY.md` sections 2 (Core Classes: "Execution", "Data plane"), 3 (Properties), 4 (Axioms) and 5 (API mappings: "Developer API keys", "Agent Runtime API v1") first, then add, matching each section's existing style:

1. **Header.** Append the new backend source files to the single `ontology-tracked-files:` line and drop the deleted stub (leave `ontology-source-hash` and `ontology-generated-at` alone; they belong to the generator):

```bash
NEW=$(git diff --name-only --diff-filter=A master...HEAD -- backend/src/main/java | paste -sd, -)
sed -i '' "s|^\(ontology-tracked-files:.*\)\$|\1,${NEW}|" ONTOLOGY.md
sed -i '' 's|,backend/src/main/java/com/agentstore/platform/runtime/UnavailableLlmBackend.java||' ONTOLOGY.md
```

2. **Section 2, new subsection "LLM gateway"** (put it right after "Data plane"): `LlmModel` (an operator-managed model row: `provider` `[ANTHROPIC | OPENAI]` (`LlmVendor`), per-million-token prices, `currency`, `enabled`, `providerRetainsData`; the migration seeds `claude-sonnet-5` and `claude-haiku-4-5-20251001` disabled with zero prices; table `llm_model`, migration `V29__llm_gateway.sql`); `LlmCall` (one metered call: `source` `[PLATFORM | BYOK_PROXY | MOCK | REPLAY]`, `status` `[OK | ERROR | BUDGET_REJECTED | REPLAY_MISS]`, tokens, `cost`, `requestHash`, `replayOrdinal`, recording keys and expiry, our own `errorCode`, never a prompt, key or provider body); `ByokCredential` (a developer org's encrypted key per provider); `LlmDecl`/`LlmMode` `[platform | byok_proxy | byok_direct]` (the manifest's optional `llm` block); `Run` LLM fields (`llmTokensUsed`, `llmTokensReserved`, read-only in JPA and changed only by atomic statements; `llmMode` `[MOCK | REPLAY]` (`RunLlmMode`), `replayRunId`, `llmMockJson`; migration `V30__run_llm_columns.sql`, which also adds `org.developer_verified_at`); `GatewayLlmBackend` (the `LlmBackend` behind `/runtime/v1/llm/chat`, replacing the 501 stub); `LlmRequestNormalizer`, `LlmBudget`, `ProviderClient` with `AnthropicProvider`/`OpenAiProvider`, `ByokKeyService`, `LlmRecorder`, `MockResponder`, `ReplayResponder`, `LlmRecordingRetentionService`/`LlmRecordingRetentionJob`.
3. **Section 3:** Manifest —declares→ LlmDecl; LlmCall —belongs to→ Run, —uses→ LlmModel; ByokCredential —owned by→ developer Org; Run —replays→ Run (`replayRunId`); Org —verified by→ WS7 (`developerVerifiedAt`, nullable).
4. **Section 4 axioms:** BYOK of either flavour is Tier B and needs a verified developer org (checked at submit and at key upload); model availability is checked per call, never at submit; every call reserves estimated input + `maxTokens` in one atomic conditional update and settles afterwards, so concurrent calls cannot oversubscribe `maxTokensPerRun` (the cap can be overshot by one call's estimation error); a failed call releases its reservation; LLM cost is recorded on the call (PLATFORM only) and is not deducted from the buyer's cost cap; provider bodies and keys never leave the gateway; mock and replay exist only for non-production runs (DB check) and never reserve budget; a replay miss never falls through to a paid call; only SANDBOX runs record prompts, for 14 days; a `byok_direct` run gets `AGENT_SECRET_<PROVIDER>_API_KEY` and `POST /llm/chat` is refused for it.
5. **Section 5:** in "Agent Runtime API v1" replace the line `* \`POST /runtime/v1/llm/chat\`: delegated to \`LlmBackend\` (501 until WS3).` with the implemented behaviour and its error codes (`LLM_NOT_DECLARED`, `MODEL_NOT_ALLOWED`, `INVALID_REQUEST`, `USE_DIRECT_PROVIDER`, `LLM_KEY_MISSING`, `BUDGET_EXCEEDED` 402, `REPLAY_MISS` 409, `REPLAY_EXPIRED` 410, `LLM_PROVIDER_ERROR` 502, `LLM_PROVIDER_UNAVAILABLE` 503, `LLM_PROVIDER_TIMEOUT` 504); extend the `GET /runtime/v1/context` line with `limits.llm`; add an "LLM keys" block after "Developer API keys": `PUT /api/v1/developer/llm-keys/{provider}`, `GET /api/v1/developer/llm-keys`, `DELETE /api/v1/developer/llm-keys/{provider}` (developer JWT only, `403 BYOK_NOT_VERIFIED` for unverified orgs). Also search for `Tier` and `Manifest` and add BYOK to the Tier B rule and `llm` to the manifest description.
6. **"Inconsistencies & Unclear Areas":** `org.developer_verified_at` is never set by any code path until WS7 (tests set it directly); LLM cost is recorded but not deducted from the buyer's cost cap or the developer's earnings (WS10); the request hash omits `system` (spec §6) while the token estimate includes it; and extend the existing "Manifest wire vs parsed shape" bullet: `modelRequirements` is ignored and superseded by `llm`.

- [ ] **Step 4: Full suites**

Run the whole backend suite, then the CLI:

```bash
cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test
cd ../cli && npx vitest run
```

Expected: all green. Fix any failure at its cause; do not edit an existing test to make it pass unless it asserted the old 501 behaviour (already handled in Task 6). Record the test counts (Tests run / Failures / Errors) for the final report. Then confirm nothing carries attribution:

```bash
git log --format=%B master..HEAD | grep -i -E "claude|anthropic|co-authored" || echo "no attribution"
```

Expected: `no attribution` (the word "Anthropic" legitimately appears in code and test files, not in commit messages).

- [ ] **Step 5: Update memory and commit**

Overwrite `/Users/amittiwari/.claude/projects/-Users-amittiwari-AgentStore/memory/agentstore-ws3-design-state.md` (create it; frontmatter `name`, `description`, `metadata.type: project`) with the WS3 implementation state: branch `feat/ws3-llm-gateway`, tasks done, suite result, the deviations listed at the top of this plan, and follow-ups (WS7 sets `developer_verified_at`; WS6 fills `llm_mode`/`replay_run_id`/`llm_mock_json`, the sandbox quota and fake secrets; WS10 earnings deduction; the UI (LLM keys page, run usage table); an operator must set real prices and enable models before any platform call works; hash-`system` decision if the user chose to change it). Add a one-line pointer to it in `MEMORY.md` and update the workstream-status line to say WS3 is implemented.

```bash
git add ONTOLOGY.md backend
git commit -m "docs: LLM gateway ontology; test: WS3 acceptance"
```

- [ ] **Step 6: Hand off**

Use superpowers:finishing-a-development-branch to decide how to integrate (PR, merge or keep the branch); nothing is pushed without the user's say-so.

---

## Self-review

**Spec coverage** — §1 scope: Tasks 1–9. §2 manifest (block, schema rules, `maxTokensPerRun` limit, byok_direct host, BYOK → Tier B, submit-time verification, no submit-time model check, CLI resync): Tasks 1 and 5. §3 data model (`llm_model` seed, `llm_call` columns, indexes and the ordinal unique index, `byok_credential`, `org.developer_verified_at`, `run` columns and the production check): Task 2. §4.1 units: normalizer and hash Task 3, budget Task 3, adapters and `ProviderClient` Task 4, key resolver Task 6, recorder/mock/replay Task 8. §4.2 request and response shape, `maxTokens` required, unknown keys rejected, `store:false`: Tasks 3–4. §4.3 flow (declared, direct, invalid, model check, dispatch, key, atomic reservation, provider, settlement, row + blobs + step), cost, context limits: Tasks 6, 8, 9. §4.4 resilience (timeouts, one retry, `Retry-After` cap, breaker, no body forwarding): Tasks 4 and 6. §4.5 keys never leak: Tasks 4, 5, 6 (`GatewayKeyLeakTest`), 8 (`LlmRecorderTest`, blobs). §5 BYOK (verified only, keys API, `byok_proxy`, `byok_direct` injection and 400): Tasks 5, 6, 7. §6 mock/replay/recording/retention: Tasks 2 (constraint), 8. §7 error contract: every code has a test (`LLM_NOT_DECLARED`, `MODEL_NOT_ALLOWED`, `INVALID_REQUEST`, `USE_DIRECT_PROVIDER` in Tasks 3/6; `LLM_KEY_MISSING` Tasks 6/7; `BYOK_NOT_VERIFIED` Task 5; `BUDGET_EXCEEDED` Task 6; `REPLAY_MISS`/`REPLAY_EXPIRED` Task 8; 502/503 Task 6; 504 Tasks 4/6 at unit level). §8 tests: each task, the acceptance test in Task 10, the 501 test updated and `UnavailableLlmBackend` deleted in Task 6, contract regenerated in Task 9, ONTOLOGY and CLI copy in Tasks 10 and 1. §10 task order: reordered, see Deviation 2. §11 settled decisions: none re-opened.

**Placeholder scan** — no TBD/TODO; every code step has code. Three spots depend on library behaviour I could not run here and each states the contract the test must keep: the resilience4j version in Task 4 Step 1 (fall back to the newest 2.x), the lowercase `authorization` header name in Task 4 Step 8 (fall back to `Authorization`), and the exact text of the `RunLauncher` edit anchors in Task 7 (they were copied from the file as it is on master at 14ce900).

**Type consistency** — `LlmCall(UUID, UUID, String, LlmSource, LlmCallStatus, String)` (Task 2) is what Tasks 6 and 8 construct; `LlmCallRepository` is replaced whole in Task 8 and keeps `findByRunIdOrderByCreatedAtAsc` from Task 2; `LlmRequest`/`LlmResponse` (Task 3) are the types of `LlmProvider.chat` (Task 4), `ProviderClient.chat` (Task 4), `GatewayLlmBackend` (Task 6) and `LlmRecorder`/`ReplayResponder` (Task 8); `LlmRequestNormalizer.Normalized(request, hash, estimatedInputTokens)` is read the same way in Tasks 6 and 8; `ByokKeyService.reveal(UUID, LlmVendor) → Optional<String>` (Task 5) backs `LlmKeyResolver` (Task 6) and `RunLauncher` (Task 7); `RuntimeApiException` 3- and 4-argument constructors are the existing public ones; `LlmFixtures.configureRun(UUID, RunMode, RunLlmMode, UUID, String)` (Task 2) is called identically in Tasks 8–10; `LlmKeyResolverTest.props(...)` (Task 6) is reused package-privately by `LlmRecorderTest` (Task 8); `StubProviderServer` (Task 4) and `AbstractLlmIntegrationTest` (Task 6) are used unchanged afterwards; `ProviderClient.breaker(LlmVendor)` and `resetBreakers()` (Task 4) are what Task 6's tests call.

**Review Focus** — items 1–5 map to Tasks 3/6, 4/5/6, 3, 2/8, 8 respectively.
