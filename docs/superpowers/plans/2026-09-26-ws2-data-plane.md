# WS2 — Data Plane Implementation Plan


**Goal:** A buyer uploads CSV/JSON/PDF files once, binds them to the resources an agent declares, and the agent reads them through `/runtime/v1/resources/**`; every agent write lands in an output store, staged until an approved gate commits it, and the buyer downloads what was committed.

**Architecture:** A new `data` package owns datasets, bindings, run pins, outputs and the retention job. Uploads are normalized to JSONL at ingest (CSV/JSON) so the runtime reads pages with a byte-offset cursor over an S3 range read. `BindingResolver` runs at trigger time and writes immutable `run_dataset_pin` rows; `DatasetResourceBackend` (replacing the 501 stub) reads only pins. Writes go to `outputs/{runId}/…jsonl` with a `run_output` row; `GateService` and `RunLifecycleService` publish Spring events that `OutputCommitService` turns into COMMITTED/DISCARDED.

**Tech Stack:** Java 21, Spring Boot 3.3.4, JPA/Hibernate (`ddl-auto: validate`), Flyway (V27, V28), Postgres 16, AWS SDK v2 S3 client against SeaweedFS, Apache Commons CSV 1.11.0 (new, free OSS), Jackson, ShedLock 5.16.0, springdoc 2.6.0, JUnit 5 + Testcontainers, Node/vitest (CLI schema resync).

**Spec:** `docs/superpowers/specs/2026-09-26-ws2-data-plane-design.md` (approved 2026-09-26). Where it and the WS2 section of `2026-09-23-agent-store-developer-platform-and-sandbox-design.md` differ, the WS2 spec wins.

## Global Constraints

- Work on branch `feat/ws2-data-plane`, cut from `docs/ws2-design` (so the spec and this plan ride along). Nothing is pushed until the user says so.
- Backend tests: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test [-Dtest=ClassName]`. Docker runs through Colima (`colima start` if `docker ps` fails). CLI: `cd cli && npm ci` if `node_modules` is missing, then `npx vitest run`.
- Last existing migration is `V26__gate_request.sql`; new ones are **V27** and **V28**. `spring.jpa.hibernate.ddl-auto` is `validate`, so every migration must match the entities exactly. `open-in-view` is `false` (no lazy loading outside a transaction).
- Formats are exactly `csv`, `json`, `pdf` (no XLSX). Manifest `resources[]`: `format` ∈ `csv|json|pdf`; `schema` optional and forbidden for `pdf`; `schema.properties.<col>.type` ∈ `string|number|integer|boolean|date` (default `string`); resource names are unique; every scope `resource` names a declared resource **only when `resources` is present**; a `pdf` resource may only be READ. A manifest without `resources` stays valid and its resources are untyped.
- Caps: 50 MB per uploaded file (413), org storage quota 5 GB default (413 `QUOTA_EXCEEDED`), agent writes 5 MB per call and 200 MB per run (413 `WRITE_TOO_LARGE`), check/validation reports at most the first 50 row errors.
- Reads only ever use the run's `run_dataset_pin` rows. A `BUYER_DATA` dataset is resolved only for a `PRODUCTION` run; `SANDBOX`/`REVIEW`/`TRIAL` runs may only ever resolve `FIXTURE`, `SAMPLE` or `EVAL` data. The backend re-checks this on every read.
- Files stream through the backend (never presigned URLs) so agents behind an egress allowlist do not need the object-store host.
- Explicit dataset delete purges within about an hour (`purge_after = now`); only *unplugging* a binding gets the 30-day grace. Purge tombstones the dataset row (blobs deleted, keys nulled, `purged_at` set); the row is never deleted.
- Errors on buyer endpoints use `{code, message}` (plus extra fields such as `checkResult`) via the new `DataApiErrors` advice, because the app hides `ResponseStatusException` reasons. Runtime endpoints keep problem+json (`RuntimeApiException`).
- **The existing tests must keep passing.** Only `RuntimeResourceEndpointsTest` (two tests that assert the old 501) is edited, in Task 7; never edit any other existing test to make it pass. `contracts/runtime-v1.yaml` is regenerated, never hand-edited.
- Out of scope (do not build): buyer UI (FE-2), XLSX, SSE-KMS (WS12), creating FIXTURE/SAMPLE/EVAL datasets and sandbox/eval/trial bindings (WS6/8/9), pending-access requests (WS11), real connector write-back, approval-card preview columns (WS1b), SDK helpers (WS4), retention of COMMITTED outputs, wiring bindings into `POST /connections` (WS15/FE-2).

## Deviations from the spec (small, called out so the user can veto)

1. **Two extra columns:** `dataset.records_size_bytes` (quota counts "originals plus JSONL copies" and the cursor needs the JSONL length) and `run_output.size_bytes` (the 200 MB per-run cap). `run_output.storage_key` is nullable (set null when the blob is purged).
2. **`GateOutcomeEvent` carries `requestedAt`** (the gate's `created_at`) so the timing rule can be applied.
3. **Two publish sites, not three.** `GateService.expire` ends the run through `RunLifecycleService.terminate → finish`, and `finish` already closes the open gate. So the event is published from `GateService.decide` (approve/reject) and `RunLifecycleService.finish` (expiry and run-end); the expiry branch that follows in `expire` never finds a PENDING gate. A second event, `RunFinishedEvent`, drives the run-end discard.
4. **No connection-revoke path exists** in the code today (`ConnectionStatus.REVOKED` is never set). `BindingService.unbindAll(connectionId)` is built and tested; it is not wired to an endpoint. Wire it when revoke lands.
5. **`CheckResult` gains `ok` and `rowErrorCount`** beside the spec's fields, and "the file has no data rows" / "wrong kind of file" are reported as a row error with `row: 0`.
6. **Over-long CSV rows** are stored with a reserved key `__extra_cells__` in the JSONL row; the checker turns it into a row error (a row that has it can never pass a check, so the runtime never serves it).
7. **A servlet filter enforces the 5 MB write cap at the transport** (Content-Length and chunked bodies); without it the cap would only apply after the whole body was parsed into memory.
8. **Roles:** upload, delete, bind, unbind = `BUYER_ADMIN`; list, check, bindings list, outputs = `BUYER_ADMIN` or `BUYER_APPROVER`. Cross-org dataset access is 404, cross-org connection/run access is 403 (matches `ConnectionController` / `RunController`).
9. **File links** use `agentstore.callback.base-url` (the URL agents already use) plus `/runtime/v1/resources/{resource}/file/content`.
10. **Required columns mean "present"**: a row must contain each required key (a null/blank value counts as present); coercion applies to non-null, non-blank cells.
11. **Known limitation, not fixed here:** an agent's JSON write body is parsed by Spring's default mapper, which reads `12.50` as the double `12.5` before it is stored. Uploaded files keep exact decimals (the normalizers use exact `BigDecimal`s). Making agent writes exact needs `spring.jackson.deserialization.use-big-decimal-for-floats` (a global change to every endpoint's `Map<String,Object>` numbers); flagged as a follow-up rather than slipped in.

## Review Focus

The inputs and conditions the spec implies but no happy path exercises, most likely to bite first. Each has a pinning test in the task named.

1. **CSV formula injection in downloaded outputs.** An agent-written string starting with `=`, `+`, `-`, `@`, tab or CR must not open as a formula in Excel; real JSON numbers such as `-5` stay untouched. → Task 10 (`OutputFormatsTest`, `csvDownloadNeutralisesFormulas`).
2. **Awkward CSV files.** BOM, quoted commas/quotes/embedded newlines, blank lines, short rows, over-long rows, duplicate or blank headers, a header-only file, non-UTF-8 bytes. → Task 3 (`CsvNormalizerTest`), Task 5 (over-long row fails the check).
3. **Paging correctness with multi-byte text.** Byte offsets, not character counts; a cursor from another file, garbage, or past EOF; exactly one page boundary at the end. → Task 7 (`pagesAcrossBoundariesWithMultiByteText`, `aCursorFromAnotherFileIsRejected`).
4. **A file disappearing or being replaced while runs use it.** Delete while a run is open (409), re-bind after trigger (run still reads the pinned file), purge skipped for pinned/bound files, deleted file retention. → Tasks 4, 6, 7, 11.
5. **Oversized or hostile agent writes.** A 6 MB body with Content-Length and as a chunked stream, the per-run total cap, 200 bad rows (only 50 errors returned), a write that fails validation leaves no blob and no row. → Task 8.

## File Structure

**Backend, new** (`backend/src/main/java/com/agentstore/platform/…`)
- `manifest/DataFormat.java`, `ColumnType.java`, `ResourceSchema.java`, `ResourceDecl.java`, `ResourceManifestRules.java` — manifest resource model and rules.
- `data/Dataset.java`, `DatasetPurpose.java`, `DatasetRepository.java`, `DatasetBinding.java`, `BindingOwnerType.java`, `DatasetBindingRepository.java`, `RunDatasetPin.java`, `RunDatasetPinId.java`, `RunDatasetPinRepository.java`, `RunOutput.java`, `OutputStatus.java`, `RunOutputRepository.java` — persistence.
- `data/Jsonl.java`, `Normalized.java`, `IngestionException.java`, `FormatDetector.java`, `CsvNormalizer.java`, `JsonNormalizer.java` — ingest.
- `data/DatasetService.java`, `DatasetController.java`, `DatasetResponse.java`, `Caller.java`, `DataApiException.java`, `DataApiErrors.java` — buyer datasets API.
- `data/ValueCoercer.java`, `SchemaChecker.java`, `DatasetCheckService.java`, `ResourceLookup.java` — schema check.
- `data/BindingService.java`, `BindingController.java`, `BindingResolver.java`, `DataAccessRules.java` — bindings, pins, mode guard.
- `data/RecordCursor.java`, `DatasetResourceBackend.java`, `RunOutputWriter.java` — runtime backend.
- `data/OutputCommitService.java`, `OutputsController.java`, `OutputFormats.java`, `DataRetentionService.java`, `DataRetentionJob.java`.
- `execution/GateOutcomeEvent.java`, `RunFinishedEvent.java` — events.
- `runtime/RuntimeBodyLimitFilter.java` — transport-level write cap.
- `db/migration/V27__datasets.sql`, `V28__run_output.sql`.

**Backend, modified:** `manifest/Manifest.java`, `ManifestParser.java`, `ManifestValidator.java`, `resources/manifest/manifest-schema.json`; `identity/Org.java`, `SecurityConfig.java`; `storage/ObjectStorage.java`; `runtime/ResourceBackend.java`, `RuntimeResourceController.java`, `RuntimeApiException.java`, `ProblemBody.java`, `RuntimeExceptionHandler.java`; `execution/GateService.java`, `RunLifecycleService.java`, `RunController.java`; `resources/application.yml`; `pom.xml`. **Deleted:** `runtime/UnavailableResourceBackend.java`.

**Repo root:** `contracts/runtime-v1.yaml` (regenerated), `ONTOLOGY.md`. **CLI:** `cli/src/schema/manifest-schema.json` (resynced).

**Backend tests, new:** `manifest/ResourceManifestTest`, `data/{DataModelTest,CsvNormalizerTest,JsonNormalizerTest,FormatDetectorTest,DatasetApiTest,DatasetUploadCapTest,ValueCoercerTest,SchemaCheckerTest,DatasetCheckEndpointTest,BindingApiTest,RunPinningTest,ModeGuardTest,DatasetResourceBackendReadTest,RuntimeWriteTest,StagedOutputsLifecycleTest,OutputFormatsTest,OutputsApiTest,DataRetentionTest,DataPlaneEndToEndTest,DataFixtures}`, `storage/ObjectStorageRangeTest`, `identity/DataEndpointsAreNotKeyReachableTest`. **Test resource, new:** `src/test/resources/manifest/valid-data-agent.json`.

---

### Task 1: Manifest `resources`

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/manifest/DataFormat.java`, `ColumnType.java`, `ResourceSchema.java`, `ResourceDecl.java`, `ResourceManifestRules.java`
- Create: `backend/src/test/resources/manifest/valid-data-agent.json`
- Modify: `manifest/Manifest.java`, `manifest/ManifestParser.java`, `manifest/ManifestValidator.java`, `backend/src/main/resources/manifest/manifest-schema.json`, `cli/src/schema/manifest-schema.json` (via script)
- Test: `backend/src/test/java/com/agentstore/platform/manifest/ResourceManifestTest.java`

**Interfaces:**
- Produces: `DataFormat {CSV, JSON, PDF}` with `boolean tabular()`, `String wire()`, `static Optional<DataFormat> fromWire(String)`; `ColumnType {STRING, NUMBER, INTEGER, BOOLEAN, DATE}` with `wire()`, `static ColumnType fromWire(String)` (null → STRING); `record ResourceSchema(List<String> required, Map<String, ColumnType> properties)`; `record ResourceDecl(String name, DataFormat format, ResourceSchema schema)` (`schema` nullable); `Manifest.resources()` (null when the key is absent), `Manifest.resource(String) : Optional<ResourceDecl>`, `Manifest.declaresResources()`; `ResourceManifestRules.check(Manifest) : List<String>`.

- [ ] **Step 1: Create the shared test manifest**

Create `backend/src/test/resources/manifest/valid-data-agent.json` (used by most later tasks; `audit_log` is a WRITE scope with no approval, `journal_entries` requires approval):

```json
{
  "name": "Data Test Agent",
  "version": "1.0.0",
  "resources": [
    { "name": "bank_statement", "format": "csv",
      "schema": { "required": ["date", "narration", "amount"],
                  "properties": { "date": { "type": "date" }, "amount": { "type": "number" } } } },
    { "name": "statement_pdf", "format": "pdf" },
    { "name": "journal_entries", "format": "json",
      "schema": { "required": ["bank_line", "amount"], "properties": { "amount": { "type": "number" } } } },
    { "name": "audit_log", "format": "json" }
  ],
  "scopes": [
    { "type": "READ", "resource": "bank_statement", "description": "Read statement lines" },
    { "type": "READ", "resource": "statement_pdf", "description": "Read the statement PDF" },
    { "type": "WRITE", "resource": "journal_entries", "description": "Propose entries", "requiresApproval": true },
    { "type": "WRITE", "resource": "audit_log", "description": "Append audit lines" }
  ],
  "network": { "allowlist": [] },
  "costEnvelope": { "currency": "INR", "maxPerRun": 500.00, "pricingUnit": "METERED", "unitPrice": 2.00, "unitLabel": "reconciled_transaction" },
  "approvalGates": [
    { "trigger": "post_journal_entries", "description": "Buyer approves entries before they are committed", "timeoutMinutes": 5 }
  ],
  "dataClasses": ["BANK_STATEMENT"]
}
```

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/java/com/agentstore/platform/manifest/ResourceManifestTest.java`:

```java
package com.agentstore.platform.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceManifestTest {

    final ObjectMapper mapper = new ObjectMapper();
    final ManifestParser parser = new ManifestParser();
    final ManifestValidator validator = new ManifestValidator();

    String read(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/manifest/" + name));
    }

    /** valid-runtime-agent.json declares scopes on bank_statement, invoices (READ) and journal_entries (WRITE). */
    String runtimeAgentWithResources(String resourcesJson) throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(read("valid-runtime-agent.json"));
        root.set("resources", mapper.readTree(resourcesJson));
        return root.toString();
    }

    static final String ALL_THREE = """
            [{"name":"bank_statement","format":"csv"},{"name":"invoices","format":"csv"},{"name":"journal_entries","format":"json"}]""";

    @Test
    void parsesResourcesWithTypedSchemas() throws Exception {
        Manifest manifest = parser.parse(read("valid-data-agent.json"));

        assertThat(manifest.resources()).extracting(ResourceDecl::name)
                .containsExactly("bank_statement", "statement_pdf", "journal_entries", "audit_log");
        ResourceDecl statement = manifest.resource("bank_statement").orElseThrow();
        assertThat(statement.format()).isEqualTo(DataFormat.CSV);
        assertThat(statement.schema().required()).containsExactly("date", "narration", "amount");
        assertThat(statement.schema().properties()).containsEntry("date", ColumnType.DATE)
                .containsEntry("amount", ColumnType.NUMBER);
        assertThat(manifest.resource("statement_pdf").orElseThrow().schema()).isNull();
        assertThat(manifest.resource("nope")).isEmpty();
    }

    @Test
    void aManifestWithoutResourcesStaysUntyped() throws Exception {
        Manifest manifest = parser.parse(read("valid-tier-a.json"));

        assertThat(manifest.resources()).isNull();
        assertThat(manifest.declaresResources()).isFalse();
        assertThat(manifest.resource("bank_statement")).isEmpty();
        assertThat(validator.validate(read("valid-tier-a.json")).valid()).isTrue();
    }

    @Test
    void theDataAgentManifestIsValid() throws Exception {
        var result = validator.validate(read("valid-data-agent.json"));
        assertThat(result.errors()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    void acceptsResourcesThatCoverEveryScope() throws Exception {
        assertThat(validator.validate(runtimeAgentWithResources(ALL_THREE)).valid()).isTrue();
    }

    @Test
    void rejectsADuplicateResourceName() throws Exception {
        var result = validator.validate(runtimeAgentWithResources("""
                [{"name":"bank_statement","format":"csv"},{"name":"bank_statement","format":"json"},
                 {"name":"invoices","format":"csv"},{"name":"journal_entries","format":"json"}]"""));
        assertThat(result.valid()).isFalse();
        assertThat(String.join(";", result.errors())).contains("declared twice");
    }

    @Test
    void rejectsAScopeThatNamesAnUndeclaredResourceWhenResourcesArePresent() throws Exception {
        var result = validator.validate(runtimeAgentWithResources("""
                [{"name":"bank_statement","format":"csv"},{"name":"journal_entries","format":"json"}]"""));
        assertThat(result.valid()).isFalse();
        assertThat(String.join(";", result.errors())).contains("'invoices'").contains("not declared");
    }

    @Test
    void rejectsWritingAPdfResource() throws Exception {
        var result = validator.validate(runtimeAgentWithResources("""
                [{"name":"bank_statement","format":"csv"},{"name":"invoices","format":"csv"},
                 {"name":"journal_entries","format":"pdf"}]"""));
        assertThat(result.valid()).isFalse();
        assertThat(String.join(";", result.errors())).contains("can only be READ");
    }

    @Test
    void rejectsASchemaOnAPdfResource() throws Exception {
        var result = validator.validate(runtimeAgentWithResources("""
                [{"name":"bank_statement","format":"pdf","schema":{"required":["a"]}},{"name":"invoices","format":"csv"},
                 {"name":"journal_entries","format":"json"}]"""));
        assertThat(result.valid()).isFalse();
    }

    @Test
    void rejectsAnUnknownColumnTypeAndAnUnknownFormat() throws Exception {
        assertThat(validator.validate(runtimeAgentWithResources("""
                [{"name":"bank_statement","format":"csv","schema":{"properties":{"amount":{"type":"decimal"}}}},
                 {"name":"invoices","format":"csv"},{"name":"journal_entries","format":"json"}]""")).valid()).isFalse();
        assertThat(validator.validate(runtimeAgentWithResources("""
                [{"name":"bank_statement","format":"xlsx"},{"name":"invoices","format":"csv"},
                 {"name":"journal_entries","format":"json"}]""")).valid()).isFalse();
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=ResourceManifestTest`
Expected: COMPILE FAIL (`ResourceDecl`, `DataFormat`, `Manifest.resource` do not exist).

- [ ] **Step 4: Implement the model**

Create `manifest/DataFormat.java`:

```java
package com.agentstore.platform.manifest;

import java.util.Locale;
import java.util.Optional;

/** The file formats a dataset or a manifest resource can have. */
public enum DataFormat {
    CSV, JSON, PDF;

    /** CSV and JSON are rows of records; PDF is a single document read through the file endpoint. */
    public boolean tabular() {
        return this != PDF;
    }

    /** The lowercase name used in manifests and on the runtime API. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<DataFormat> fromWire(String value) {
        for (DataFormat format : values()) {
            if (format.wire().equals(value)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
```

Create `manifest/ColumnType.java`:

```java
package com.agentstore.platform.manifest;

import java.util.Locale;

public enum ColumnType {
    STRING, NUMBER, INTEGER, BOOLEAN, DATE;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** A missing or unknown type is a string, per the manifest rules. */
    public static ColumnType fromWire(String value) {
        for (ColumnType type : values()) {
            if (type.wire().equals(value)) {
                return type;
            }
        }
        return STRING;
    }
}
```

Create `manifest/ResourceSchema.java`:

```java
package com.agentstore.platform.manifest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code required} lists column (or key) names; {@code properties} gives the type of the columns that have one. */
public record ResourceSchema(List<String> required, Map<String, ColumnType> properties) {
    public ResourceSchema {
        required = List.copyOf(required);
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }
}
```

Create `manifest/ResourceDecl.java`:

```java
package com.agentstore.platform.manifest;

/** One entry of the manifest's {@code resources[]}. {@code schema} is null when none was declared (always for PDF). */
public record ResourceDecl(String name, DataFormat format, ResourceSchema schema) {
}
```

Create `manifest/ResourceManifestRules.java`:

```java
package com.agentstore.platform.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-field rules the JSON schema cannot express. They apply only when the manifest declares {@code resources};
 * a manifest without them stays valid and untyped.
 */
public final class ResourceManifestRules {

    private ResourceManifestRules() {
    }

    public static List<String> check(Manifest manifest) {
        List<String> errors = new ArrayList<>();
        if (manifest.resources() == null) {
            return errors;
        }
        Map<String, ResourceDecl> byName = new LinkedHashMap<>();
        for (ResourceDecl resource : manifest.resources()) {
            if (byName.put(resource.name(), resource) != null) {
                errors.add("Resource name is declared twice: " + resource.name());
            }
        }
        for (Scope scope : manifest.scopes()) {
            ResourceDecl resource = byName.get(scope.resource());
            if (resource == null) {
                errors.add("Scope " + scope.type() + " '" + scope.resource()
                        + "' names a resource that is not declared in resources");
            } else if (resource.format() == DataFormat.PDF && scope.type() == ScopeType.WRITE) {
                errors.add("Resource '" + resource.name() + "' is a pdf and can only be READ");
            }
        }
        return errors;
    }
}
```

Replace `manifest/Manifest.java` with:

```java
package com.agentstore.platform.manifest;

import java.util.List;
import java.util.Optional;

/**
 * {@code runtime} and {@code entrypoint} are required for bundles (BundleManifestRules) and null otherwise.
 * {@code resources} is null when the manifest has no {@code resources} key (untyped resources), never an empty
 * stand-in.
 */
public record Manifest(String name, String version, List<Scope> scopes, List<String> networkAllowlist,
                        CostEnvelope costEnvelope, List<ApprovalGateDecl> approvalGates,
                        List<String> dataClasses, String runtime, String entrypoint, List<ResourceDecl> resources) {

    public Manifest(String name, String version, List<Scope> scopes, List<String> networkAllowlist,
                    CostEnvelope costEnvelope, List<ApprovalGateDecl> approvalGates,
                    List<String> dataClasses, String runtime, String entrypoint) {
        this(name, version, scopes, networkAllowlist, costEnvelope, approvalGates, dataClasses, runtime, entrypoint, null);
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

In `manifest/ManifestParser.java` change the final `return new Manifest(...)` to pass `parseResources(root)` as a 10th argument, add the imports `java.util.LinkedHashMap` and `java.util.Map`, and add these methods before `textOrNull`:

```java
        return new Manifest(root.get("name").asText(), root.get("version").asText(), scopes, allowlist,
                costEnvelope, gates, dataClasses, textOrNull(root, "runtime"), textOrNull(root, "entrypoint"),
                parseResources(root));
    }

    private static List<ResourceDecl> parseResources(JsonNode root) {
        JsonNode array = root.get("resources");
        if (array == null || !array.isArray()) {
            return null;
        }
        List<ResourceDecl> resources = new ArrayList<>();
        for (JsonNode r : array) {
            String wire = r.get("format").asText();
            DataFormat format = DataFormat.fromWire(wire)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown resource format: " + wire));
            resources.add(new ResourceDecl(r.get("name").asText(), format, parseSchema(r.get("schema"))));
        }
        return resources;
    }

    private static ResourceSchema parseSchema(JsonNode schema) {
        if (schema == null || schema.isNull()) {
            return null;
        }
        List<String> required = new ArrayList<>();
        for (JsonNode name : schema.path("required")) {
            required.add(name.asText());
        }
        Map<String, ColumnType> properties = new LinkedHashMap<>();
        schema.path("properties").fields().forEachRemaining(entry ->
                properties.put(entry.getKey(), ColumnType.fromWire(entry.getValue().path("type").asText(null))));
        return new ResourceSchema(required, properties);
    }
```

(The old `return new Manifest(...)` and its closing `}` are replaced by the block above; keep `textOrNull` after it.)

In `manifest/ManifestValidator.java` add a field `private final ManifestParser parser = new ManifestParser();` and change the tail of `validate` to:

```java
        var errors = schema.validate(node);
        if (!errors.isEmpty()) {
            return new Result(false, errors.stream().map(Object::toString).collect(Collectors.toList()));
        }
        List<String> semantic = ResourceManifestRules.check(parser.parse(rawJson));
        return semantic.isEmpty() ? new Result(true, List.of()) : new Result(false, semantic);
    }
```

In `backend/src/main/resources/manifest/manifest-schema.json`, add this property next to `dataClasses` (mind the comma):

```json
    "resources": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["name", "format"],
        "additionalProperties": false,
        "properties": {
          "name": { "type": "string", "pattern": "^[A-Za-z0-9_][A-Za-z0-9_-]{0,99}$" },
          "format": { "type": "string", "enum": ["csv", "json", "pdf"] },
          "schema": {
            "type": "object",
            "additionalProperties": false,
            "properties": {
              "required": { "type": "array", "items": { "type": "string", "minLength": 1 } },
              "properties": {
                "type": "object",
                "additionalProperties": {
                  "type": "object",
                  "additionalProperties": false,
                  "properties": {
                    "type": { "type": "string", "enum": ["string", "number", "integer", "boolean", "date"] }
                  }
                }
              }
            }
          }
        },
        "if": { "properties": { "format": { "const": "pdf" } }, "required": ["format"] },
        "then": { "not": { "required": ["schema"] } }
      }
    },
```

- [ ] **Step 5: Run to verify it passes, plus the existing manifest tests**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='ResourceManifestTest,ManifestValidatorTest,ManifestParserTest,BundleManifestRulesTest,ManifestRuntimeFieldsTest,BundleManifestParsingTest'`
Expected: PASS.

- [ ] **Step 6: Resync the CLI's bundled schema**

Run: `cd cli && npm run sync-schema && npx vitest run`
Expected: `copied … manifest-schema.json`, all vitest tests PASS (the "keeps the bundled schema identical" test now matches the new backend schema).

- [ ] **Step 7: Commit**

```bash
git add backend/src cli/src/schema/manifest-schema.json
git commit -m "feat(manifest): resources[] with an inline schema"
```

---

### Task 2: Migrations, entities, repositories

**Files:**
- Create: `backend/src/main/resources/db/migration/V27__datasets.sql`, `V28__run_output.sql`
- Create (all in `backend/src/main/java/com/agentstore/platform/data/`): `DatasetPurpose.java`, `Dataset.java`, `DatasetRepository.java`, `BindingOwnerType.java`, `DatasetBinding.java`, `DatasetBindingRepository.java`, `RunDatasetPinId.java`, `RunDatasetPin.java`, `RunDatasetPinRepository.java`, `OutputStatus.java`, `RunOutput.java`, `RunOutputRepository.java`, `Jsonl.java`
- Modify: `identity/Org.java`
- Test: `backend/src/test/java/com/agentstore/platform/data/DataModelTest.java`

**Interfaces:**
- Produces (used by every later task):
  - `Dataset` (Lombok getters/setters): `id, ownerOrgId, purpose, displayName, sourceLabel, uploadedByUserId, format (DataFormat), storageKey, recordsKey, sha256, sizeBytes (long), recordsSizeBytes (long), rowCount (Long), columnsJson, createdAt, deletedAt, purgeAfter, purgedAt`; `List<String> columns()`, `void setColumns(List<String>)`.
  - `DatasetRepository`: `findByIdAndOwnerOrgId(UUID, UUID)`, `findByOwnerOrgIdAndPurposeAndDeletedAtIsNullOrderByCreatedAtDesc(UUID, DatasetPurpose)`, `findByIdForUpdate(UUID)`, `long usedBytes(UUID orgId)`, `List<Dataset> findDueForPurge(Instant now)`.
  - `DatasetBinding`: `id, ownerType, ownerId, resourceName, datasetId, checkResult (String JSON), boundByUserId, boundAt`. `DatasetBindingRepository`: `findByOwnerTypeAndOwnerIdAndResourceName`, `findByOwnerTypeAndOwnerId`, `findByDatasetIdIn(Collection<UUID>)`, `boolean existsByDatasetId(UUID)`, `void deleteByDatasetId(UUID)`.
  - `RunDatasetPin(UUID runId, String resourceName, UUID datasetId, String sha256)` with `getId().getRunId()/getResourceName()`, `getDatasetId()`, `getSha256()`; `RunDatasetPinRepository`: `findByIdRunId(UUID)`, `findByIdRunIdAndIdResourceName(UUID, String)`, `boolean pinnedByOpenRun(UUID datasetId)`.
  - `RunOutput(UUID id, UUID runId, String resourceName, OutputStatus status, int recordCount, long sizeBytes, String storageKey)` with getters and setters for `status`, `gateRequestId`, `storageKey`; `RunOutputRepository`: `findByRunIdOrderByCreatedAtAsc`, `findByIdAndRunId`, `long totalBytes(UUID runId)`, `int transition(runId, from, to, gateId, resources, requestedAt)`, `int discardStaged(UUID runId)`, `findByStatusAndCreatedAtBeforeAndStorageKeyIsNotNull(OutputStatus, Instant)`.
  - `Jsonl.MAPPER` (Jackson, exact decimals) and `Jsonl.readRows(InputStream) : MappingIterator<Map<String,Object>>`.
  - `Org.getStorageLimitBytes()` / `setStorageLimitBytes(long)` (default 5 GiB). `OrgRepository.findByIdForUpdate` already exists.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/agentstore/platform/data/DataModelTest.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.manifest.DataFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataModelTest extends AbstractIntegrationTest {

    @Autowired
    OrgRepository orgRepository;
    @Autowired
    DatasetRepository datasetRepository;
    @Autowired
    DatasetBindingRepository bindingRepository;

    Dataset dataset(UUID orgId) {
        Dataset d = new Dataset();
        d.setOwnerOrgId(orgId);
        d.setPurpose(DatasetPurpose.BUYER_DATA);
        d.setDisplayName("HDFC Jan");
        d.setFormat(DataFormat.CSV);
        d.setStorageKey("datasets/x/original");
        d.setRecordsKey("datasets/x/records.jsonl");
        d.setSha256("a".repeat(64));
        d.setSizeBytes(100);
        d.setRecordsSizeBytes(150);
        d.setRowCount(2L);
        d.setColumns(List.of("date", "amount"));
        return datasetRepository.save(d);
    }

    @Test
    void anOrgDefaultsToFiveGibOfStorage() {
        Org org = orgRepository.save(new Org(null, "Acme " + UUID.randomUUID(), OrgType.BUYER));

        assertThat(orgRepository.findById(org.getId()).orElseThrow().getStorageLimitBytes())
                .isEqualTo(5L * 1024 * 1024 * 1024);
    }

    @Test
    void aDatasetRoundTripsAndUsageCountsOriginalPlusRecords() {
        Org org = orgRepository.save(new Org(null, "Acme " + UUID.randomUUID(), OrgType.BUYER));
        Dataset saved = dataset(org.getId());

        Dataset loaded = datasetRepository.findByIdAndOwnerOrgId(saved.getId(), org.getId()).orElseThrow();
        assertThat(loaded.columns()).containsExactly("date", "amount");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(datasetRepository.usedBytes(org.getId())).isEqualTo(250);
        assertThat(datasetRepository.findByIdAndOwnerOrgId(saved.getId(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void aResourceCanBeBoundToOnlyOneDatasetPerOwner() {
        Org org = orgRepository.save(new Org(null, "Acme " + UUID.randomUUID(), OrgType.BUYER));
        Dataset a = dataset(org.getId());
        Dataset b = dataset(org.getId());
        UUID connection = UUID.randomUUID();
        bindingRepository.saveAndFlush(new DatasetBinding(BindingOwnerType.CONNECTION, connection, "bank_statement",
                a.getId(), "{}", UUID.randomUUID()));

        assertThatThrownBy(() -> bindingRepository.saveAndFlush(new DatasetBinding(BindingOwnerType.CONNECTION,
                connection, "bank_statement", b.getId(), "{}", UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(bindingRepository.existsByDatasetId(a.getId())).isTrue();
        assertThat(bindingRepository.findByDatasetIdIn(List.of(a.getId(), b.getId()))).hasSize(1);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=DataModelTest`
Expected: COMPILE FAIL (`Dataset`, `DatasetRepository`, … do not exist).

- [ ] **Step 3: Write the migrations**

Create `V27__datasets.sql`:

```sql
ALTER TABLE org ADD COLUMN storage_limit_bytes BIGINT NOT NULL DEFAULT 5368709120;

CREATE TABLE dataset (
    id UUID PRIMARY KEY,
    owner_org_id UUID NOT NULL REFERENCES org(id),
    purpose VARCHAR(20) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    source_label VARCHAR(200),
    uploaded_by_user_id UUID,            -- no FK: tests upload with synthetic user ids
    format VARCHAR(10) NOT NULL,
    storage_key VARCHAR(500),            -- null once purged
    records_key VARCHAR(500),            -- normalized JSONL; null for PDF and once purged
    sha256 VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    records_size_bytes BIGINT NOT NULL DEFAULT 0,
    row_count BIGINT,                    -- null for PDF
    columns JSONB NOT NULL DEFAULT '[]',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    purge_after TIMESTAMPTZ,
    purged_at TIMESTAMPTZ
);
CREATE INDEX dataset_org_idx ON dataset (owner_org_id) WHERE purged_at IS NULL;
CREATE INDEX dataset_purge_idx ON dataset (purge_after) WHERE purged_at IS NULL AND purge_after IS NOT NULL;

CREATE TABLE dataset_binding (
    id UUID PRIMARY KEY,
    owner_type VARCHAR(20) NOT NULL,
    owner_id UUID NOT NULL,
    resource_name VARCHAR(100) NOT NULL,
    dataset_id UUID NOT NULL REFERENCES dataset(id),
    check_result JSONB NOT NULL DEFAULT '{}',
    bound_by_user_id UUID,
    bound_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT dataset_binding_owner_resource_uq UNIQUE (owner_type, owner_id, resource_name)
);
CREATE INDEX dataset_binding_dataset_idx ON dataset_binding (dataset_id);

-- What each run reads. Written at run creation; a purged dataset is tombstoned, never deleted, so this FK never dangles.
CREATE TABLE run_dataset_pin (
    run_id UUID NOT NULL REFERENCES run(id),
    resource_name VARCHAR(100) NOT NULL,
    dataset_id UUID NOT NULL REFERENCES dataset(id),
    sha256 VARCHAR(64) NOT NULL,
    PRIMARY KEY (run_id, resource_name)
);
CREATE INDEX run_dataset_pin_dataset_idx ON run_dataset_pin (dataset_id);
```

Create `V28__run_output.sql`:

```sql
CREATE TABLE run_output (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES run(id),
    resource_name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    record_count INT NOT NULL,
    size_bytes BIGINT NOT NULL,
    storage_key VARCHAR(500),            -- JSONL chunk; null once its blob was purged
    gate_request_id UUID REFERENCES gate_request(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX run_output_run_status_idx ON run_output (run_id, status);
```

- [ ] **Step 4: Change `Org`**

In `identity/Org.java` add, below `type`:

```java
    public static final long DEFAULT_STORAGE_LIMIT_BYTES = 5L * 1024 * 1024 * 1024;

    @Column(name = "storage_limit_bytes", nullable = false)
    private long storageLimitBytes = DEFAULT_STORAGE_LIMIT_BYTES;
```

(The class-level `@AllArgsConstructor` gains a fifth parameter; nothing in the code base calls it with four arguments.)

- [ ] **Step 5: Write enums, entities and repositories**

Enums in `data/`: `DatasetPurpose.java` → `public enum DatasetPurpose { BUYER_DATA, FIXTURE, SAMPLE, EVAL }`; `BindingOwnerType.java` → `public enum BindingOwnerType { CONNECTION, SANDBOX_PROFILE, EVAL_CASE, TRIAL }`; `OutputStatus.java` → `public enum OutputStatus { STAGED, COMMITTED, DISCARDED }` (each with the `package com.agentstore.platform.data;` line).

`data/Jsonl.java`:

```java
package com.agentstore.platform.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** One JSON object per line. Decimals stay exact (12.50 is not rewritten as 12.5 or 1.25E+1). */
public final class Jsonl {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    public static final TypeReference<Map<String, Object>> ROW = new TypeReference<>() {};

    private Jsonl() {
    }

    public static MappingIterator<Map<String, Object>> readRows(InputStream in) throws IOException {
        return MAPPER.readerFor(ROW).readValues(in);
    }
}
```

`data/Dataset.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.manifest.DataFormat;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A file in a workspace library. Untyped until it is bound: nothing is checked against a resource before that.
 * A purged dataset stays as a tombstone (name, size, sha) so run pins and traces keep pointing at something.
 */
@Entity
@Table(name = "dataset")
@Getter
@Setter
@NoArgsConstructor
public class Dataset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID ownerOrgId;

    @Enumerated(EnumType.STRING)
    private DatasetPurpose purpose = DatasetPurpose.BUYER_DATA;

    private String displayName;
    private String sourceLabel;
    private UUID uploadedByUserId;

    @Enumerated(EnumType.STRING)
    private DataFormat format;

    private String storageKey;
    private String recordsKey;
    private String sha256;
    private long sizeBytes;
    private long recordsSizeBytes;
    private Long rowCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "columns", columnDefinition = "jsonb")
    private String columnsJson = "[]";

    private Instant createdAt;
    private Instant deletedAt;
    private Instant purgeAfter;
    private Instant purgedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public List<String> columns() {
        try {
            return Jsonl.MAPPER.readValue(columnsJson, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public void setColumns(List<String> columns) {
        try {
            this.columnsJson = Jsonl.MAPPER.writeValueAsString(columns);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`data/DatasetRepository.java`:

```java
package com.agentstore.platform.data;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DatasetRepository extends JpaRepository<Dataset, UUID> {

    Optional<Dataset> findByIdAndOwnerOrgId(UUID id, UUID ownerOrgId);

    List<Dataset> findByOwnerOrgIdAndPurposeAndDeletedAtIsNullOrderByCreatedAtDesc(UUID ownerOrgId, DatasetPurpose purpose);

    /** Row lock for bind, delete and purge, so they cannot interleave on one file. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Dataset d where d.id = :id")
    Optional<Dataset> findByIdForUpdate(@Param("id") UUID id);

    /** Originals plus their JSONL copies of non-purged datasets (outputs are not counted). */
    @Query("select coalesce(sum(d.sizeBytes + d.recordsSizeBytes), 0) from Dataset d "
            + "where d.ownerOrgId = :orgId and d.purgedAt is null")
    long usedBytes(@Param("orgId") UUID orgId);

    @Query("select d from Dataset d where d.purgeAfter <= :now and d.purgedAt is null")
    List<Dataset> findDueForPurge(@Param("now") Instant now);
}
```

`data/DatasetBinding.java`:

```java
package com.agentstore.platform.data;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** A file plugged into one resource of one owner (a connection today). Unique per (owner, resource). */
@Entity
@Table(name = "dataset_binding")
@Getter
@Setter
@NoArgsConstructor
public class DatasetBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    private BindingOwnerType ownerType;

    private UUID ownerId;
    private String resourceName;
    private UUID datasetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "check_result", columnDefinition = "jsonb")
    private String checkResult = "{}";

    private UUID boundByUserId;
    private Instant boundAt;

    public DatasetBinding(BindingOwnerType ownerType, UUID ownerId, String resourceName, UUID datasetId,
                          String checkResult, UUID boundByUserId) {
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.resourceName = resourceName;
        this.datasetId = datasetId;
        this.checkResult = checkResult;
        this.boundByUserId = boundByUserId;
    }

    @PrePersist
    void onCreate() {
        if (boundAt == null) boundAt = Instant.now();
    }
}
```

`data/DatasetBindingRepository.java`:

```java
package com.agentstore.platform.data;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DatasetBindingRepository extends JpaRepository<DatasetBinding, UUID> {

    Optional<DatasetBinding> findByOwnerTypeAndOwnerIdAndResourceName(BindingOwnerType ownerType, UUID ownerId,
                                                                      String resourceName);

    List<DatasetBinding> findByOwnerTypeAndOwnerId(BindingOwnerType ownerType, UUID ownerId);

    List<DatasetBinding> findByDatasetIdIn(Collection<UUID> datasetIds);

    boolean existsByDatasetId(UUID datasetId);

    void deleteByDatasetId(UUID datasetId);
}
```

`data/RunDatasetPinId.java`:

```java
package com.agentstore.platform.data;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class RunDatasetPinId implements Serializable {

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "resource_name")
    private String resourceName;
}
```

`data/RunDatasetPin.java`:

```java
package com.agentstore.platform.data;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** The file a run reads for one resource, fixed when the run was created. */
@Entity
@Table(name = "run_dataset_pin")
@Getter
@NoArgsConstructor
public class RunDatasetPin {

    @EmbeddedId
    private RunDatasetPinId id;

    private UUID datasetId;
    private String sha256;

    public RunDatasetPin(UUID runId, String resourceName, UUID datasetId, String sha256) {
        this.id = new RunDatasetPinId(runId, resourceName);
        this.datasetId = datasetId;
        this.sha256 = sha256;
    }
}
```

`data/RunDatasetPinRepository.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.execution.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunDatasetPinRepository extends JpaRepository<RunDatasetPin, RunDatasetPinId> {

    List<RunStatus> OPEN_STATUSES = Arrays.stream(RunStatus.values()).filter(s -> !s.isTerminal()).toList();

    List<RunDatasetPin> findByIdRunId(UUID runId);

    Optional<RunDatasetPin> findByIdRunIdAndIdResourceName(UUID runId, String resourceName);

    @Query("select count(p) from RunDatasetPin p, com.agentstore.platform.execution.Run r "
            + "where r.id = p.id.runId and p.datasetId = :datasetId and r.status in :statuses")
    long countPinnedByRunsIn(@Param("datasetId") UUID datasetId, @Param("statuses") Collection<RunStatus> statuses);

    /** True while a run that has not ended reads this file. */
    default boolean pinnedByOpenRun(UUID datasetId) {
        return countPinnedByRunsIn(datasetId, OPEN_STATUSES) > 0;
    }
}
```

`data/RunOutput.java`:

```java
package com.agentstore.platform.data;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One agent write call: a JSONL chunk in the object store, staged until an approved gate commits it. */
@Entity
@Table(name = "run_output")
@Getter
@Setter
@NoArgsConstructor
public class RunOutput {

    @Id
    private UUID id;

    private UUID runId;
    private String resourceName;

    @Enumerated(EnumType.STRING)
    private OutputStatus status;

    private int recordCount;
    private long sizeBytes;
    private String storageKey;
    private UUID gateRequestId;
    private Instant createdAt;

    /** The id is chosen by the caller because the blob key {@code outputs/{runId}/{id}.jsonl} is written first. */
    public RunOutput(UUID id, UUID runId, String resourceName, OutputStatus status, int recordCount, long sizeBytes,
                     String storageKey) {
        this.id = id;
        this.runId = runId;
        this.resourceName = resourceName;
        this.status = status;
        this.recordCount = recordCount;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`data/RunOutputRepository.java`:

```java
package com.agentstore.platform.data;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunOutputRepository extends JpaRepository<RunOutput, UUID> {

    List<RunOutput> findByRunIdOrderByCreatedAtAsc(UUID runId);

    Optional<RunOutput> findByIdAndRunId(UUID id, UUID runId);

    List<RunOutput> findByStatusAndCreatedAtBeforeAndStorageKeyIsNotNull(OutputStatus status, Instant before);

    @Query("select coalesce(sum(o.sizeBytes), 0) from RunOutput o where o.runId = :runId")
    long totalBytes(@Param("runId") UUID runId);

    /** Moves the run's STAGED rows for these resources, staged no later than the gate was requested. */
    @Modifying(flushAutomatically = true)
    @Query("update RunOutput o set o.status = :to, o.gateRequestId = :gateId "
            + "where o.runId = :runId and o.status = :from and o.resourceName in :resources "
            + "and o.createdAt <= :requestedAt")
    int transition(@Param("runId") UUID runId, @Param("from") OutputStatus from, @Param("to") OutputStatus to,
                   @Param("gateId") UUID gateId, @Param("resources") Collection<String> resources,
                   @Param("requestedAt") Instant requestedAt);

    @Modifying(flushAutomatically = true)
    @Query("update RunOutput o set o.status = com.agentstore.platform.data.OutputStatus.DISCARDED "
            + "where o.runId = :runId and o.status = com.agentstore.platform.data.OutputStatus.STAGED")
    int discardStaged(@Param("runId") UUID runId);
}
```

- [ ] **Step 6: Run to verify it passes, plus a smoke of the existing suite start-up**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='DataModelTest,PlatformApplicationTest,RuntimeDataModelTest'`
Expected: PASS (Hibernate `validate` accepts the new mappings).

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat(data): datasets, bindings, run pins and outputs tables"
```

---

### Task 3: Ingestion

**Files:**
- Create (in `data/`): `Normalized.java`, `IngestionException.java`, `FormatDetector.java`, `CsvNormalizer.java`, `JsonNormalizer.java`
- Modify: `backend/pom.xml`, `storage/ObjectStorage.java`, `backend/src/main/resources/application.yml`
- Test: `data/CsvNormalizerTest.java`, `data/JsonNormalizerTest.java`, `data/FormatDetectorTest.java`, `storage/ObjectStorageRangeTest.java`

**Interfaces:**
- Consumes: `Jsonl.MAPPER`, `DataFormat` (Tasks 1, 2).
- Produces:
  - `record Normalized(long rowCount, List<String> columns)` with `static final String EXTRA_KEY = "__extra_cells__"`.
  - `class IngestionException extends RuntimeException`.
  - `FormatDetector.detect(String filename, Path file) : Optional<DataFormat>`.
  - `CsvNormalizer.normalize(Path in, Path out) : Normalized` and `JsonNormalizer.normalize(Path in, Path out) : Normalized` (write JSONL to `out`; throw `IngestionException` with a buyer-readable message).
  - `ObjectStorage.putFile(String key, Path file)` and `ObjectStorage.openRange(String key, long from, long toInclusive) : InputStream`.

- [ ] **Step 1: Write the failing tests**

`data/CsvNormalizerTest.java`:

```java
package com.agentstore.platform.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvNormalizerTest {

    @TempDir
    Path dir;

    Normalized run(String csv, List<String> jsonlOut) throws Exception {
        return run(csv.getBytes(StandardCharsets.UTF_8), jsonlOut);
    }

    Normalized run(byte[] csv, List<String> jsonlOut) throws Exception {
        Path in = dir.resolve("in.csv");
        Path out = dir.resolve("out.jsonl");
        Files.write(in, csv);
        Normalized n = CsvNormalizer.normalize(in, out);
        jsonlOut.addAll(Files.readAllLines(out));
        return n;
    }

    @Test
    void turnsRowsIntoStringValuedObjects() throws Exception {
        List<String> lines = new java.util.ArrayList<>();
        Normalized n = run("date,narration,amount\n2026-01-05,Salary,50000.00\n2026-01-06,Rent,-12000.50\n", lines);

        assertThat(n.rowCount()).isEqualTo(2);
        assertThat(n.columns()).containsExactly("date", "narration", "amount");
        assertThat(lines).containsExactly(
                "{\"date\":\"2026-01-05\",\"narration\":\"Salary\",\"amount\":\"50000.00\"}",
                "{\"date\":\"2026-01-06\",\"narration\":\"Rent\",\"amount\":\"-12000.50\"}");
    }

    @Test
    void handlesABomQuotingAndEmbeddedNewlinesAndBlankLines() throws Exception {
        List<String> lines = new java.util.ArrayList<>();
        String csv = "﻿a,b\n\n\"x, \"\"quoted\"\"\",\"line1\nline2\"\n";
        Normalized n = run(csv, lines);

        assertThat(n.columns()).containsExactly("a", "b");   // BOM not glued to the first header
        assertThat(n.rowCount()).isEqualTo(1);
        assertThat(lines).hasSize(1);                         // the embedded newline is escaped, not a new line
        assertThat(lines.get(0)).isEqualTo("{\"a\":\"x, \\\"quoted\\\"\",\"b\":\"line1\\nline2\"}");
    }

    @Test
    void aShortRowGetsNullCellsAndALongRowIsMarked() throws Exception {
        List<String> lines = new java.util.ArrayList<>();
        Normalized n = run("a,b,c\n1,2\n1,2,3,4,5\n", lines);

        assertThat(n.columns()).containsExactly("a", "b", "c");
        assertThat(lines.get(0)).isEqualTo("{\"a\":\"1\",\"b\":\"2\",\"c\":null}");
        assertThat(lines.get(1)).isEqualTo("{\"a\":\"1\",\"b\":\"2\",\"c\":\"3\",\"" + Normalized.EXTRA_KEY + "\":[\"4\",\"5\"]}");
    }

    @Test
    void aHeaderOnlyFileHasZeroRows() throws Exception {
        List<String> lines = new java.util.ArrayList<>();
        Normalized n = run("a,b\n", lines);

        assertThat(n.rowCount()).isZero();
        assertThat(lines).isEmpty();
    }

    @Test
    void rejectsEmptyDuplicateAndBlankHeaders() {
        assertThatThrownBy(() -> run("", new java.util.ArrayList<>())).isInstanceOf(IngestionException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> run("a,a\n1,2\n", new java.util.ArrayList<>())).isInstanceOf(IngestionException.class)
                .hasMessageContaining("Duplicate column");
        assertThatThrownBy(() -> run("a,,c\n1,2,3\n", new java.util.ArrayList<>())).isInstanceOf(IngestionException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejectsBytesThatAreNotUtf8() {
        assertThatThrownBy(() -> run(new byte[]{'a', ',', 'b', '\n', (byte) 0xFF, (byte) 0xFE, ',', '1', '\n'},
                new java.util.ArrayList<>())).isInstanceOf(IngestionException.class).hasMessageContaining("UTF-8");
    }

    @Test
    void rejectsAnUnterminatedQuote() {
        assertThatThrownBy(() -> run("a,b\n\"oops,1\n", new java.util.ArrayList<>()))
                .isInstanceOf(IngestionException.class);
    }
}
```

`data/JsonNormalizerTest.java`:

```java
package com.agentstore.platform.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonNormalizerTest {

    @TempDir
    Path dir;

    Normalized run(String json, List<String> lines) throws Exception {
        Path in = dir.resolve("in.json");
        Path out = dir.resolve("out.jsonl");
        Files.writeString(in, json, StandardCharsets.UTF_8);
        Normalized n = JsonNormalizer.normalize(in, out);
        lines.addAll(Files.readAllLines(out));
        return n;
    }

    @Test
    void writesOneObjectPerLineWithTheUnionOfKeysAndExactDecimals() throws Exception {
        List<String> lines = new ArrayList<>();
        Normalized n = run("[{\"a\":1,\"amount\":12.50},{\"b\":\"x\",\"amount\":3}]", lines);

        assertThat(n.rowCount()).isEqualTo(2);
        assertThat(n.columns()).containsExactly("a", "amount", "b");
        assertThat(lines).containsExactly("{\"a\":1,\"amount\":12.50}", "{\"b\":\"x\",\"amount\":3}");
    }

    @Test
    void anEmptyArrayIsAValidFileWithNoRows() throws Exception {
        List<String> lines = new ArrayList<>();
        assertThat(run("[]", lines).rowCount()).isZero();
    }

    @Test
    void rejectsAnythingThatIsNotAnArrayOfObjects() {
        assertThatThrownBy(() -> run("{\"a\":1}", new ArrayList<>())).isInstanceOf(IngestionException.class)
                .hasMessageContaining("array of objects");
        assertThatThrownBy(() -> run("[1,2]", new ArrayList<>())).isInstanceOf(IngestionException.class)
                .hasMessageContaining("Item 1");
        assertThatThrownBy(() -> run("[{\"a\":1},", new ArrayList<>())).isInstanceOf(IngestionException.class);
        assertThatThrownBy(() -> run("[{\"a\":1}] trailing", new ArrayList<>())).isInstanceOf(IngestionException.class);
    }
}
```

`data/FormatDetectorTest.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.manifest.DataFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FormatDetectorTest {

    @TempDir
    Path dir;

    Optional<DataFormat> detect(String filename, String content) throws Exception {
        Path file = dir.resolve("f");
        Files.writeString(file, content, StandardCharsets.ISO_8859_1);
        return FormatDetector.detect(filename, file);
    }

    @Test
    void usesTheExtensionAndConfirmsPdfByItsMagicBytes() throws Exception {
        assertThat(detect("a.csv", "a,b\n1,2\n")).contains(DataFormat.CSV);
        assertThat(detect("A.CSV", "a,b\n")).contains(DataFormat.CSV);
        assertThat(detect("a.json", "[]")).contains(DataFormat.JSON);
        assertThat(detect("a.pdf", "%PDF-1.7\n...")).contains(DataFormat.PDF);
        assertThat(detect("a.pdf", "not a pdf")).isEmpty();
        assertThat(detect("a.csv", "%PDF-1.7\n...")).isEmpty();          // a PDF wearing a .csv name
    }

    @Test
    void sniffsWhenThereIsNoUsableExtension() throws Exception {
        assertThat(detect("export", "%PDF-1.4")).contains(DataFormat.PDF);
        assertThat(detect("export", "  \n[{\"a\":1}]")).contains(DataFormat.JSON);
        assertThat(detect("export", "a,b\n1,2")).isEmpty();               // CSV cannot be told from prose
        assertThat(detect("notes.exe", "MZ\u0090")).isEmpty();
        assertThat(detect("", "")).isEmpty();
    }
}
```

`storage/ObjectStorageRangeTest.java`:

```java
package com.agentstore.platform.storage;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectStorageRangeTest extends AbstractStorageIntegrationTest {

    @TempDir
    Path dir;

    @Test
    void putFileThenReadAnInclusiveByteRange() throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "0123456789", StandardCharsets.UTF_8);
        String key = "tests/" + UUID.randomUUID() + ".txt";

        storage.putFile(key, file);

        assertThat(storage.size(key)).contains(10L);
        try (InputStream in = storage.openRange(key, 3, 6)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("3456");
        }
        try (InputStream in = storage.openRange(key, 8, 9)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("89");
        }
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='CsvNormalizerTest,JsonNormalizerTest,FormatDetectorTest,ObjectStorageRangeTest'`
Expected: COMPILE FAIL.

- [ ] **Step 3: Add the dependency and config**

In `backend/pom.xml`, inside `<dependencies>` next to `json-schema-validator`:

```xml
    <dependency>
      <groupId>org.apache.commons</groupId>
      <artifactId>commons-csv</artifactId>
      <version>1.11.0</version>
    </dependency>
```

In `application.yml`, under `spring:` add (after `flyway`):

```yaml
  servlet:
    multipart:
      max-file-size: ${agentstore.data.max-file-size:50MB}
      max-request-size: ${agentstore.data.max-request-size:51MB}
```

and under `agentstore:` add:

```yaml
  data:
    retention-interval: PT1H
```

- [ ] **Step 4: Implement storage additions**

In `ObjectStorage.java` add the import `java.nio.file.Path;` and these methods after `put`:

```java
    /** Streams a local file (up to the upload cap) without holding it in memory. */
    public void putFile(String key, Path file) {
        try {
            s3.putObject(p -> p.bucket(bucket).key(key), RequestBody.fromFile(file));
        } catch (SdkException e) {
            throw new StorageException("Could not write " + key, e);
        }
    }

    /** Bytes {@code from}..{@code toInclusive} of an object. The caller must keep {@code from} below the object's size. */
    public InputStream openRange(String key, long from, long toInclusive) {
        try {
            return s3.getObject(g -> g.bucket(bucket).key(key).range("bytes=" + from + "-" + toInclusive));
        } catch (SdkException e) {
            throw new StorageException("Could not read " + key, e);
        }
    }
```

- [ ] **Step 5: Implement ingestion**

`data/Normalized.java`:

```java
package com.agentstore.platform.data;

import java.util.List;

/** What normalizing a file produced. {@code EXTRA_KEY} marks a CSV row that had more cells than the header. */
public record Normalized(long rowCount, List<String> columns) {
    public static final String EXTRA_KEY = "__extra_cells__";
}
```

`data/IngestionException.java`:

```java
package com.agentstore.platform.data;

/** The uploaded file cannot be read as its format. The message is shown to the buyer, so keep it plain. */
public class IngestionException extends RuntimeException {
    public IngestionException(String message) {
        super(message);
    }

    public IngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`data/FormatDetector.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.manifest.DataFormat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** Decides a file's format from its name and its first bytes. Empty means "not something we accept" (415). */
public final class FormatDetector {

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private FormatDetector() {
    }

    public static Optional<DataFormat> detect(String filename, Path file) throws IOException {
        byte[] head = head(file, 1024);
        boolean pdf = startsWith(head, PDF_MAGIC);
        String extension = extension(filename);
        switch (extension) {
            case "pdf":
                return pdf ? Optional.of(DataFormat.PDF) : Optional.empty();
            case "csv":
                return pdf ? Optional.empty() : Optional.of(DataFormat.CSV);
            case "json":
                return pdf ? Optional.empty() : Optional.of(DataFormat.JSON);
            default:
                if (pdf) {
                    return Optional.of(DataFormat.PDF);
                }
                return firstSignificantByte(head) == '[' ? Optional.of(DataFormat.JSON) : Optional.empty();
        }
    }

    private static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static byte[] head(Path file, int max) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(max);
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** Skips a UTF-8 BOM and whitespace. */
    private static int firstSignificantByte(byte[] bytes) {
        int i = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        while (i < bytes.length && Character.isWhitespace(bytes[i])) {
            i++;
        }
        return i < bytes.length ? bytes[i] : -1;
    }
}
```

`data/CsvNormalizer.java`:

```java
package com.agentstore.platform.data;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * CSV to JSONL of string-valued objects. Handles a BOM, quoting and embedded newlines. A short row's missing cells are
 * null; an over-long row keeps its extra cells under {@link Normalized#EXTRA_KEY} so the schema check can flag it.
 */
public final class CsvNormalizer {

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(true).build();

    private CsvNormalizer() {
    }

    public static Normalized normalize(Path in, Path out) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(in, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            skipBom(reader);
            try (CSVParser parser = FORMAT.parse(reader)) {
                Iterator<CSVRecord> records = parser.iterator();
                if (!records.hasNext()) {
                    throw new IngestionException("The CSV file is empty; it needs a header row");
                }
                List<String> columns = header(records.next());
                long rows = 0;
                while (records.hasNext()) {
                    CSVRecord record = records.next();
                    ObjectNode row = Jsonl.MAPPER.createObjectNode();
                    for (int i = 0; i < columns.size(); i++) {
                        if (i < record.size()) {
                            row.put(columns.get(i), record.get(i));
                        } else {
                            row.putNull(columns.get(i));
                        }
                    }
                    if (record.size() > columns.size()) {
                        ArrayNode extra = row.putArray(Normalized.EXTRA_KEY);
                        for (int i = columns.size(); i < record.size(); i++) {
                            extra.add(record.get(i));
                        }
                    }
                    writer.write(Jsonl.MAPPER.writeValueAsString(row));
                    writer.write('\n');
                    rows++;
                }
                return new Normalized(rows, columns);
            }
        } catch (CharacterCodingException e) {
            throw new IngestionException("The file is not valid UTF-8 text; export it as CSV (UTF-8)", e);
        } catch (UncheckedIOException e) {
            if (e.getCause() instanceof CharacterCodingException) {
                throw new IngestionException("The file is not valid UTF-8 text; export it as CSV (UTF-8)", e);
            }
            throw new IngestionException("The CSV file could not be read: " + e.getCause().getMessage(), e);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new IngestionException("The CSV file is malformed: " + e.getMessage(), e);
        }
    }

    private static List<String> header(CSVRecord record) {
        List<String> columns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String cell : record) {
            String name = cell.strip();
            if (name.isEmpty()) {
                throw new IngestionException("The header row has a blank column name");
            }
            if (!seen.add(name)) {
                throw new IngestionException("Duplicate column name in the header row: " + name);
            }
            columns.add(name);
        }
        return columns;
    }

    private static void skipBom(BufferedReader reader) throws IOException {
        reader.mark(1);
        if (reader.read() != '﻿') {
            reader.reset();
        }
    }
}
```

`data/JsonNormalizer.java`:

```java
package com.agentstore.platform.data;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/** A JSON file must be an array of objects; it becomes JSONL with the union of the objects' keys as its columns. */
public final class JsonNormalizer {

    private JsonNormalizer() {
    }

    public static Normalized normalize(Path in, Path out) throws IOException {
        Set<String> columns = new LinkedHashSet<>();
        long rows = 0;
        try (JsonParser parser = Jsonl.MAPPER.createParser(in.toFile());
             BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IngestionException("The JSON file must be an array of objects");
            }
            while (true) {
                JsonToken token = parser.nextToken();
                if (token == JsonToken.END_ARRAY) {
                    break;
                }
                if (token == null) {
                    throw new IngestionException("The JSON file ends before its array is closed");
                }
                if (token != JsonToken.START_OBJECT) {
                    throw new IngestionException("Item " + (rows + 1) + " is not an object; the file must be an array of objects");
                }
                JsonNode row = Jsonl.MAPPER.readTree(parser);
                row.fieldNames().forEachRemaining(columns::add);
                writer.write(Jsonl.MAPPER.writeValueAsString(row));
                writer.write('\n');
                rows++;
            }
            if (parser.nextToken() != null) {
                throw new IngestionException("The JSON file has content after its array");
            }
        } catch (JsonProcessingException e) {
            throw new IngestionException("The JSON file is not valid: " + e.getOriginalMessage(), e);
        }
        return new Normalized(rows, new ArrayList<>(columns));
    }
}
```

- [ ] **Step 6: Run to verify they pass**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='CsvNormalizerTest,JsonNormalizerTest,FormatDetectorTest,ObjectStorageRangeTest,ObjectStorageTest'`
Expected: PASS. If `CsvNormalizerTest.rejectsBytesThatAreNotUtf8` fails because commons-csv surfaces the decoding error in a different exception type, add that type to the `catch` chain and map it to the same UTF-8 message; the assertion (an `IngestionException` mentioning UTF-8) is the contract.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat(data): CSV/JSON normalizers, format detection and ranged object reads"
```

---

### Task 4: Datasets API — upload, list, delete, storage and quota

**Files:**
- Create (in `data/`): `DataApiException.java`, `DataApiErrors.java`, `Caller.java`, `DatasetResponse.java`, `DatasetService.java`, `DatasetController.java`
- Test: `data/DataFixtures.java`, `data/DatasetApiTest.java`, `data/DatasetUploadCapTest.java`, `identity/DataEndpointsAreNotKeyReachableTest.java`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces:
  - `DataApiException(HttpStatus, String code, String message[, Map<String,Object> extra])`, rendered as `{code, message, …extra}`; `DataApiErrors` also maps `IngestionException → 422 INVALID_FILE` and `MaxUploadSizeExceededException → 413 FILE_TOO_LARGE`.
  - `Caller.current() : JwtService.Claims`.
  - `DatasetService.upload(UUID orgId, UUID userId, String filename, InputStream content, String displayName, String sourceLabel) : Dataset`; `list(UUID orgId, boolean withUsage) : List<DatasetResponse>`; `delete(UUID orgId, UUID datasetId)`; `storage(UUID orgId) : StorageResponse`.
  - `DatasetResponse(UUID id, String displayName, String sourceLabel, DataFormat format, Long rowCount, long sizeBytes, List<String> columns, String sha256, String uploadedBy, Instant createdAt, List<Usage> usage)` with `Usage(UUID connectionId, String resource, String agent, String version, String access)`.
  - HTTP: `POST /api/v1/datasets` (multipart `file`, `displayName`, `sourceLabel`), `GET /api/v1/datasets?include=usage`, `DELETE /api/v1/datasets/{id}` (204), `GET /api/v1/orgs/{orgId}/storage` → `{usedBytes, limitBytes}`.
  - Test helpers `DataFixtures` (below), reused by Tasks 5–12.

- [ ] **Step 1: Write the test fixtures**

`backend/src/test/java/com/agentstore/platform/data/DataFixtures.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.UserRole;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Shared setup for data-plane tests: the data agent scenario, buyer tokens, uploads and pinned runs. */
@Component
public class DataFixtures {

    public static final List<String> GRANTED = List.of("bank_statement", "statement_pdf", "journal_entries", "audit_log");
    public static final String CSV = "date,narration,amount\n2026-01-05,Salary,50000.00\n2026-01-06,Rent,-12000.50\n";
    public static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".getBytes(StandardCharsets.UTF_8);

    public record RunningAgent(ExecutionFixtures.Scenario scenario, Run run, String token) {}

    private final ExecutionFixtures executionFixtures;
    private final RunRepository runRepository;
    private final JwtService jwtService;
    private final DatasetService datasetService;
    private final RunDatasetPinRepository pinRepository;

    public DataFixtures(ExecutionFixtures executionFixtures, RunRepository runRepository, JwtService jwtService,
                        DatasetService datasetService, RunDatasetPinRepository pinRepository) {
        this.executionFixtures = executionFixtures;
        this.runRepository = runRepository;
        this.jwtService = jwtService;
        this.datasetService = datasetService;
        this.pinRepository = pinRepository;
    }

    /** A LIVE agent whose manifest declares typed resources, a buyer with a wallet, and an ACTIVE connection. */
    public ExecutionFixtures.Scenario scenario() {
        return executionFixtures.liveWithManifest("valid-data-agent.json", GRANTED, new BigDecimal("500.00"),
                new BigDecimal("1000.00"));
    }

    public HttpHeaders bearer(Org org, UserRole role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), org.getId(), role));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    public HttpHeaders multipart(Org org, UserRole role) {
        HttpHeaders headers = bearer(org, role);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return headers;
    }

    public static MultiValueMap<String, Object> file(String filename, byte[] content, String displayName) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        body.add("displayName", displayName);
        body.add("sourceLabel", "HDFC export");
        return body;
    }

    /** Uploads through the service (real blobs, real rows), as a buyer with a synthetic user id. */
    public Dataset upload(Org org, String filename, byte[] content) {
        return datasetService.upload(org.getId(), UUID.randomUUID(), filename, new ByteArrayInputStream(content),
                filename, "test");
    }

    public Dataset upload(Org org, String filename, String content) {
        return upload(org, filename, content.getBytes(StandardCharsets.UTF_8));
    }

    /** A RUNNING run of the data agent with a raw token the test uses as the agent would. */
    public RunningAgent runningAgent() {
        return runningAgent(scenario());
    }

    public RunningAgent runningAgent(ExecutionFixtures.Scenario scenario) {
        String token = "rt-" + UUID.randomUUID();
        Run run = executionFixtures.run(scenario, RunStatus.RUNNING, "sbx-" + UUID.randomUUID(), token);
        run.setDeadlineAt(Instant.now().plusSeconds(600));
        return new RunningAgent(scenario, runRepository.save(run), token);
    }

    public void pin(Run run, String resource, Dataset dataset) {
        pinRepository.save(new RunDatasetPin(run.getId(), resource, dataset.getId(), dataset.getSha256()));
    }
}
```

- [ ] **Step 2: Write the failing tests**

`data/DatasetApiTest.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.manifest.DataFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetApiTest extends AbstractStorageIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    DataFixtures fx;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    DatasetRepository datasetRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    Org buyer() {
        return orgRepository.save(new Org(null, "Acme " + UUID.randomUUID(), OrgType.BUYER));
    }

    @SuppressWarnings("rawtypes")
    org.springframework.http.ResponseEntity<Map> upload(Org org, UserRole role, String filename, byte[] content) {
        return rest.exchange(url("/api/v1/datasets"), HttpMethod.POST,
                new HttpEntity<>(DataFixtures.file(filename, content, "HDFC Jan"), fx.multipart(org, role)), Map.class);
    }

    @SuppressWarnings("rawtypes")
    org.springframework.http.ResponseEntity<Map> exchange(HttpMethod method, String path, Org org, UserRole role) {
        return rest.exchange(url(path), method, new HttpEntity<>(null, fx.bearer(org, role)), Map.class);
    }

    @Test
    void uploadingACsvNormalizesItAndStoresBothBlobs() {
        Org org = buyer();

        var response = upload(org, UserRole.BUYER_ADMIN, "jan.csv", DataFixtures.CSV.getBytes(StandardCharsets.UTF_8));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body.get("format")).isEqualTo("CSV");
        assertThat(body.get("rowCount")).isEqualTo(2);
        assertThat(body.get("columns")).isEqualTo(List.of("date", "narration", "amount"));
        assertThat(body.get("displayName")).isEqualTo("HDFC Jan");
        assertThat(body.get("sourceLabel")).isEqualTo("HDFC export");
        Dataset stored = datasetRepository.findById(UUID.fromString((String) body.get("id"))).orElseThrow();
        assertThat(stored.getSizeBytes()).isEqualTo(DataFixtures.CSV.length());
        assertThat(stored.getSha256()).hasSize(64);
        assertThat(storage.size(stored.getStorageKey())).contains(stored.getSizeBytes());
        assertThat(storage.size(stored.getRecordsKey())).contains(stored.getRecordsSizeBytes());
        assertThat(stored.getPurpose()).isEqualTo(DatasetPurpose.BUYER_DATA);
    }

    @Test
    void aPdfIsStoredAsIsWithoutRows() {
        Org org = buyer();

        var response = upload(org, UserRole.BUYER_ADMIN, "statement.pdf", DataFixtures.PDF);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("format")).isEqualTo("PDF");
        assertThat(response.getBody().get("rowCount")).isNull();
        Dataset stored = datasetRepository.findById(UUID.fromString((String) response.getBody().get("id"))).orElseThrow();
        assertThat(stored.getRecordsKey()).isNull();
        assertThat(stored.getFormat()).isEqualTo(DataFormat.PDF);
    }

    @Test
    void anUnknownFormatIs415AndABadJsonShapeIs422() {
        Org org = buyer();

        var exe = upload(org, UserRole.BUYER_ADMIN, "tool.exe", new byte[]{'M', 'Z', 1, 2, 3});
        var json = upload(org, UserRole.BUYER_ADMIN, "obj.json", "{\"a\":1}".getBytes(StandardCharsets.UTF_8));

        assertThat(exe.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(exe.getBody().get("code")).isEqualTo("UNSUPPORTED_FORMAT");
        assertThat(json.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(json.getBody().get("code")).isEqualTo("INVALID_FILE");
        assertThat((String) json.getBody().get("message")).contains("array of objects");
        assertThat(datasetRepository.findByOwnerOrgIdAndPurposeAndDeletedAtIsNullOrderByCreatedAtDesc(
                org.getId(), DatasetPurpose.BUYER_DATA)).isEmpty();
    }

    @Test
    void exceedingTheOrgQuotaIs413AndLeavesNothingBehind() {
        Org org = buyer();
        org.setStorageLimitBytes(100);
        orgRepository.save(org);

        var response = upload(org, UserRole.BUYER_ADMIN, "big.csv", DataFixtures.CSV.getBytes(StandardCharsets.UTF_8));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().get("code")).isEqualTo("QUOTA_EXCEEDED");
        assertThat(datasetRepository.usedBytes(org.getId())).isZero();
    }

    @Test
    void onlyABuyerAdminMayUploadAndABlankNameIsRejected() {
        Org org = buyer();

        assertThat(upload(org, UserRole.BUYER_APPROVER, "a.csv", DataFixtures.CSV.getBytes(StandardCharsets.UTF_8))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var body = DataFixtures.file("a.csv", DataFixtures.CSV.getBytes(StandardCharsets.UTF_8), "  ");
        var blank = rest.exchange(url("/api/v1/datasets"), HttpMethod.POST,
                new HttpEntity<>(body, fx.multipart(org, UserRole.BUYER_ADMIN)), Map.class);
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blank.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void listingShowsOnlyTheCallersOrgAndBothBuyerRolesCanRead() {
        Org mine = buyer();
        Org theirs = buyer();
        upload(mine, UserRole.BUYER_ADMIN, "mine.csv", DataFixtures.CSV.getBytes(StandardCharsets.UTF_8));
        upload(theirs, UserRole.BUYER_ADMIN, "theirs.csv", DataFixtures.CSV.getBytes(StandardCharsets.UTF_8));

        var response = rest.exchange(url("/api/v1/datasets?include=usage"), HttpMethod.GET,
                new HttpEntity<>(null, fx.bearer(mine, UserRole.BUYER_APPROVER)), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        Map<?, ?> row = (Map<?, ?>) response.getBody().get(0);
        assertThat(row.get("usage")).isEqualTo(List.of());
    }

    @Test
    void deletingUnbindsHidesAndSchedulesAnImmediatePurge() {
        Org org = buyer();
        Dataset dataset = fx.upload(org, "a.csv", DataFixtures.CSV);

        var response = exchange(HttpMethod.DELETE, "/api/v1/datasets/" + dataset.getId(), org, UserRole.BUYER_ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Dataset after = datasetRepository.findById(dataset.getId()).orElseThrow();
        assertThat(after.getDeletedAt()).isNotNull();
        assertThat(after.getPurgeAfter()).isNotNull().isBeforeOrEqualTo(java.time.Instant.now());
        assertThat(rest.exchange(url("/api/v1/datasets"), HttpMethod.GET,
                new HttpEntity<>(null, fx.bearer(org, UserRole.BUYER_ADMIN)), List.class).getBody()).isEmpty();
        assertThat(exchange(HttpMethod.DELETE, "/api/v1/datasets/" + dataset.getId(), org, UserRole.BUYER_ADMIN)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deletingAnotherOrgsDatasetIs404() {
        Org mine = buyer();
        Org theirs = buyer();
        Dataset dataset = fx.upload(theirs, "a.csv", DataFixtures.CSV);

        var response = exchange(HttpMethod.DELETE, "/api/v1/datasets/" + dataset.getId(), mine, UserRole.BUYER_ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(datasetRepository.findById(dataset.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    void aFileAnOpenRunReadsCannotBeDeleted() {
        ExecutionFixtures.Scenario scenario = fx.scenario();
        Org org = scenario.buyer();
        Dataset dataset = fx.upload(org, "a.csv", DataFixtures.CSV);
        var agent = fx.runningAgent(scenario);
        fx.pin(agent.run(), "bank_statement", dataset);

        var response = exchange(HttpMethod.DELETE, "/api/v1/datasets/" + dataset.getId(), org, UserRole.BUYER_ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("DATASET_IN_USE");
        assertThat(datasetRepository.findById(dataset.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    void storageReportsUsageAndIsOnlyForTheCallersOwnOrg() {
        Org org = buyer();
        Dataset dataset = fx.upload(org, "a.csv", DataFixtures.CSV);

        var response = exchange(HttpMethod.GET, "/api/v1/orgs/" + org.getId() + "/storage", org, UserRole.BUYER_APPROVER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) response.getBody().get("usedBytes")).longValue())
                .isEqualTo(dataset.getSizeBytes() + dataset.getRecordsSizeBytes());
        assertThat(((Number) response.getBody().get("limitBytes")).longValue()).isEqualTo(5L * 1024 * 1024 * 1024);
        assertThat(exchange(HttpMethod.GET, "/api/v1/orgs/" + UUID.randomUUID() + "/storage", org, UserRole.BUYER_ADMIN)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
```

`data/DatasetUploadCapTest.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The 50 MB cap is the multipart limit; shrinking it here keeps the test small. */
@TestPropertySource(properties = {"agentstore.data.max-file-size=2KB", "agentstore.data.max-request-size=4KB"})
class DatasetUploadCapTest extends AbstractStorageIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    DataFixtures fx;
    @Autowired
    OrgRepository orgRepository;

    @Test
    void aFileOverTheCapIs413() {
        Org org = orgRepository.save(new Org(null, "Acme " + UUID.randomUUID(), OrgType.BUYER));
        byte[] big = ("a,b\n" + "1,2\n".repeat(1500)).getBytes();

        var response = rest.exchange("http://localhost:" + port + "/api/v1/datasets", HttpMethod.POST,
                new HttpEntity<>(DataFixtures.file("big.csv", big, "Big"), fx.multipart(org, UserRole.BUYER_ADMIN)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().get("code")).isEqualTo("FILE_TOO_LARGE");
    }
}
```

`identity/DataEndpointsAreNotKeyReachableTest.java`:

```java
package com.agentstore.platform.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Developer API keys stay on the publishing allowlist; the buyer data plane is unreachable by them. */
class DataEndpointsAreNotKeyReachableTest {

    @Test
    void keysCannotTouchDatasetsBindingsOrOutputs() {
        assertThat(ApiKeyScope.allows("GET", "/api/v1/datasets")).isFalse();
        assertThat(ApiKeyScope.allows("POST", "/api/v1/datasets")).isFalse();
        assertThat(ApiKeyScope.allows("DELETE", "/api/v1/datasets/abc")).isFalse();
        assertThat(ApiKeyScope.allows("PUT", "/api/v1/connections/abc/bindings/bank_statement")).isFalse();
        assertThat(ApiKeyScope.allows("GET", "/api/v1/runs/abc/outputs")).isFalse();
        assertThat(ApiKeyScope.allows("GET", "/api/v1/orgs/abc/storage")).isFalse();
    }
}
```

- [ ] **Step 3: Run to verify they fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='DatasetApiTest,DatasetUploadCapTest,DataEndpointsAreNotKeyReachableTest'`
Expected: COMPILE FAIL (`DatasetService` etc. missing).

- [ ] **Step 4: Implement the API**

`data/DataApiException.java`:

```java
package com.agentstore.platform.data;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** A buyer-API failure with a stable code; {@code extra} fields are added to the response body. */
public class DataApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> extra;

    public DataApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public DataApiException(HttpStatus status, String code, String message, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.code = code;
        this.extra = extra;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> extra() {
        return extra;
    }
}
```

`data/DataApiErrors.java`:

```java
package com.agentstore.platform.data;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The app hides {@code ResponseStatusException} reasons, so the data plane's buyer endpoints answer
 * {@code {code, message, ...}} explicitly. It only fires for these exception types.
 */
@RestControllerAdvice
public class DataApiErrors {

    @ExceptionHandler(DataApiException.class)
    ResponseEntity<Map<String, Object>> api(DataApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("message", e.getMessage());
        body.putAll(e.extra());
        return ResponseEntity.status(e.status()).body(body);
    }

    @ExceptionHandler(IngestionException.class)
    ResponseEntity<Map<String, Object>> ingestion(IngestionException e) {
        return api(new DataApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILE", e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        return api(new DataApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                "The file is larger than the upload limit"));
    }
}
```

`data/Caller.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.identity.JwtService;
import org.springframework.security.core.context.SecurityContextHolder;

/** The authenticated buyer behind a /api/v1 call. */
public final class Caller {

    private Caller() {
    }

    public static JwtService.Claims current() {
        return (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
```

`data/DatasetResponse.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.manifest.DataFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code usage} is present only when the caller asked for {@code include=usage}. */
public record DatasetResponse(UUID id, String displayName, String sourceLabel, DataFormat format, Long rowCount,
                              long sizeBytes, List<String> columns, String sha256, String uploadedBy,
                              Instant createdAt, @JsonInclude(JsonInclude.Include.NON_NULL) List<Usage> usage) {

    public record Usage(UUID connectionId, String resource, String agent, String version, String access) {
    }

    static DatasetResponse from(Dataset d, String uploadedBy, List<Usage> usage) {
        return new DatasetResponse(d.getId(), d.getDisplayName(), d.getSourceLabel(), d.getFormat(), d.getRowCount(),
                d.getSizeBytes(), d.columns(), d.getSha256(), uploadedBy, d.getCreatedAt(), usage);
    }
}
```

`data/DatasetService.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.execution.Connection;
import com.agentstore.platform.execution.ConnectionRepository;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.User;
import com.agentstore.platform.identity.UserRepository;
import com.agentstore.platform.manifest.DataFormat;
import com.agentstore.platform.storage.ObjectStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class DatasetService {

    public record StorageResponse(long usedBytes, long limitBytes) {}

    private final DatasetRepository datasets;
    private final DatasetBindingRepository bindings;
    private final RunDatasetPinRepository pins;
    private final OrgRepository orgs;
    private final UserRepository users;
    private final ConnectionRepository connections;
    private final ObjectStorage storage;
    private final TransactionTemplate tx;

    public DatasetService(DatasetRepository datasets, DatasetBindingRepository bindings, RunDatasetPinRepository pins,
                          OrgRepository orgs, UserRepository users, ConnectionRepository connections,
                          ObjectStorage storage, TransactionTemplate tx) {
        this.datasets = datasets;
        this.bindings = bindings;
        this.pins = pins;
        this.orgs = orgs;
        this.users = users;
        this.connections = connections;
        this.storage = storage;
        this.tx = tx;
    }

    /**
     * Streams the upload to a temp file (hashing as it goes), normalizes it, writes the blobs, then registers the row
     * under the org's row lock so two uploads cannot both slip under the quota. Blobs are removed if registering fails.
     */
    public Dataset upload(UUID orgId, UUID userId, String filename, InputStream content, String displayName,
                          String sourceLabel) {
        if (displayName == null || displayName.isBlank()) {
            throw new DataApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "displayName must not be blank");
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("dataset-upload");
            Path original = dir.resolve("original");
            MessageDigest digest = sha256();
            try (DigestInputStream hashing = new DigestInputStream(content, digest)) {
                Files.copy(hashing, original, StandardCopyOption.REPLACE_EXISTING);
            }
            long size = Files.size(original);
            DataFormat format = FormatDetector.detect(filename, original).orElseThrow(() -> new DataApiException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FORMAT", "Upload a CSV, JSON or PDF file"));

            Path records = dir.resolve("records.jsonl");
            Normalized normalized = null;
            long recordsSize = 0;
            if (format == DataFormat.CSV) {
                normalized = CsvNormalizer.normalize(original, records);
            } else if (format == DataFormat.JSON) {
                normalized = JsonNormalizer.normalize(original, records);
            }
            if (normalized != null) {
                recordsSize = Files.size(records);
            }

            String base = "datasets/" + orgId + "/" + UUID.randomUUID() + "/";
            String originalKey = base + "original";
            String recordsKey = normalized == null ? null : base + "records.jsonl";
            storage.putFile(originalKey, original);
            if (recordsKey != null) {
                storage.putFile(recordsKey, records);
            }
            try {
                Dataset dataset = new Dataset();
                dataset.setOwnerOrgId(orgId);
                dataset.setPurpose(DatasetPurpose.BUYER_DATA);
                dataset.setDisplayName(displayName.strip());
                dataset.setSourceLabel(sourceLabel == null || sourceLabel.isBlank() ? null : sourceLabel.strip());
                dataset.setUploadedByUserId(userId);
                dataset.setFormat(format);
                dataset.setStorageKey(originalKey);
                dataset.setRecordsKey(recordsKey);
                dataset.setSha256(HexFormat.of().formatHex(digest.digest()));
                dataset.setSizeBytes(size);
                dataset.setRecordsSizeBytes(recordsSize);
                dataset.setRowCount(normalized == null ? null : normalized.rowCount());
                dataset.setColumns(normalized == null ? List.of() : normalized.columns());
                long incoming = size + recordsSize;
                return tx.execute(status -> {
                    Org org = orgs.findByIdForUpdate(orgId).orElseThrow();
                    if (datasets.usedBytes(orgId) + incoming > org.getStorageLimitBytes()) {
                        throw new DataApiException(HttpStatus.PAYLOAD_TOO_LARGE, "QUOTA_EXCEEDED",
                                "This upload would take the workspace over its storage limit");
                    }
                    return datasets.save(dataset);
                });
            } catch (RuntimeException e) {
                storage.deleteQuietly(originalKey);
                if (recordsKey != null) {
                    storage.deleteQuietly(recordsKey);
                }
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteQuietly(dir);
        }
    }

    public List<DatasetResponse> list(UUID orgId, boolean withUsage) {
        List<Dataset> rows = datasets.findByOwnerOrgIdAndPurposeAndDeletedAtIsNullOrderByCreatedAtDesc(orgId,
                DatasetPurpose.BUYER_DATA);
        Map<UUID, List<DatasetResponse.Usage>> usage = withUsage ? usageByDataset(rows) : Map.of();
        Map<UUID, String> uploaders = new HashMap<>();
        return rows.stream().map(d -> DatasetResponse.from(d,
                uploaders.computeIfAbsent(d.getUploadedByUserId() == null ? new UUID(0, 0) : d.getUploadedByUserId(),
                        id -> users.findById(id).map(User::getFullName).orElse(null)),
                withUsage ? usage.getOrDefault(d.getId(), List.of()) : null)).toList();
    }

    private Map<UUID, List<DatasetResponse.Usage>> usageByDataset(List<Dataset> rows) {
        List<DatasetBinding> all = bindings.findByDatasetIdIn(rows.stream().map(Dataset::getId).toList()).stream()
                .filter(b -> b.getOwnerType() == BindingOwnerType.CONNECTION).toList();
        Map<UUID, Connection> byId = connections.findAllById(all.stream().map(DatasetBinding::getOwnerId).toList())
                .stream().collect(Collectors.toMap(Connection::getId, Function.identity()));
        return all.stream().filter(b -> byId.containsKey(b.getOwnerId())).collect(Collectors.groupingBy(
                DatasetBinding::getDatasetId, Collectors.mapping(b -> {
                    Connection c = byId.get(b.getOwnerId());
                    return new DatasetResponse.Usage(c.getId(), b.getResourceName(), c.getAgent().getName(),
                            c.getAgentVersion() == null ? null : c.getAgentVersion().getVersionNumber(), "READ");
                }, Collectors.toList())));
    }

    /**
     * Unbinds everywhere and schedules the purge for right now (the retention job removes the blobs within about an
     * hour): a 30-day window on an explicit delete would be a privacy liability.
     */
    @Transactional
    public void delete(UUID orgId, UUID datasetId) {
        Dataset dataset = datasets.findByIdForUpdate(datasetId)
                .filter(d -> d.getOwnerOrgId().equals(orgId) && d.getDeletedAt() == null && d.getPurgedAt() == null)
                .orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Dataset not found"));
        if (pins.pinnedByOpenRun(datasetId)) {
            throw new DataApiException(HttpStatus.CONFLICT, "DATASET_IN_USE",
                    "A run that has not finished is reading this file; delete it once the run ends");
        }
        bindings.deleteByDatasetId(datasetId);
        Instant now = Instant.now();
        dataset.setDeletedAt(now);
        dataset.setPurgeAfter(now);
    }

    public StorageResponse storage(UUID orgId) {
        Org org = orgs.findById(orgId).orElseThrow();
        return new StorageResponse(datasets.usedBytes(orgId), org.getStorageLimitBytes());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // a temp file left in the OS temp dir is not worth failing the request over
        }
    }
}
```

`data/DatasetController.java`:

```java
package com.agentstore.platform.data;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DatasetController {

    private final DatasetService datasetService;

    public DatasetController(DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    @PostMapping(value = "/datasets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    public DatasetResponse upload(@RequestParam("file") MultipartFile file,
                                  @RequestParam(value = "displayName", required = false) String displayName,
                                  @RequestParam(value = "sourceLabel", required = false) String sourceLabel)
            throws IOException {
        var claims = Caller.current();
        try (InputStream in = file.getInputStream()) {
            Dataset dataset = datasetService.upload(claims.orgId(), claims.userId(), file.getOriginalFilename(), in,
                    displayName, sourceLabel);
            return DatasetResponse.from(dataset, null, null);
        }
    }

    @GetMapping("/datasets")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public List<DatasetResponse> list(@RequestParam(value = "include", required = false) String include) {
        return datasetService.list(Caller.current().orgId(), "usage".equals(include));
    }

    @DeleteMapping("/datasets/{id}")
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        datasetService.delete(Caller.current().orgId(), id);
    }

    @GetMapping("/orgs/{orgId}/storage")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public DatasetService.StorageResponse storage(@PathVariable UUID orgId) {
        if (!Caller.current().orgId().equals(orgId)) {
            throw new DataApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not your workspace");
        }
        return datasetService.storage(orgId);
    }
}
```

The blank-name test expects `VALIDATION_FAILED`; `DatasetService.upload` already throws it.

- [ ] **Step 5: Run to verify they pass**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='DatasetApiTest,DatasetUploadCapTest,DataEndpointsAreNotKeyReachableTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat(data): datasets API with upload, list, delete, storage and org quota"
```

---

### Task 5: `SchemaChecker` and the check endpoint

**Files:**
- Create (in `data/`): `ValueCoercer.java`, `SchemaChecker.java`, `DatasetCheckService.java`, `ResourceLookup.java`
- Modify: `data/DatasetController.java`
- Test: `data/ValueCoercerTest.java`, `data/SchemaCheckerTest.java`, `data/DatasetCheckEndpointTest.java`

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces:
  - `ValueCoercer.coerce(ColumnType, Object) : Object` (returns `String`, `BigDecimal`, `Long`, `Boolean` or null; throws `ValueCoercer.NotCoercible`), `ValueCoercer.canCoerce(ColumnType, Object) : boolean`.
  - `SchemaChecker.check(ResourceSchema schema /*nullable*/, List<String> columns, Iterator<Map<String,Object>> rows) : CheckResult`; `record RowError(long row, String column, String message)`; `record CheckResult(boolean ok, long rowCount, boolean columnsMatch, List<String> missingColumns, long rowErrorCount, List<RowError> rowErrors)` with `static CheckResult formatMismatch(String)` and `static CheckResult file()` (a PDF that passes). `SchemaChecker.MAX_ERRORS = 50`.
  - `DatasetCheckService.check(Dataset, ResourceDecl /*nullable = untyped*/) : CheckResult`.
  - `ResourceLookup.requireReadable(Manifest, String resource) : ResourceDecl /*nullable*/` (throws 422 `RESOURCE_NOT_BINDABLE`).
  - HTTP `POST /api/v1/datasets/{id}/check` body `{agentVersionId, resource}` → `CheckResult` JSON.

- [ ] **Step 1: Write the failing tests**

`data/ValueCoercerTest.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.manifest.ColumnType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValueCoercerTest {

    @Test
    void numbersCoerceFromStringsAndJsonNumbers() {
        assertThat(ValueCoercer.coerce(ColumnType.NUMBER, "12.50")).isEqualTo(new BigDecimal("12.50"));
        assertThat(ValueCoercer.coerce(ColumnType.NUMBER, " -3 ")).isEqualTo(new BigDecimal("-3"));
        assertThat(ValueCoercer.coerce(ColumnType.NUMBER, 7)).isEqualTo(new BigDecimal("7"));
        assertThat(ValueCoercer.coerce(ColumnType.NUMBER, new BigDecimal("1.10"))).isEqualTo(new BigDecimal("1.10"));
        assertThat(ValueCoercer.canCoerce(ColumnType.NUMBER, "abc")).isFalse();
        assertThat(ValueCoercer.canCoerce(ColumnType.NUMBER, "1,234.5")).isFalse();
        assertThat(ValueCoercer.canCoerce(ColumnType.NUMBER, "NaN")).isFalse();
        assertThat(ValueCoercer.canCoerce(ColumnType.NUMBER, Double.NaN)).isFalse();
        assertThat(ValueCoercer.canCoerce(ColumnType.NUMBER, true)).isFalse();
    }

    @Test
    void integersMustBeWholeAndFitALong() {
        assertThat(ValueCoercer.coerce(ColumnType.INTEGER, "42")).isEqualTo(42L);
        assertThat(ValueCoercer.coerce(ColumnType.INTEGER, "12.0")).isEqualTo(12L);
        assertThat(ValueCoercer.canCoerce(ColumnType.INTEGER, "12.5")).isFalse();
        assertThat(ValueCoercer.canCoerce(ColumnType.INTEGER, "99999999999999999999")).isFalse();
    }

    @Test
    void booleansAndDates() {
        assertThat(ValueCoercer.coerce(ColumnType.BOOLEAN, "TRUE")).isEqualTo(true);
        assertThat(ValueCoercer.coerce(ColumnType.BOOLEAN, false)).isEqualTo(false);
        assertThat(ValueCoercer.canCoerce(ColumnType.BOOLEAN, "yes")).isFalse();
        assertThat(ValueCoercer.coerce(ColumnType.DATE, "2026-02-28")).isEqualTo("2026-02-28");
        assertThat(ValueCoercer.canCoerce(ColumnType.DATE, "2026-02-30")).isFalse();
        assertThat(ValueCoercer.canCoerce(ColumnType.DATE, "05/01/2026")).isFalse();
        assertThat(ValueCoercer.canCoerce(ColumnType.DATE, 20260105)).isFalse();
    }

    @Test
    void nullAndBlankAreNullForTypedColumnsButKeptForStrings() {
        assertThat(ValueCoercer.coerce(ColumnType.NUMBER, null)).isNull();
        assertThat(ValueCoercer.coerce(ColumnType.NUMBER, "  ")).isNull();
        assertThat(ValueCoercer.coerce(ColumnType.DATE, "")).isNull();
        assertThat(ValueCoercer.coerce(ColumnType.STRING, "")).isEqualTo("");
        assertThat(ValueCoercer.coerce(ColumnType.STRING, 5)).isEqualTo("5");
        assertThatThrownBy(() -> ValueCoercer.coerce(ColumnType.STRING, java.util.Map.of()))
                .isInstanceOf(ValueCoercer.NotCoercible.class);
    }
}
```

`data/SchemaCheckerTest.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.manifest.ColumnType;
import com.agentstore.platform.manifest.ResourceSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaCheckerTest {

    static final ResourceSchema SCHEMA = new ResourceSchema(List.of("date", "narration", "amount"),
            Map.of("date", ColumnType.DATE, "amount", ColumnType.NUMBER));
    static final List<String> COLUMNS = List.of("date", "narration", "amount");

    static Map<String, Object> row(Object date, Object narration, Object amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("date", date);
        row.put("narration", narration);
        row.put("amount", amount);
        return row;
    }

    SchemaChecker.CheckResult check(ResourceSchema schema, List<String> columns, List<Map<String, Object>> rows) {
        return SchemaChecker.check(schema, columns, rows.iterator());
    }

    @Test
    void aMatchingFilePasses() {
        var result = check(SCHEMA, COLUMNS, List.of(row("2026-01-05", "Salary", "50000.00"), row("2026-01-06", "Rent", "-1.5")));

        assertThat(result.ok()).isTrue();
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.columnsMatch()).isTrue();
        assertThat(result.missingColumns()).isEmpty();
        assertThat(result.rowErrors()).isEmpty();
    }

    @Test
    void reportsMissingRequiredColumns() {
        var result = check(SCHEMA, List.of("date", "amount"), List.of(row("2026-01-05", null, "1")));

        assertThat(result.ok()).isFalse();
        assertThat(result.columnsMatch()).isFalse();
        assertThat(result.missingColumns()).containsExactly("narration");
    }

    @Test
    void reportsRowAndColumnForEveryBadValueButOnlyTheFirst50() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            rows.add(row("2026-01-05", "n", "abc"));
        }
        var result = check(SCHEMA, COLUMNS, rows);

        assertThat(result.ok()).isFalse();
        assertThat(result.rowErrorCount()).isEqualTo(60);
        assertThat(result.rowErrors()).hasSize(50);
        assertThat(result.rowErrors().get(0)).isEqualTo(new SchemaChecker.RowError(1, "amount",
                "'abc' is not a valid number"));
        assertThat(result.rowErrors().get(49).row()).isEqualTo(50);
    }

    @Test
    void anOverLongCsvRowAndAMissingRequiredKeyAreRowErrors() {
        Map<String, Object> longRow = row("2026-01-05", "n", "1");
        longRow.put(Normalized.EXTRA_KEY, List.of("x"));
        Map<String, Object> missingKey = new LinkedHashMap<>();
        missingKey.put("date", "2026-01-05");
        missingKey.put("amount", "1");     // 'narration' is a column of the file but absent from this JSON row

        var result = check(SCHEMA, COLUMNS, List.of(longRow, missingKey));

        assertThat(result.rowErrors()).extracting(SchemaChecker.RowError::row).containsExactly(1L, 2L);
        assertThat(result.rowErrors().get(0).message()).contains("more cells than the header");
        assertThat(result.rowErrors().get(1).column()).isEqualTo("narration");
    }

    @Test
    void anEmptyFileFailsBecauseItHasNoRows() {
        var result = check(SCHEMA, COLUMNS, List.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.rowCount()).isZero();
        assertThat(result.rowErrors()).extracting(SchemaChecker.RowError::message).containsExactly("The file has no data rows");
    }

    @Test
    void anUntypedResourceOnlyNeedsRows() {
        assertThat(check(null, List.of("anything"), List.of(Map.of("anything", "x"))).ok()).isTrue();
        assertThat(check(null, List.of("anything"), List.of()).ok()).isFalse();
    }

    @Test
    void nullAndBlankCellsAreFineForTypedColumns() {
        assertThat(check(SCHEMA, COLUMNS, List.of(row("2026-01-05", "n", null), row("", "n", " "))).ok()).isTrue();
    }

    @Test
    void longValuesAreTruncatedInTheMessage() {
        var result = check(SCHEMA, COLUMNS, List.of(row("2026-01-05", "n", "x".repeat(500))));

        assertThat(result.rowErrors().get(0).message().length()).isLessThan(100);
    }
}
```

`data/DatasetCheckEndpointTest.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetCheckEndpointTest extends AbstractStorageIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    DataFixtures fx;
    @Autowired
    DatasetBindingRepository bindingRepository;

    @SuppressWarnings("rawtypes")
    org.springframework.http.ResponseEntity<Map> check(ExecutionFixtures.Scenario s, Dataset d, String resource) {
        return check(s, s.buyer(), d.getId(), s.version().getId(), resource);
    }

    @SuppressWarnings("rawtypes")
    org.springframework.http.ResponseEntity<Map> check(ExecutionFixtures.Scenario s, com.agentstore.platform.identity.Org caller,
                                                       UUID datasetId, UUID versionId, String resource) {
        return rest.exchange("http://localhost:" + port + "/api/v1/datasets/" + datasetId + "/check", HttpMethod.POST,
                new HttpEntity<>(Map.of("agentVersionId", versionId, "resource", resource),
                        fx.bearer(caller, UserRole.BUYER_APPROVER)), Map.class);
    }

    @Test
    void aMatchingCsvPassesAndNothingIsSaved() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV);

        var response = check(s, d, "bank_statement");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("ok")).isEqualTo(true);
        assertThat(response.getBody().get("rowCount")).isEqualTo(2);
        assertThat(response.getBody().get("columnsMatch")).isEqualTo(true);
        assertThat(bindingRepository.existsByDatasetId(d.getId())).isFalse();
    }

    @Test
    void aMissingColumnIsReported() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "jan.csv", "date,amount\n2026-01-05,10\n");

        var body = check(s, d, "bank_statement").getBody();

        assertThat(body.get("ok")).isEqualTo(false);
        assertThat(body.get("columnsMatch")).isEqualTo(false);
        assertThat(body.get("missingColumns")).isEqualTo(List.of("narration"));
    }

    @Test
    void aBadValueNamesTheRowAndColumn() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "jan.csv", "date,narration,amount\n2026-01-05,a,10\n2026-01-06,b,abc\n");

        var body = check(s, d, "bank_statement").getBody();

        Map<?, ?> error = (Map<?, ?>) ((List<?>) body.get("rowErrors")).get(0);
        assertThat(error.get("row")).isEqualTo(2);
        assertThat(error.get("column")).isEqualTo("amount");
    }

    @Test
    void anOverLongRowFailsTheCheck() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "jan.csv", "date,narration,amount\n2026-01-05,a,10,extra\n");

        var body = check(s, d, "bank_statement").getBody();

        assertThat(body.get("ok")).isEqualTo(false);
        assertThat(body.get("rowErrorCount")).isEqualTo(1);
    }

    @Test
    void onlyTheFirstFiftyRowErrorsAreReturnedWithTheTotal() {
        var s = fx.scenario();
        StringBuilder csv = new StringBuilder("date,narration,amount\n");
        for (int i = 0; i < 60; i++) {
            csv.append("2026-01-05,n,abc\n");
        }
        Dataset d = fx.upload(s.buyer(), "jan.csv", csv.toString());

        var body = check(s, d, "bank_statement").getBody();

        assertThat((List<?>) body.get("rowErrors")).hasSize(50);
        assertThat(body.get("rowErrorCount")).isEqualTo(60);
    }

    @Test
    void theKindOfFileMustMatchThePdfOrTabularResource() {
        var s = fx.scenario();
        Dataset csv = fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV);
        Dataset pdf = fx.upload(s.buyer(), "jan.pdf", DataFixtures.PDF);

        assertThat(check(s, pdf, "bank_statement").getBody().get("ok")).isEqualTo(false);
        assertThat(check(s, csv, "statement_pdf").getBody().get("ok")).isEqualTo(false);
        assertThat(check(s, pdf, "statement_pdf").getBody().get("ok")).isEqualTo(true);
    }

    @Test
    void aWriteResourceIsNotBindableSoItIsNotCheckable() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV);

        var response = check(s, d, "journal_entries");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code")).isEqualTo("RESOURCE_NOT_BINDABLE");
    }

    @Test
    void anotherOrgsDatasetIs404() {
        var s = fx.scenario();
        var other = fx.scenario();
        Dataset d = fx.upload(other.buyer(), "jan.csv", DataFixtures.CSV);

        var response = check(s, s.buyer(), d.getId(), s.version().getId(), "bank_statement");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anUnknownAgentVersionIs404() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV);

        var response = check(s, s.buyer(), d.getId(), UUID.randomUUID(), "bank_statement");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='ValueCoercerTest,SchemaCheckerTest,DatasetCheckEndpointTest'`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement**

`data/ValueCoercer.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.manifest.ColumnType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Turns a stored cell (a string from CSV, a JSON value from a JSON file) into the declared type. Null and, for typed
 * columns, blank cells are null: "required" means the column is present, not that every cell has a value.
 */
public final class ValueCoercer {

    public static final class NotCoercible extends RuntimeException {
        NotCoercible(String message) {
            super(message, null, false, false);
        }
    }

    private ValueCoercer() {
    }

    public static Object coerce(ColumnType type, Object value) {
        if (value == null) {
            return null;
        }
        if (type != ColumnType.STRING && value instanceof String s && s.isBlank()) {
            return null;
        }
        return switch (type) {
            case STRING -> string(value);
            case NUMBER -> decimal(value, type);
            case INTEGER -> integer(value);
            case BOOLEAN -> bool(value);
            case DATE -> date(value);
        };
    }

    public static boolean canCoerce(ColumnType type, Object value) {
        try {
            coerce(type, value);
            return true;
        } catch (NotCoercible e) {
            return false;
        }
    }

    private static String string(Object value) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        throw new NotCoercible("not a string");
    }

    private static BigDecimal decimal(Object value, ColumnType type) {
        try {
            if (value instanceof BigDecimal b) {
                return b;
            }
            if (value instanceof Number n) {
                return new BigDecimal(n.toString());
            }
            if (value instanceof String s) {
                return new BigDecimal(s.strip());
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        throw new NotCoercible("not a valid " + type.wire());
    }

    private static Long integer(Object value) {
        try {
            return decimal(value, ColumnType.INTEGER).longValueExact();
        } catch (ArithmeticException e) {
            throw new NotCoercible("not a valid integer");
        }
    }

    private static Boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            String t = s.strip();
            if (t.equalsIgnoreCase("true")) {
                return true;
            }
            if (t.equalsIgnoreCase("false")) {
                return false;
            }
        }
        throw new NotCoercible("not a valid boolean");
    }

    private static String date(Object value) {
        if (value instanceof String s) {
            try {
                LocalDate.parse(s.strip());
                return s.strip();
            } catch (DateTimeParseException e) {
                // fall through
            }
        }
        throw new NotCoercible("not a valid date");
    }
}
```

`data/SchemaChecker.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.manifest.ColumnType;
import com.agentstore.platform.manifest.ResourceSchema;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Validates rows against a resource's schema (or, with none, only that there are rows). Pure: it reads a row iterator
 * and saves nothing. Rows are numbered from 1, not counting the header; row 0 means "the file as a whole".
 */
public final class SchemaChecker {

    public static final int MAX_ERRORS = 50;
    private static final int MAX_VALUE_CHARS = 40;

    public record RowError(long row, String column, String message) {
    }

    public record CheckResult(boolean ok, long rowCount, boolean columnsMatch, List<String> missingColumns,
                              long rowErrorCount, List<RowError> rowErrors) {

        /** The file is the wrong kind for the resource (a PDF where rows are expected, or the reverse). */
        public static CheckResult formatMismatch(String message) {
            return new CheckResult(false, 0, true, List.of(), 1, List.of(new RowError(0, null, message)));
        }

        /** A PDF for a resource that reads a document: nothing further to check. */
        public static CheckResult file() {
            return new CheckResult(true, 0, true, List.of(), 0, List.of());
        }
    }

    private SchemaChecker() {
    }

    public static CheckResult check(ResourceSchema schema, List<String> columns, Iterator<Map<String, Object>> rows) {
        List<String> required = schema == null ? List.of() : schema.required();
        List<String> missing = required.stream().filter(c -> !columns.contains(c)).toList();
        List<RowError> errors = new ArrayList<>();
        long errorCount = 0;
        long n = 0;
        while (rows.hasNext()) {
            n++;
            Map<String, Object> row = rows.next();
            if (row.containsKey(Normalized.EXTRA_KEY)) {
                errorCount++;
                add(errors, n, null, "Row has more cells than the header");
            }
            if (schema == null) {
                continue;
            }
            for (String column : required) {
                if (columns.contains(column) && !row.containsKey(column)) {
                    errorCount++;
                    add(errors, n, column, "Missing required column '" + column + "'");
                }
            }
            for (Map.Entry<String, ColumnType> property : schema.properties().entrySet()) {
                String column = property.getKey();
                if (row.containsKey(column) && !ValueCoercer.canCoerce(property.getValue(), row.get(column))) {
                    errorCount++;
                    add(errors, n, column, "'" + shorten(row.get(column)) + "' is not a valid "
                            + property.getValue().wire());
                }
            }
        }
        if (n == 0) {
            errorCount++;
            errors.add(new RowError(0, null, "The file has no data rows"));
        }
        boolean columnsMatch = missing.isEmpty();
        return new CheckResult(columnsMatch && errorCount == 0, n, columnsMatch, missing, errorCount, List.copyOf(errors));
    }

    private static void add(List<RowError> errors, long row, String column, String message) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(new RowError(row, column, message));
        }
    }

    private static String shorten(Object value) {
        String text = String.valueOf(value);
        return text.length() <= MAX_VALUE_CHARS ? text : text.substring(0, MAX_VALUE_CHARS) + "…";
    }
}
```

`data/ResourceLookup.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ResourceDecl;
import com.agentstore.platform.manifest.ScopeType;
import org.springframework.http.HttpStatus;

/** Resolves a resource name against an agent version's manifest for the buyer-side check and bind. */
final class ResourceLookup {

    private ResourceLookup() {
    }

    /** The declaration to check against, or null when the manifest leaves the resource untyped. */
    static ResourceDecl requireReadable(Manifest manifest, String resource) {
        boolean readable = manifest.scopes().stream()
                .anyMatch(s -> s.type() == ScopeType.READ && s.resource().equals(resource));
        if (!readable) {
            throw new DataApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESOURCE_NOT_BINDABLE",
                    "'" + resource + "' is not a resource this agent reads, so no file can be bound to it");
        }
        return manifest.resource(resource).orElse(null);
    }
}
```

`data/DatasetCheckService.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ResourceDecl;
import com.agentstore.platform.storage.ObjectStorage;
import com.fasterxml.jackson.databind.MappingIterator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;

@Service
public class DatasetCheckService {

    private final DatasetRepository datasets;
    private final AgentVersionRepository versions;
    private final ManifestParser manifestParser;
    private final ObjectStorage storage;

    public DatasetCheckService(DatasetRepository datasets, AgentVersionRepository versions,
                               ManifestParser manifestParser, ObjectStorage storage) {
        this.datasets = datasets;
        this.versions = versions;
        this.manifestParser = manifestParser;
        this.storage = storage;
    }

    /** The endpoint's entry point: the dataset must be the caller's, the version LIVE, the resource a READ one. */
    public SchemaChecker.CheckResult check(UUID orgId, UUID datasetId, UUID agentVersionId, String resource) {
        Dataset dataset = datasets.findByIdAndOwnerOrgId(datasetId, orgId)
                .filter(d -> d.getDeletedAt() == null && d.getPurgedAt() == null)
                .orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Dataset not found"));
        AgentVersion version = versions.findById(agentVersionId)
                .filter(v -> v.getStatus() == VersionStatus.LIVE)
                .orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Agent version not found"));
        Manifest manifest = manifestParser.parse(version.getManifestJson());
        return check(dataset, ResourceLookup.requireReadable(manifest, resource));
    }

    /** Checks a stored file against a resource declaration (null = untyped). Saves nothing. */
    public SchemaChecker.CheckResult check(Dataset dataset, ResourceDecl decl) {
        if (decl != null && decl.format().tabular() != dataset.getFormat().tabular()) {
            return SchemaChecker.CheckResult.formatMismatch("This resource expects "
                    + (decl.format().tabular() ? "rows of data (CSV or JSON)" : "a PDF document")
                    + " but the file is " + dataset.getFormat().wire());
        }
        if (!dataset.getFormat().tabular()) {
            return SchemaChecker.CheckResult.file();
        }
        try (InputStream in = storage.open(dataset.getRecordsKey());
             MappingIterator<Map<String, Object>> rows = Jsonl.readRows(in)) {
            return SchemaChecker.check(decl == null ? null : decl.schema(), dataset.columns(), rows);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

In `data/DatasetController.java`, add a `DatasetCheckService checkService` constructor parameter and field (constructor becomes `DatasetController(DatasetService datasetService, DatasetCheckService checkService)`), and add:

```java
    public record CheckRequest(@jakarta.validation.constraints.NotNull UUID agentVersionId,
                               @jakarta.validation.constraints.NotBlank String resource) {
    }

    @PostMapping("/datasets/{id}/check")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public SchemaChecker.CheckResult check(@PathVariable UUID id, @jakarta.validation.Valid @RequestBody CheckRequest request) {
        return checkService.check(Caller.current().orgId(), id, request.agentVersionId(), request.resource());
    }
```

- [ ] **Step 4: Run to verify they pass, plus Task 4's tests**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='ValueCoercerTest,SchemaCheckerTest,DatasetCheckEndpointTest,DatasetApiTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(data): schema checker and the dataset check endpoint"
```

---

### Task 6: Bindings API, `BindingResolver`, trigger-time pins, mode guard

**Files:**
- Create (in `data/`): `DataAccessRules.java`, `BindingService.java`, `BindingController.java`, `BindingResolver.java`
- Modify: `execution/RunController.java`, `data/DataFixtures.java` (test)
- Test: `data/BindingApiTest.java`, `data/RunPinningTest.java`, `data/ModeGuardTest.java`

**Interfaces:**
- Consumes: Tasks 2, 4, 5 (`DatasetCheckService.check(Dataset, ResourceDecl)`, `ResourceLookup.requireReadable`, `DataApiException`, `Caller`).
- Produces:
  - `DataAccessRules.allows(RunMode, DatasetPurpose) : boolean` (PRODUCTION ↔ BUYER_DATA; every other mode ↔ anything but BUYER_DATA).
  - `BindingService.bind(UUID orgId, UUID userId, UUID connectionId, String resource, UUID datasetId) : BindingResponse`; `unbind(UUID orgId, UUID connectionId, String resource)`; `list(UUID orgId, UUID connectionId) : List<BindingResponse>`; `unbindAll(UUID connectionId)`; `record BindingResponse(String resource, UUID datasetId, String displayName, DataFormat format, Long rowCount, Instant boundAt)`.
  - HTTP: `PUT /api/v1/connections/{id}/bindings/{resource}` body `{datasetId}`; `DELETE …/bindings/{resource}` (204); `GET …/bindings`.
  - `BindingResolver.resolve(Connection, Manifest, RunMode) : List<ResolvedPin>`; `record ResolvedPin(String resource, UUID datasetId, String sha256)`; `BindingResolver.saveWithPins(Run pending, List<ResolvedPin>) : Run` (one transaction). Throws `DataApiException(422, MISSING_BINDING, …, {resources: […]})`.

- [ ] **Step 1: Extend the test fixtures**

Add two constructor parameters and fields to `DataFixtures` (test): `BindingService bindingService` and `ConnectionRepository connectionRepository` (`com.agentstore.platform.execution.ConnectionRepository`), then add:

```java
    /** Binds through the service as a buyer admin of the connection's org. */
    public void bind(com.agentstore.platform.execution.Connection connection, String resource, Dataset dataset) {
        bindingService.bind(connection.getBuyerOrg().getId(), UUID.randomUUID(), connection.getId(), resource,
                dataset.getId());
    }

    /** Narrows what a connection grants, like a buyer who ticked fewer scopes. */
    public void grantOnly(com.agentstore.platform.execution.Connection connection, List<String> scopes) {
        connection.setGrantedScopes(scopes);
        connectionRepository.save(connection);
    }

    /** A second ACTIVE connection of the same buyer to the same agent version. */
    public com.agentstore.platform.execution.Connection connectionFor(ExecutionFixtures.Scenario scenario, List<String> scopes) {
        var connection = new com.agentstore.platform.execution.Connection(null, scenario.buyer(),
                scenario.version().getAgent(), scopes, new BigDecimal("500.00"),
                com.agentstore.platform.execution.ConnectionStatus.ACTIVE);
        connection.setAgentVersion(scenario.version());
        return connectionRepository.save(connection);
    }

    public void unbindAll(com.agentstore.platform.execution.Connection connection) {
        bindingService.unbindAll(connection.getId());
    }
```

- [ ] **Step 2: Write the failing tests**

`data/BindingApiTest.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BindingApiTest extends AbstractStorageIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    DataFixtures fx;
    @Autowired
    DatasetRepository datasetRepository;
    @Autowired
    DatasetBindingRepository bindingRepository;

    @SuppressWarnings("rawtypes")
    org.springframework.http.ResponseEntity<Map> put(Org caller, UserRole role, UUID connectionId, String resource, UUID datasetId) {
        return rest.exchange("http://localhost:" + port + "/api/v1/connections/" + connectionId + "/bindings/" + resource,
                HttpMethod.PUT, new HttpEntity<>(Map.of("datasetId", datasetId), fx.bearer(caller, role)), Map.class);
    }

    @SuppressWarnings("rawtypes")
    org.springframework.http.ResponseEntity<Map> delete(Org caller, UUID connectionId, String resource) {
        return rest.exchange("http://localhost:" + port + "/api/v1/connections/" + connectionId + "/bindings/" + resource,
                HttpMethod.DELETE, new HttpEntity<>(null, fx.bearer(caller, UserRole.BUYER_ADMIN)), Map.class);
    }

    @Test
    void aMatchingFileBindsAndIsListed() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV);

        var response = put(s.buyer(), UserRole.BUYER_ADMIN, s.connection().getId(), "bank_statement", d.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("resource")).isEqualTo("bank_statement");
        var list = rest.exchange("http://localhost:" + port + "/api/v1/connections/" + s.connection().getId() + "/bindings",
                HttpMethod.GET, new HttpEntity<>(null, fx.bearer(s.buyer(), UserRole.BUYER_APPROVER)), List.class);
        assertThat(list.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) list.getBody().get(0)).get("datasetId")).isEqualTo(d.getId().toString());
    }

    @Test
    void aFileThatDoesNotMatchIs422WithTheCheckResultAndBindsNothing() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "jan.csv", "date,amount\n2026-01-05,10\n");

        var response = put(s.buyer(), UserRole.BUYER_ADMIN, s.connection().getId(), "bank_statement", d.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code")).isEqualTo("DATASET_CHECK_FAILED");
        Map<?, ?> result = (Map<?, ?>) response.getBody().get("checkResult");
        assertThat(result.get("missingColumns")).isEqualTo(List.of("narration"));
        assertThat(bindingRepository.existsByDatasetId(d.getId())).isFalse();
    }

    @Test
    void writeResourcesAndUngrantedResourcesAreNeverBound() {
        var s = fx.scenario();
        Dataset csv = fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV);
        fx.grantOnly(s.connection(), List.of("bank_statement"));

        var write = put(s.buyer(), UserRole.BUYER_ADMIN, s.connection().getId(), "journal_entries", csv.getId());
        var undeclared = put(s.buyer(), UserRole.BUYER_ADMIN, s.connection().getId(), "payroll", csv.getId());

        assertThat(write.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(write.getBody().get("code")).isEqualTo("RESOURCE_NOT_BINDABLE");
        assertThat(undeclared.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aReadResourceTheBuyerDidNotGrantIsRefused() {
        var s = fx.scenario();
        Dataset pdf = fx.upload(s.buyer(), "jan.pdf", DataFixtures.PDF);
        fx.grantOnly(s.connection(), List.of("bank_statement"));

        var response = put(s.buyer(), UserRole.BUYER_ADMIN, s.connection().getId(), "statement_pdf", pdf.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code")).isEqualTo("RESOURCE_NOT_GRANTED");
    }

    @Test
    void crossOrgAccessFails() {
        var s = fx.scenario();
        var other = fx.scenario();
        Dataset mine = fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV);
        Dataset theirs = fx.upload(other.buyer(), "jan.csv", DataFixtures.CSV);

        assertThat(put(s.buyer(), UserRole.BUYER_ADMIN, s.connection().getId(), "bank_statement", theirs.getId())
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put(s.buyer(), UserRole.BUYER_ADMIN, other.connection().getId(), "bank_statement", mine.getId())
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(delete(s.buyer(), other.connection().getId(), "bank_statement").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anApproverCannotBind() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV);

        assertThat(put(s.buyer(), UserRole.BUYER_APPROVER, s.connection().getId(), "bank_statement", d.getId())
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void replacingABindingStartsTheThirtyDayClockOnTheOldFileAndRebindingClearsIt() {
        var s = fx.scenario();
        Dataset a = fx.upload(s.buyer(), "a.csv", DataFixtures.CSV);
        Dataset b = fx.upload(s.buyer(), "b.csv", DataFixtures.CSV);
        UUID connection = s.connection().getId();

        put(s.buyer(), UserRole.BUYER_ADMIN, connection, "bank_statement", a.getId());
        put(s.buyer(), UserRole.BUYER_ADMIN, connection, "bank_statement", b.getId());

        assertThat(bindingRepository.findByDatasetIdIn(List.of(a.getId(), b.getId())))
                .extracting(DatasetBinding::getDatasetId).containsExactly(b.getId());
        Instant purgeAfter = datasetRepository.findById(a.getId()).orElseThrow().getPurgeAfter();
        assertThat(purgeAfter).isBetween(Instant.now().plus(Duration.ofDays(29)), Instant.now().plus(Duration.ofDays(31)));
        assertThat(datasetRepository.findById(b.getId()).orElseThrow().getPurgeAfter()).isNull();

        put(s.buyer(), UserRole.BUYER_ADMIN, connection, "bank_statement", a.getId());
        assertThat(datasetRepository.findById(a.getId()).orElseThrow().getPurgeAfter()).isNull();
        assertThat(datasetRepository.findById(b.getId()).orElseThrow().getPurgeAfter()).isNotNull();
    }

    @Test
    void unpluggingStartsTheThirtyDayClockUnlessAnotherConnectionStillUsesTheFile() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "a.csv", DataFixtures.CSV);
        UUID connection = s.connection().getId();
        put(s.buyer(), UserRole.BUYER_ADMIN, connection, "bank_statement", d.getId());

        var response = delete(s.buyer(), connection, "bank_statement");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(datasetRepository.findById(d.getId()).orElseThrow().getPurgeAfter())
                .isBetween(Instant.now().plus(Duration.ofDays(29)), Instant.now().plus(Duration.ofDays(31)));
        assertThat(delete(s.buyer(), connection, "bank_statement").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unbindAllStartsTheClockOnlyForFilesNoLongerBoundAnywhere() {
        var s = fx.scenario();
        Dataset shared = fx.upload(s.buyer(), "shared.csv", DataFixtures.CSV);
        Dataset pdf = fx.upload(s.buyer(), "only-here.pdf", DataFixtures.PDF);
        var second = fx.connectionFor(s, List.of("bank_statement"));
        fx.bind(s.connection(), "bank_statement", shared);
        fx.bind(s.connection(), "statement_pdf", pdf);
        fx.bind(second, "bank_statement", shared);           // shared is bound on both connections

        fx.unbindAll(s.connection());

        assertThat(bindingRepository.findByOwnerTypeAndOwnerId(BindingOwnerType.CONNECTION, s.connection().getId())).isEmpty();
        assertThat(bindingRepository.existsByDatasetId(shared.getId())).isTrue();          // still bound on the second connection
        assertThat(datasetRepository.findById(shared.getId()).orElseThrow().getPurgeAfter()).isNull();
        assertThat(datasetRepository.findById(pdf.getId()).orElseThrow().getPurgeAfter())
                .isBetween(Instant.now().plus(Duration.ofDays(29)), Instant.now().plus(Duration.ofDays(31)));
    }
}
```

`data/RunPinningTest.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunResponse;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunPinningTest extends AbstractStorageIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    DataFixtures fx;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunDatasetPinRepository pinRepository;

    @SuppressWarnings("rawtypes")
    org.springframework.http.ResponseEntity<Map> trigger(com.agentstore.platform.execution.ExecutionFixtures.Scenario s) {
        return rest.exchange("http://localhost:" + port + "/api/v1/runs", HttpMethod.POST,
                new HttpEntity<>(Map.of("connectionId", s.connection().getId()), fx.bearer(s.buyer(), UserRole.BUYER_ADMIN)),
                Map.class);
    }

    @Test
    void aMissingBindingBlocksTheTriggerAndListsWhatIsMissing() {
        var s = fx.scenario();
        fx.bind(s.connection(), "bank_statement", fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV));

        var response = trigger(s);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code")).isEqualTo("MISSING_BINDING");
        assertThat(response.getBody().get("resources")).isEqualTo(List.of("statement_pdf"));
        assertThat(runRepository.findByConnectionBuyerOrgIdOrderByStartedAtDesc(s.buyer().getId())).isEmpty();
    }

    @Test
    void aBoundTriggerPinsTheFilesAndALaterRebindDoesNotChangeThem() {
        var s = fx.scenario();
        Dataset first = fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV);
        Dataset pdf = fx.upload(s.buyer(), "jan.pdf", DataFixtures.PDF);
        fx.bind(s.connection(), "bank_statement", first);
        fx.bind(s.connection(), "statement_pdf", pdf);

        var response = trigger(s);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        java.util.UUID runId = java.util.UUID.fromString((String) response.getBody().get("id"));
        assertThat(pinRepository.findByIdRunId(runId)).extracting(p -> p.getId().getResourceName(), RunDatasetPin::getDatasetId,
                RunDatasetPin::getSha256).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("bank_statement", first.getId(), first.getSha256()),
                org.assertj.core.groups.Tuple.tuple("statement_pdf", pdf.getId(), pdf.getSha256()));

        Dataset second = fx.upload(s.buyer(), "feb.csv", "date,narration,amount\n2026-02-01,x,1\n");
        fx.bind(s.connection(), "bank_statement", second);
        assertThat(pinRepository.findByIdRunIdAndIdResourceName(runId, "bank_statement").orElseThrow().getDatasetId())
                .isEqualTo(first.getId());
    }
}
```

`data/ModeGuardTest.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.manifest.ManifestParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModeGuardTest extends AbstractStorageIntegrationTest {

    @Autowired
    DataFixtures fx;
    @Autowired
    BindingResolver resolver;
    @Autowired
    ManifestParser parser;
    @Autowired
    DatasetRepository datasetRepository;
    @Autowired
    DatasetBindingRepository bindingRepository;

    @Test
    void theRulesTable() {
        assertThat(DataAccessRules.allows(RunMode.PRODUCTION, DatasetPurpose.BUYER_DATA)).isTrue();
        for (DatasetPurpose purpose : new DatasetPurpose[]{DatasetPurpose.FIXTURE, DatasetPurpose.SAMPLE, DatasetPurpose.EVAL}) {
            assertThat(DataAccessRules.allows(RunMode.PRODUCTION, purpose)).isFalse();
            for (RunMode mode : new RunMode[]{RunMode.SANDBOX, RunMode.REVIEW, RunMode.TRIAL}) {
                assertThat(DataAccessRules.allows(mode, purpose)).isTrue();
            }
        }
        for (RunMode mode : new RunMode[]{RunMode.SANDBOX, RunMode.REVIEW, RunMode.TRIAL}) {
            assertThat(DataAccessRules.allows(mode, DatasetPurpose.BUYER_DATA)).isFalse();
        }
    }

    @Test
    void aSandboxRunNeverResolvesBuyerData() {
        var s = fx.scenario();
        var manifest = parser.parse(s.version().getManifestJson());
        fx.bind(s.connection(), "bank_statement", fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV));
        fx.bind(s.connection(), "statement_pdf", fx.upload(s.buyer(), "jan.pdf", DataFixtures.PDF));

        assertThat(resolver.resolve(s.connection(), manifest, RunMode.PRODUCTION)).hasSize(2);
        for (RunMode mode : new RunMode[]{RunMode.SANDBOX, RunMode.REVIEW, RunMode.TRIAL}) {
            assertThatThrownBy(() -> resolver.resolve(s.connection(), manifest, mode))
                    .isInstanceOf(DataApiException.class).hasMessageContaining("Bind a file");
        }
    }

    @Test
    void aFixtureDatasetResolvesOnlyForNonProductionRuns() {
        var s = fx.scenario();
        var manifest = parser.parse(s.version().getManifestJson());
        Dataset csv = fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV);
        Dataset pdf = fx.upload(s.buyer(), "jan.pdf", DataFixtures.PDF);
        fx.bind(s.connection(), "bank_statement", csv);
        fx.bind(s.connection(), "statement_pdf", pdf);
        // WS6 will create FIXTURE datasets; simulate that by changing the purpose of the two bound files
        for (Dataset dataset : List.of(csv, pdf)) {
            dataset.setPurpose(DatasetPurpose.FIXTURE);
            datasetRepository.save(dataset);
        }

        assertThat(resolver.resolve(s.connection(), manifest, RunMode.SANDBOX))
                .extracting(BindingResolver.ResolvedPin::resource).containsExactlyInAnyOrder("bank_statement", "statement_pdf");
        assertThatThrownBy(() -> resolver.resolve(s.connection(), manifest, RunMode.PRODUCTION))
                .isInstanceOf(DataApiException.class).hasMessageContaining("Bind a file");
    }
}
```

- [ ] **Step 3: Run to verify they fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='BindingApiTest,RunPinningTest,ModeGuardTest'`
Expected: COMPILE FAIL.

- [ ] **Step 4: Implement**

`data/DataAccessRules.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.execution.RunMode;

/** The mode guard: buyer data only ever reaches a PRODUCTION run, and a PRODUCTION run only ever sees buyer data. */
public final class DataAccessRules {

    private DataAccessRules() {
    }

    public static boolean allows(RunMode mode, DatasetPurpose purpose) {
        return mode == RunMode.PRODUCTION ? purpose == DatasetPurpose.BUYER_DATA : purpose != DatasetPurpose.BUYER_DATA;
    }
}
```

`data/BindingService.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.execution.Connection;
import com.agentstore.platform.execution.ConnectionRepository;
import com.agentstore.platform.execution.ConnectionStatus;
import com.agentstore.platform.manifest.DataFormat;
import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ResourceDecl;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BindingService {

    /** How long an unplugged file lives before the retention job may purge it. */
    static final Duration UNPLUG_GRACE = Duration.ofDays(30);

    public record BindingResponse(String resource, UUID datasetId, String displayName, DataFormat format,
                                  Long rowCount, Instant boundAt) {
    }

    private final ConnectionRepository connections;
    private final DatasetRepository datasets;
    private final DatasetBindingRepository bindings;
    private final DatasetCheckService checks;
    private final ManifestParser manifestParser;

    public BindingService(ConnectionRepository connections, DatasetRepository datasets,
                          DatasetBindingRepository bindings, DatasetCheckService checks, ManifestParser manifestParser) {
        this.connections = connections;
        this.datasets = datasets;
        this.bindings = bindings;
        this.checks = checks;
        this.manifestParser = manifestParser;
    }

    @Transactional
    public BindingResponse bind(UUID orgId, UUID userId, UUID connectionId, String resource, UUID datasetId) {
        Connection connection = ownedConnection(orgId, connectionId);
        if (connection.getStatus() != ConnectionStatus.ACTIVE) {
            throw new DataApiException(HttpStatus.CONFLICT, "CONNECTION_NOT_ACTIVE", "Connection is not active");
        }
        Dataset dataset = datasets.findByIdForUpdate(datasetId)
                .filter(d -> d.getOwnerOrgId().equals(orgId) && d.getDeletedAt() == null && d.getPurgedAt() == null
                        && d.getPurpose() == DatasetPurpose.BUYER_DATA)
                .orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Dataset not found"));
        AgentVersion version = connection.getAgentVersion();
        if (version == null) {
            throw new DataApiException(HttpStatus.CONFLICT, "NO_AGENT_VERSION",
                    "Connection is not pinned to an agent version");
        }
        Manifest manifest = manifestParser.parse(version.getManifestJson());
        ResourceDecl decl = ResourceLookup.requireReadable(manifest, resource);
        if (!connection.getGrantedScopes().contains(resource)) {
            throw new DataApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESOURCE_NOT_GRANTED",
                    "This connection does not grant access to '" + resource + "'");
        }
        SchemaChecker.CheckResult result = checks.check(dataset, decl);
        if (!result.ok()) {
            throw new DataApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DATASET_CHECK_FAILED",
                    "The file does not match what the agent expects", Map.of("checkResult", result));
        }
        String checkJson = json(result);
        DatasetBinding binding = bindings.findByOwnerTypeAndOwnerIdAndResourceName(BindingOwnerType.CONNECTION,
                connectionId, resource).orElse(null);
        UUID replaced = null;
        if (binding == null) {
            binding = new DatasetBinding(BindingOwnerType.CONNECTION, connectionId, resource, datasetId, checkJson, userId);
        } else {
            if (!binding.getDatasetId().equals(datasetId)) {
                replaced = binding.getDatasetId();
            }
            binding.setDatasetId(datasetId);
            binding.setCheckResult(checkJson);
            binding.setBoundByUserId(userId);
            binding.setBoundAt(Instant.now());
        }
        bindings.saveAndFlush(binding);
        dataset.setPurgeAfter(null);
        if (replaced != null) {
            release(replaced);
        }
        return response(binding, dataset);
    }

    @Transactional
    public void unbind(UUID orgId, UUID connectionId, String resource) {
        ownedConnection(orgId, connectionId);
        DatasetBinding binding = bindings.findByOwnerTypeAndOwnerIdAndResourceName(BindingOwnerType.CONNECTION,
                connectionId, resource).orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Nothing is bound to '" + resource + "'"));
        bindings.delete(binding);
        bindings.flush();
        release(binding.getDatasetId());
    }

    @Transactional(readOnly = true)
    public List<BindingResponse> list(UUID orgId, UUID connectionId) {
        ownedConnection(orgId, connectionId);
        return bindings.findByOwnerTypeAndOwnerId(BindingOwnerType.CONNECTION, connectionId).stream()
                .map(b -> response(b, datasets.findById(b.getDatasetId()).orElseThrow())).toList();
    }

    /** For revoking a connection: unplugs everything and starts the 30-day clock for files no longer bound anywhere. */
    @Transactional
    public void unbindAll(UUID connectionId) {
        List<DatasetBinding> all = bindings.findByOwnerTypeAndOwnerId(BindingOwnerType.CONNECTION, connectionId);
        bindings.deleteAll(all);
        bindings.flush();
        all.stream().map(DatasetBinding::getDatasetId).distinct().forEach(this::release);
    }

    /** A file no longer bound anywhere gets the grace period (unless it is already being deleted). */
    private void release(UUID datasetId) {
        if (bindings.existsByDatasetId(datasetId)) {
            return;
        }
        datasets.findByIdForUpdate(datasetId).filter(d -> d.getDeletedAt() == null && d.getPurgedAt() == null)
                .ifPresent(d -> d.setPurgeAfter(Instant.now().plus(UNPLUG_GRACE)));
    }

    private Connection ownedConnection(UUID orgId, UUID connectionId) {
        Connection connection = connections.findById(connectionId)
                .orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Connection not found"));
        if (!connection.getBuyerOrg().getId().equals(orgId)) {
            throw new DataApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Connection not owned by caller's org");
        }
        return connection;
    }

    private static BindingResponse response(DatasetBinding b, Dataset d) {
        return new BindingResponse(b.getResourceName(), d.getId(), d.getDisplayName(), d.getFormat(), d.getRowCount(),
                b.getBoundAt());
    }

    private static String json(Object value) {
        try {
            return Jsonl.MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`data/BindingController.java`:

```java
package com.agentstore.platform.data;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/connections/{connectionId}/bindings")
public class BindingController {

    public record BindRequest(@NotNull UUID datasetId) {
    }

    private final BindingService bindingService;

    public BindingController(BindingService bindingService) {
        this.bindingService = bindingService;
    }

    @PutMapping("/{resource}")
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    public BindingService.BindingResponse bind(@PathVariable UUID connectionId, @PathVariable String resource,
                                               @Valid @RequestBody BindRequest request) {
        var claims = Caller.current();
        return bindingService.bind(claims.orgId(), claims.userId(), connectionId, resource, request.datasetId());
    }

    @DeleteMapping("/{resource}")
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind(@PathVariable UUID connectionId, @PathVariable String resource) {
        bindingService.unbind(Caller.current().orgId(), connectionId, resource);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public List<BindingService.BindingResponse> list(@PathVariable UUID connectionId) {
        return bindingService.list(Caller.current().orgId(), connectionId);
    }
}
```

`data/BindingResolver.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.execution.Connection;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.Scope;
import com.agentstore.platform.manifest.ScopeType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Called from the run trigger: resolves what each granted READ resource is bound to and fixes it for the run. A
 * declared, granted READ resource with no usable binding blocks the trigger (there are no optional resources in v1);
 * a manifest without {@code resources} is untyped, so its bindings are pinned when present and never demanded.
 */
@Service
public class BindingResolver {

    public record ResolvedPin(String resource, UUID datasetId, String sha256) {
    }

    private final DatasetBindingRepository bindings;
    private final DatasetRepository datasets;
    private final RunDatasetPinRepository pins;
    private final RunRepository runs;

    public BindingResolver(DatasetBindingRepository bindings, DatasetRepository datasets, RunDatasetPinRepository pins,
                           RunRepository runs) {
        this.bindings = bindings;
        this.datasets = datasets;
        this.pins = pins;
        this.runs = runs;
    }

    @Transactional(readOnly = true)
    public List<ResolvedPin> resolve(Connection connection, Manifest manifest, RunMode mode) {
        Set<String> granted = new HashSet<>(connection.getGrantedScopes());
        Map<String, DatasetBinding> bound = new HashMap<>();
        for (DatasetBinding binding : bindings.findByOwnerTypeAndOwnerId(BindingOwnerType.CONNECTION, connection.getId())) {
            bound.put(binding.getResourceName(), binding);
        }
        Set<String> resources = new LinkedHashSet<>();
        for (Scope scope : manifest.scopes()) {
            if (scope.type() == ScopeType.READ && granted.contains(scope.resource())) {
                resources.add(scope.resource());
            }
        }
        List<ResolvedPin> resolved = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String resource : resources) {
            Dataset dataset = java.util.Optional.ofNullable(bound.get(resource))
                    .flatMap(b -> datasets.findById(b.getDatasetId()))
                    .filter(d -> d.getPurgedAt() == null && d.getDeletedAt() == null
                            && DataAccessRules.allows(mode, d.getPurpose()))
                    .orElse(null);
            if (dataset != null) {
                resolved.add(new ResolvedPin(resource, dataset.getId(), dataset.getSha256()));
            } else if (manifest.declaresResources()) {
                missing.add(resource);
            }
        }
        if (!missing.isEmpty()) {
            throw new DataApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MISSING_BINDING",
                    "Bind a file to " + String.join(", ", missing) + " before running this agent",
                    Map.of("resources", missing));
        }
        return resolved;
    }

    /** Creates the run and its pins in one transaction, so a run never exists without what it reads. */
    @Transactional
    public Run saveWithPins(Run pending, List<ResolvedPin> resolved) {
        Run run = runs.save(pending);
        for (ResolvedPin pin : resolved) {
            pins.save(new RunDatasetPin(run.getId(), pin.resource(), pin.datasetId(), pin.sha256()));
        }
        return run;
    }
}
```

Modify `execution/RunController.java`:
- add imports `com.agentstore.platform.data.BindingResolver`, `com.agentstore.platform.manifest.Manifest`, `java.util.List`;
- add a `private final BindingResolver bindingResolver;` field and a last constructor parameter `BindingResolver bindingResolver`;
- replace `manifestParser.parse(version.getManifestJson());   // fail fast …` with
  ```java
        Manifest manifest = manifestParser.parse(version.getManifestJson());   // fail fast on a corrupt manifest, before reserving
        List<BindingResolver.ResolvedPin> pins = bindingResolver.resolve(connection, manifest, RunMode.PRODUCTION);   // MISSING_BINDING before any money is reserved
  ```
- replace `run = runRepository.save(pending);` with `run = bindingResolver.saveWithPins(pending, pins);`.

- [ ] **Step 5: Run to verify they pass, plus every existing run test**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='BindingApiTest,RunPinningTest,ModeGuardTest,RunControllerTest,RunControllerWalletTest,RunLaunchFromBuildTest,RunJoinsInFlightRebuildTest,RunVersionPinningTest,EndToEndTierATest,EndToEndTierBTest'`
Expected: PASS (manifests without `resources` are unaffected).

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat(data): bindings API, trigger-time pinning and the mode guard"
```

---

### Task 7: `DatasetResourceBackend` reads — metadata, records cursor, file and its content endpoint

**Files:**
- Create: `data/RecordCursor.java`, `data/DatasetResourceBackend.java`
- Modify: `runtime/ResourceBackend.java`, `runtime/RuntimeResourceController.java`, `runtime/RuntimeExceptionHandler.java`, `runtime/RuntimeApiException.java`, `runtime/ProblemBody.java`, `contracts/runtime-v1.yaml` (regenerated)
- Delete: `runtime/UnavailableResourceBackend.java`
- Modify (tests): `runtime/RuntimeResourceEndpointsTest.java`, `runtime/RuntimeContractTest.java`
- Test: `data/DatasetResourceBackendReadTest.java`

**Interfaces:**
- Consumes: Tasks 2–6.
- Produces:
  - `RecordCursor.encode(String sha256, long offset) : String`; `RecordCursor.decode(String cursor, String sha256) : long` (throws `RuntimeApiException(400, "INVALID_CURSOR")`).
  - `ResourceBackend.fileContent(RunPrincipal, String resource) : FileContent` (default method → 501) and `record FileContent(String contentType, long sizeBytes, InputStream stream)`.
  - `RuntimeApiException(HttpStatus, String code, String message, List<?> errors)` and `errors()`; `ProblemBody.errors` (omitted when null).
  - `RuntimeExceptionHandler`: `StorageException → 503 STORAGE_UNAVAILABLE`.
  - HTTP: `GET /runtime/v1/resources/{resource}/file/content`.
  - `DatasetResourceBackend.write` is a stub delegating to a `RunOutputWriter` in Task 8; in this task it throws 501 so the class compiles and the write tests are Task 8's.

- [ ] **Step 1: Write the failing tests**

`data/DatasetResourceBackendReadTest.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.runtime.RuntimeFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetResourceBackendReadTest extends AbstractStorageIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    DataFixtures fx;
    @Autowired
    RunRepository runRepository;

    @SuppressWarnings("rawtypes")
    org.springframework.http.ResponseEntity<Map> get(String path, String token) {
        return rest.exchange("http://localhost:" + port + path, HttpMethod.GET,
                new HttpEntity<>(null, RuntimeFixtures.bearer(token)), Map.class);
    }

    /** 25 rows whose narration has multi-byte characters, so byte offsets differ from character counts. */
    String bigCsv() {
        StringBuilder csv = new StringBuilder("date,narration,amount\n");
        for (int i = 1; i <= 25; i++) {
            csv.append("2026-01-").append(String.format("%02d", i)).append(",row-").append(i).append(" é₹😀,")
                    .append(i).append(".50\n");
        }
        return csv.toString();
    }

    @Test
    void metadataReportsFormatAndRowCount() {
        var agent = fx.runningAgent();
        fx.pin(agent.run(), "bank_statement", fx.upload(agent.scenario().buyer(), "jan.csv", DataFixtures.CSV));
        fx.pin(agent.run(), "statement_pdf", fx.upload(agent.scenario().buyer(), "jan.pdf", DataFixtures.PDF));

        var csv = get("/runtime/v1/resources/bank_statement", agent.token());
        var pdf = get("/runtime/v1/resources/statement_pdf", agent.token());

        assertThat(csv.getBody().get("format")).isEqualTo("csv");
        assertThat(csv.getBody().get("rowCount")).isEqualTo(2);
        assertThat(pdf.getBody().get("format")).isEqualTo("pdf");
        assertThat(pdf.getBody().get("rowCount")).isNull();
    }

    @Test
    void pagesAcrossBoundariesWithMultiByteText() {
        var agent = fx.runningAgent();
        fx.pin(agent.run(), "bank_statement", fx.upload(agent.scenario().buyer(), "big.csv", bigCsv()));
        List<String> narrations = new ArrayList<>();
        String cursor = null;
        int pages = 0;

        do {
            var page = get("/runtime/v1/resources/bank_statement/records?limit=10"
                    + (cursor == null ? "" : "&cursor=" + cursor), agent.token());
            assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
            for (Object record : (List<?>) page.getBody().get("records")) {
                narrations.add((String) ((Map<?, ?>) record).get("narration"));
            }
            cursor = (String) page.getBody().get("nextCursor");
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(narrations).hasSize(25);
        assertThat(narrations.get(0)).isEqualTo("row-1 é₹😀");
        assertThat(narrations.get(24)).isEqualTo("row-25 é₹😀");
    }

    @Test
    void anExactMultipleOfTheLimitEndsWithANullCursor() {
        var agent = fx.runningAgent();
        fx.pin(agent.run(), "bank_statement", fx.upload(agent.scenario().buyer(), "jan.csv", DataFixtures.CSV));

        var page = get("/runtime/v1/resources/bank_statement/records?limit=2", agent.token());

        assertThat((List<?>) page.getBody().get("records")).hasSize(2);
        assertThat(page.getBody().get("nextCursor")).isNull();
    }

    @Test
    void valuesAreCoercedToTheDeclaredTypes() {
        var agent = fx.runningAgent();
        fx.pin(agent.run(), "bank_statement", fx.upload(agent.scenario().buyer(), "jan.csv", DataFixtures.CSV));

        var page = get("/runtime/v1/resources/bank_statement/records", agent.token());

        Map<?, ?> rent = (Map<?, ?>) ((List<?>) page.getBody().get("records")).get(1);
        assertThat(((Number) rent.get("amount")).doubleValue()).isEqualTo(-12000.50);
        assertThat(rent.get("date")).isEqualTo("2026-01-06");
        assertThat(rent.get("narration")).isEqualTo("Rent");
    }

    @Test
    void aCursorFromAnotherFileIsRejected() {
        var agent = fx.runningAgent();
        var other = fx.runningAgent();
        fx.pin(agent.run(), "bank_statement", fx.upload(agent.scenario().buyer(), "a.csv", bigCsv()));
        // different content, so a different sha; identical content would make the cursor valid for both files
        fx.pin(other.run(), "bank_statement", fx.upload(other.scenario().buyer(), "b.csv", bigCsv() + "2026-02-01,extra,1\n"));
        String foreign = (String) get("/runtime/v1/resources/bank_statement/records?limit=5", other.token())
                .getBody().get("nextCursor");

        var response = get("/runtime/v1/resources/bank_statement/records?limit=5&cursor=" + foreign, agent.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_CURSOR");
    }

    @Test
    void garbageAndPastTheEndCursorsAreHandled() {
        var agent = fx.runningAgent();
        Dataset d = fx.upload(agent.scenario().buyer(), "jan.csv", DataFixtures.CSV);
        fx.pin(agent.run(), "bank_statement", d);

        assertThat(get("/runtime/v1/resources/bank_statement/records?cursor=!!!", agent.token()).getBody().get("code"))
                .isEqualTo("INVALID_CURSOR");
        assertThat(get("/runtime/v1/resources/bank_statement/records?cursor=bm9wZQ", agent.token()).getBody().get("code"))
                .isEqualTo("INVALID_CURSOR");
        String past = RecordCursor.encode(d.getSha256(), d.getRecordsSizeBytes() + 100);
        var page = get("/runtime/v1/resources/bank_statement/records?cursor=" + past, agent.token());
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) page.getBody().get("records")).isEmpty();
        assertThat(page.getBody().get("nextCursor")).isNull();
    }

    @Test
    void aPdfIsNotTabularButItsFileStreamsThroughTheBackend() {
        var agent = fx.runningAgent();
        fx.pin(agent.run(), "statement_pdf", fx.upload(agent.scenario().buyer(), "jan.pdf", DataFixtures.PDF));

        var records = get("/runtime/v1/resources/statement_pdf/records", agent.token());
        assertThat(records.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(records.getBody().get("code")).isEqualTo("NOT_TABULAR");

        var link = get("/runtime/v1/resources/statement_pdf/file", agent.token());
        assertThat((String) link.getBody().get("url")).endsWith("/runtime/v1/resources/statement_pdf/file/content");
        var content = rest.exchange("http://localhost:" + port + "/runtime/v1/resources/statement_pdf/file/content",
                HttpMethod.GET, new HttpEntity<>(null, RuntimeFixtures.bearer(agent.token())), byte[].class);
        assertThat(content.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(content.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(content.getBody()).isEqualTo(DataFixtures.PDF);
    }

    @Test
    void aResourceWithNoPinIs404() {
        var agent = fx.runningAgent();

        var response = get("/runtime/v1/resources/bank_statement/records", agent.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("RESOURCE_NOT_BOUND");
    }

    @Test
    void theBackendRefusesBuyerDataToASandboxRunEvenIfItWasPinned() {
        var agent = fx.runningAgent();
        fx.pin(agent.run(), "bank_statement", fx.upload(agent.scenario().buyer(), "jan.csv", DataFixtures.CSV));
        var run = agent.run();
        run.setMode(RunMode.SANDBOX);
        runRepository.save(run);

        var response = get("/runtime/v1/resources/bank_statement/records", agent.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("DATA_MODE_MISMATCH");
    }

    @Test
    void aReboundOrDeletedFileDoesNotChangeWhatTheRunReads() {
        var agent = fx.runningAgent();
        Dataset first = fx.upload(agent.scenario().buyer(), "jan.csv", DataFixtures.CSV);
        fx.pin(agent.run(), "bank_statement", first);
        fx.bind(agent.scenario().connection(), "bank_statement",
                fx.upload(agent.scenario().buyer(), "feb.csv", "date,narration,amount\n2026-02-01,Feb,1\n"));

        var page = get("/runtime/v1/resources/bank_statement/records", agent.token());
        assertThat((List<?>) page.getBody().get("records")).hasSize(2);

        var delete = rest.exchange("http://localhost:" + port + "/api/v1/datasets/" + first.getId(), HttpMethod.DELETE,
                new HttpEntity<>(null, fx.bearer(agent.scenario().buyer(), UserRole.BUYER_ADMIN)), Map.class);
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void anObjectStoreFailureIs503NotAServerError() {
        var agent = fx.runningAgent();
        Dataset d = fx.upload(agent.scenario().buyer(), "jan.csv", DataFixtures.CSV);
        fx.pin(agent.run(), "bank_statement", d);
        storage.deleteQuietly(d.getRecordsKey());   // the blob is gone: the same failure path as an unreachable store

        var response = get("/runtime/v1/resources/bank_statement/records", agent.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().get("code")).isEqualTo("STORAGE_UNAVAILABLE");
    }
}
```

Edit `runtime/RuntimeResourceEndpointsTest.java` (the only existing test file changed): change `extends AbstractIntegrationTest` to `extends AbstractStorageIntegrationTest` (plus the import `com.agentstore.platform.AbstractStorageIntegrationTest`, drop the unused `AbstractIntegrationTest` import), and replace the first two tests:

```java
    @Test
    void aGrantedReadOfAnUnboundResourceIsNotFoundAndNotAViolation() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = call(HttpMethod.GET, "/runtime/v1/resources/bank_statement/records", agent.token(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("RESOURCE_NOT_BOUND");
        assertThat(violationSteps(agent.run().getId())).isZero();
    }

    @Test
    void aGrantedWriteIsAcceptedAndStagedBecauseTheScopeRequiresApproval() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = call(HttpMethod.POST, "/runtime/v1/resources/journal_entries/records", agent.token(),
                Map.of("records", List.of(Map.of("account", "1000"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("accepted")).isEqualTo(1);
        assertThat(response.getBody().get("staged")).isEqualTo(true);
        assertThat(violationSteps(agent.run().getId())).isZero();
    }
```

(The second one passes only after Task 8; mark it with `@org.junit.jupiter.api.Disabled("enabled in Task 8")` for now and remove the annotation in Task 8 Step 5.)

In `RuntimeContractTest.theContractDescribesOnlyTheRuntimeApi` add `"/runtime/v1/resources/{resource}/file/content"` to the `contains(...)` list.

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='DatasetResourceBackendReadTest'`
Expected: COMPILE FAIL (`RecordCursor`).

- [ ] **Step 3: Extend the runtime error model**

`runtime/RuntimeApiException.java`:

```java
package com.agentstore.platform.runtime;

import org.springframework.http.HttpStatus;

import java.util.List;

public class RuntimeApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<?> errors;

    public RuntimeApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    /** {@code errors} carries structured detail (for example row errors) into the problem body. */
    public RuntimeApiException(HttpStatus status, String code, String message, List<?> errors) {
        super(message);
        this.status = status;
        this.code = code;
        this.errors = errors;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<?> errors() {
        return errors;
    }
}
```

`runtime/ProblemBody.java`:

```java
package com.agentstore.platform.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;

import java.util.List;

/** RFC 7807 problem details plus the stable {@code code} the SDKs map to typed exceptions. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemBody(String type, String title, int status, String code, String detail, List<?> errors) {
    static ProblemBody of(HttpStatus status, String code, String detail) {
        return of(status, code, detail, null);
    }

    static ProblemBody of(HttpStatus status, String code, String detail, List<?> errors) {
        return new ProblemBody("about:blank", status.getReasonPhrase(), status.value(), code, detail, errors);
    }
}
```

In `runtime/RuntimeExceptionHandler.java`: change `problem(...)` to a 4-argument form and add the storage handler:

```java
    private static ResponseEntity<ProblemBody> problem(HttpStatus status, String code, String detail) {
        return problem(status, code, detail, null);
    }

    private static ResponseEntity<ProblemBody> problem(HttpStatus status, String code, String detail, java.util.List<?> errors) {
        return ResponseEntity.status(status)
                .contentType(MediaType.parseMediaType(ProblemResponses.PROBLEM_JSON))
                .body(ProblemBody.of(status, code, detail, errors));
    }

    @ExceptionHandler(RuntimeApiException.class)
    ResponseEntity<ProblemBody> api(RuntimeApiException e) {
        return problem(e.status(), e.code(), e.getMessage(), e.errors());
    }

    @ExceptionHandler(com.agentstore.platform.storage.StorageException.class)
    ResponseEntity<ProblemBody> storage(com.agentstore.platform.storage.StorageException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE",
                "The data store is unavailable right now; retry shortly");
    }
```

- [ ] **Step 4: Extend `ResourceBackend` and the controller, delete the stub**

In `runtime/ResourceBackend.java` add imports `java.io.InputStream` and `org.springframework.http.HttpStatus`, and inside the interface:

```java
    /** The bytes behind {@link #file}'s link, streamed under the run token. Backends that have no files may keep the default. */
    default FileContent fileContent(RunPrincipal principal, String resource) {
        throw new RuntimeApiException(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED", "File content is not available");
    }

    record FileContent(String contentType, long sizeBytes, InputStream stream) {}
```

Delete `runtime/UnavailableResourceBackend.java` (`git rm`).

In `runtime/RuntimeResourceController.java` add (imports: `io.swagger.v3.oas.annotations.Operation`, `io.swagger.v3.oas.annotations.media.Content`, `io.swagger.v3.oas.annotations.media.Schema`, `io.swagger.v3.oas.annotations.responses.ApiResponse`, `jakarta.servlet.http.HttpServletResponse`, `java.io.IOException`, `java.io.InputStream`):

```java
    /** Writes straight to the response (not StreamingResponseBody): the stateless run-token chain must not be re-entered on an async dispatch. */
    @GetMapping("/{resource}/file/content")
    @Operation(responses = @ApiResponse(responseCode = "200", description = "The file's bytes",
            content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary"))))
    public void fileContent(@PathVariable String resource, HttpServletResponse response) throws IOException {
        RunPrincipal principal = RunPrincipal.current();
        scopeGuard.read(principal, resource);
        ResourceBackend.FileContent content = backend.fileContent(principal, resource);
        try (InputStream in = content.stream()) {
            recorder.record(principal, RunStepKind.DATA_READ, Map.of("resource", resource, "file", true, "download", true));
            response.setContentType(content.contentType());
            response.setContentLengthLong(content.sizeBytes());
            in.transferTo(response.getOutputStream());
        }
    }
```

- [ ] **Step 5: Implement the cursor and the backend**

`data/RecordCursor.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.runtime.RuntimeApiException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Opaque page cursor: base64url of {@code v1:<first 8 hex of the file's sha>:<byte offset into records.jsonl>}. */
final class RecordCursor {

    private static final String VERSION = "v1";

    private RecordCursor() {
    }

    static String encode(String sha256, long offset) {
        String raw = VERSION + ":" + sha256.substring(0, 8) + ":" + offset;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static long decode(String cursor, String sha256) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split(":");
            if (parts.length == 3 && parts[0].equals(VERSION) && parts[1].equals(sha256.substring(0, 8))) {
                long offset = Long.parseLong(parts[2]);
                if (offset >= 0) {
                    return offset;
                }
            }
        } catch (IllegalArgumentException e) {
            // not base64, or not a number: fall through
        }
        throw new RuntimeApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR",
                "The cursor is not valid for this resource; start again without a cursor");
    }
}
```

`data/DatasetResourceBackend.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.manifest.ColumnType;
import com.agentstore.platform.manifest.ResourceDecl;
import com.agentstore.platform.manifest.ResourceSchema;
import com.agentstore.platform.runtime.ResourceBackend;
import com.agentstore.platform.runtime.RunPrincipal;
import com.agentstore.platform.runtime.RuntimeApiException;
import com.agentstore.platform.storage.ObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Serves a run's data from its pins only, so unplugging, replacing or purging a file mid-run cannot change what the
 * agent reads. It re-checks the dataset's purpose against the run's mode on every read (defense in depth).
 */
@Component
public class DatasetResourceBackend implements ResourceBackend {

    static final long LINK_TTL_SECONDS = 600;

    private final RunDatasetPinRepository pins;
    private final DatasetRepository datasets;
    private final ObjectStorage storage;
    private final RunOutputWriter writer;
    private final String baseUrl;

    public DatasetResourceBackend(RunDatasetPinRepository pins, DatasetRepository datasets, ObjectStorage storage,
                                  RunOutputWriter writer,
                                  @Value("${agentstore.callback.base-url}") String baseUrl) {
        this.pins = pins;
        this.datasets = datasets;
        this.storage = storage;
        this.writer = writer;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public ResourceMeta metadata(RunPrincipal principal, String resource) {
        Dataset dataset = pinned(principal, resource);
        return new ResourceMeta(resource, dataset.getFormat().wire(), dataset.getRowCount());
    }

    @Override
    public RecordPage records(RunPrincipal principal, String resource, String cursor, int limit) {
        Dataset dataset = pinned(principal, resource);
        if (!dataset.getFormat().tabular()) {
            throw new RuntimeApiException(HttpStatus.CONFLICT, "NOT_TABULAR",
                    "'" + resource + "' is a document; read it through the file endpoint");
        }
        long offset = cursor == null ? 0 : RecordCursor.decode(cursor, dataset.getSha256());
        long size = dataset.getRecordsSizeBytes();
        if (offset >= size) {
            return new RecordPage(List.of(), null);
        }
        ResourceSchema schema = principal.manifest().resource(resource).map(ResourceDecl::schema).orElse(null);
        List<Map<String, Object>> page = new ArrayList<>();
        long position = offset;
        try (InputStream in = new BufferedInputStream(storage.openRange(dataset.getRecordsKey(), offset, size - 1), 64 * 1024)) {
            while (page.size() < limit) {
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                int b;
                boolean any = false;
                while ((b = in.read()) != -1) {
                    position++;
                    any = true;
                    if (b == '\n') {
                        break;
                    }
                    line.write(b);
                }
                if (!any) {
                    break;
                }
                if (line.size() > 0) {
                    page.add(coerce(Jsonl.MAPPER.readValue(line.toByteArray(), Jsonl.ROW), schema));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new RecordPage(page, position >= size ? null : RecordCursor.encode(dataset.getSha256(), position));
    }

    @Override
    public FileLink file(RunPrincipal principal, String resource) {
        pinned(principal, resource);
        return new FileLink(baseUrl + "/runtime/v1/resources/" + resource + "/file/content",
                Instant.now().plusSeconds(LINK_TTL_SECONDS));
    }

    @Override
    public FileContent fileContent(RunPrincipal principal, String resource) {
        Dataset dataset = pinned(principal, resource);
        String type = switch (dataset.getFormat()) {
            case PDF -> "application/pdf";
            case CSV -> "text/csv";
            case JSON -> "application/json";
        };
        return new FileContent(type, dataset.getSizeBytes(), storage.open(dataset.getStorageKey()));
    }

    @Override
    public WriteResult write(RunPrincipal principal, String resource, List<Map<String, Object>> records,
                             boolean requiresApproval) {
        return writer.write(principal, resource, records, requiresApproval);
    }

    private Dataset pinned(RunPrincipal principal, String resource) {
        RunDatasetPin pin = pins.findByIdRunIdAndIdResourceName(principal.runId(), resource)
                .orElseThrow(() -> notBound(resource));
        Dataset dataset = datasets.findById(pin.getDatasetId()).orElseThrow(() -> notBound(resource));
        if (!DataAccessRules.allows(principal.mode(), dataset.getPurpose())) {
            throw new RuntimeApiException(HttpStatus.FORBIDDEN, "DATA_MODE_MISMATCH",
                    "This run's mode may not read that data");
        }
        if (dataset.getPurgedAt() != null || dataset.getStorageKey() == null) {
            throw new RuntimeApiException(HttpStatus.GONE, "DATASET_PURGED", "The file behind '" + resource + "' was removed");
        }
        return dataset;
    }

    private static RuntimeApiException notBound(String resource) {
        return new RuntimeApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_BOUND",
                "No file is bound to '" + resource + "' for this run");
    }

    /** The bind-time check proved every row coerces, so a failure here leaves the raw value rather than failing the read. */
    private static Map<String, Object> coerce(Map<String, Object> row, ResourceSchema schema) {
        row.remove(Normalized.EXTRA_KEY);
        if (schema == null) {
            return row;
        }
        for (Map.Entry<String, ColumnType> property : schema.properties().entrySet()) {
            String column = property.getKey();
            if (row.containsKey(column)) {
                try {
                    row.put(column, ValueCoercer.coerce(property.getValue(), row.get(column)));
                } catch (ValueCoercer.NotCoercible e) {
                    // leave as stored
                }
            }
        }
        return row;
    }
}
```

`data/RunOutputWriter.java` (stub; Task 8 replaces its body):

```java
package com.agentstore.platform.data;

import com.agentstore.platform.runtime.ResourceBackend;
import com.agentstore.platform.runtime.RunPrincipal;
import com.agentstore.platform.runtime.RuntimeApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Replaced in Task 8. */
@Service
public class RunOutputWriter {

    public ResourceBackend.WriteResult write(RunPrincipal principal, String resource, List<Map<String, Object>> records,
                                             boolean requiresApproval) {
        throw new RuntimeApiException(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED", "Writes arrive in the next task");
    }
}
```

- [ ] **Step 6: Run to verify they pass, then regenerate the contract**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='DatasetResourceBackendReadTest,RuntimeResourceEndpointsTest,RuntimeEndToEndTest,RuntimeEndpointMatrixTest,RuntimeAuthTest,ScopeGuardTest'`
Expected: PASS (the write test in `RuntimeResourceEndpointsTest` is disabled).

Then regenerate and review the contract diff (it should only add the `file/content` path):
`cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=RuntimeContractTest -Dcontract.update=true && git diff --stat ../contracts && mvn -q test -Dtest=RuntimeContractTest`
Expected: second run PASS; `git diff ../contracts/runtime-v1.yaml` shows only the new path.

- [ ] **Step 7: Commit**

```bash
git add -A backend contracts
git commit -m "feat(data): DatasetResourceBackend reads — metadata, byte-offset cursor, file streaming"
```

---

### Task 8: Writes and `run_output`, with the caps

**Files:**
- Modify: `data/RunOutputWriter.java` (real body), `runtime/SecurityConfig` wiring (`identity/SecurityConfig.java`), `runtime/RuntimeExceptionHandler.java`
- Create: `runtime/RuntimeBodyLimitFilter.java`
- Modify (tests): `runtime/RuntimeResourceEndpointsTest.java` (remove the `@Disabled`)
- Test: `data/RuntimeWriteTest.java`

**Interfaces:**
- Consumes: Tasks 2, 5, 7 (`SchemaChecker`, `Jsonl`, `RuntimeApiException(…, errors)`, `RunOutputRepository`).
- Produces: `RunOutputWriter.write(RunPrincipal, String resource, List<Map<String,Object>> records, boolean requiresApproval) : WriteResult`; constants `MAX_CALL_BYTES = 5 MiB`, `MAX_RUN_BYTES = 200 MiB`; `RuntimeBodyLimitFilter` (public, registered after `RuntimeAuthFilter`); a `run_output` row per accepted call, blob at `outputs/{runId}/{outputId}.jsonl`.

- [ ] **Step 1: Write the failing tests**

`data/RuntimeWriteTest.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.runtime.RuntimeFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tomcat stops swallowing an unread request body after 2 MB and then closes the connection, which would let the 413
 * for a 6 MB body race the client's write. Unlimited swallowing keeps these tests deterministic; a real client that
 * loses the race sees a reset or the 413, and either way nothing is stored.
 */
@TestPropertySource(properties = "server.tomcat.max-swallow-size=-1")
class RuntimeWriteTest extends AbstractStorageIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    DataFixtures fx;
    @Autowired
    RunOutputRepository outputRepository;

    @SuppressWarnings("rawtypes")
    org.springframework.http.ResponseEntity<Map> write(String token, String resource, List<Map<String, Object>> records) {
        return rest.exchange("http://localhost:" + port + "/runtime/v1/resources/" + resource + "/records",
                HttpMethod.POST, new HttpEntity<>(Map.of("records", records), RuntimeFixtures.bearer(token)), Map.class);
    }

    static Map<String, Object> entry(String bankLine, Object amount) {
        return Map.of("bank_line", bankLine, "amount", amount);
    }

    @Test
    void anApprovalScopeWriteIsStagedAsAJsonlBlobWithARow() throws Exception {
        var agent = fx.runningAgent();

        var response = write(agent.token(), "journal_entries", List.of(entry("l1", 10), entry("l2", "12.50")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("accepted")).isEqualTo(2);
        assertThat(response.getBody().get("staged")).isEqualTo(true);
        List<RunOutput> outputs = outputRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId());
        assertThat(outputs).hasSize(1);
        RunOutput output = outputs.get(0);
        assertThat(output.getStatus()).isEqualTo(OutputStatus.STAGED);
        assertThat(output.getRecordCount()).isEqualTo(2);
        assertThat(output.getResourceName()).isEqualTo("journal_entries");
        assertThat(output.getStorageKey()).isEqualTo("outputs/" + agent.run().getId() + "/" + output.getId() + ".jsonl");
        try (InputStream in = storage.open(output.getStorageKey())) {
            String[] lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n");
            assertThat(lines).hasSize(2);
            assertThat(lines[0]).contains("\"bank_line\":\"l1\"");
        }
        assertThat(storage.size(output.getStorageKey())).contains(output.getSizeBytes());
    }

    @Test
    void aScopeWithoutApprovalCommitsImmediately() {
        var agent = fx.runningAgent();

        var response = write(agent.token(), "audit_log", List.of(Map.of("event", "reconciled")));

        assertThat(response.getBody().get("staged")).isEqualTo(false);
        assertThat(outputRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId()))
                .extracting(RunOutput::getStatus).containsExactly(OutputStatus.COMMITTED);
    }

    @Test
    void aWriteThatFailsTheSchemaIs422WithRowErrorsAndStoresNothing() {
        var agent = fx.runningAgent();

        var missingKey = write(agent.token(), "journal_entries", List.of(Map.of("amount", 5)));
        var badType = write(agent.token(), "journal_entries", List.of(entry("l1", 1), entry("l2", "abc")));

        assertThat(missingKey.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(missingKey.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
        Map<?, ?> first = (Map<?, ?>) ((List<?>) missingKey.getBody().get("errors")).get(0);
        assertThat(first.get("row")).isEqualTo(1);
        assertThat(first.get("column")).isEqualTo("bank_line");
        Map<?, ?> second = (Map<?, ?>) ((List<?>) badType.getBody().get("errors")).get(0);
        assertThat(second.get("row")).isEqualTo(2);
        assertThat(second.get("column")).isEqualTo("amount");
        assertThat(outputRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId())).isEmpty();
    }

    @Test
    void onlyTheFirstFiftyRowErrorsComeBack() {
        var agent = fx.runningAgent();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            rows.add(entry("l" + i, "abc"));
        }

        var response = write(agent.token(), "journal_entries", rows);

        assertThat((List<?>) response.getBody().get("errors")).hasSize(50);
    }

    @Test
    void aBodyOverFiveMegabytesIs413BeforeItIsParsed() {
        var agent = fx.runningAgent();
        String big = "x".repeat(6 * 1024 * 1024);

        var response = write(agent.token(), "audit_log", List.of(Map.of("blob", big)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().get("code")).isEqualTo("WRITE_TOO_LARGE");
        assertThat(outputRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId())).isEmpty();
    }

    @Test
    void aChunkedBodyOverFiveMegabytesIsAlso413() throws Exception {
        var agent = fx.runningAgent();
        byte[] prefix = "{\"records\":[{\"blob\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}]}".getBytes(StandardCharsets.UTF_8);
        int filler = 6 * 1024 * 1024;
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/runtime/v1/resources/audit_log/records"))
                .header("Authorization", "Bearer " + agent.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.SequenceInputStream(
                        java.util.Collections.enumeration(List.of(
                                new java.io.ByteArrayInputStream(prefix),
                                new java.io.InputStream() {
                                    int left = filler;

                                    @Override
                                    public int read() {
                                        return left-- > 0 ? 'x' : -1;
                                    }
                                },
                                new java.io.ByteArrayInputStream(suffix))))))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("WRITE_TOO_LARGE");
    }

    @Test
    void theRunTotalIsCappedAt200Megabytes() {
        var agent = fx.runningAgent();
        outputRepository.save(new RunOutput(java.util.UUID.randomUUID(), agent.run().getId(), "audit_log",
                OutputStatus.COMMITTED, 1, 200L * 1024 * 1024 - 10, "outputs/preloaded"));

        var response = write(agent.token(), "audit_log", List.of(Map.of("event", "this pushes it over")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().get("code")).isEqualTo("WRITE_TOO_LARGE");
    }

    @Test
    void aWriteToAPdfOrUndeclaredResourceIsStillAScopeViolation() {
        var agent = fx.runningAgent();

        assertThat(write(agent.token(), "statement_pdf", List.of(Map.of("a", 1))).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(write(agent.token(), "payroll", List.of(Map.of("a", 1))).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(outputRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId())).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=RuntimeWriteTest`
Expected: FAIL (501 from the stub).

- [ ] **Step 3: Implement `RunOutputWriter`**

Replace `data/RunOutputWriter.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunTerminatedException;
import com.agentstore.platform.manifest.ResourceDecl;
import com.agentstore.platform.manifest.ResourceSchema;
import com.agentstore.platform.runtime.ResourceBackend;
import com.agentstore.platform.runtime.RunPrincipal;
import com.agentstore.platform.runtime.RuntimeApiException;
import com.agentstore.platform.storage.ObjectStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Validates an agent's whole write call, stores it as a JSONL chunk and records a {@code run_output} row. The blob is
 * written first and removed if the row cannot be inserted; the insert happens under the run's row lock so a write
 * cannot slip in after the run ended and be left STAGED forever.
 */
@Service
public class RunOutputWriter {

    static final int MAX_CALL_BYTES = 5 * 1024 * 1024;
    static final long MAX_RUN_BYTES = 200L * 1024 * 1024;

    private final RunOutputRepository outputs;
    private final RunRepository runs;
    private final ObjectStorage storage;
    private final TransactionTemplate tx;

    public RunOutputWriter(RunOutputRepository outputs, RunRepository runs, ObjectStorage storage, TransactionTemplate tx) {
        this.outputs = outputs;
        this.runs = runs;
        this.storage = storage;
        this.tx = tx;
    }

    public ResourceBackend.WriteResult write(RunPrincipal principal, String resource, List<Map<String, Object>> records,
                                             boolean requiresApproval) {
        validate(principal, resource, records);
        byte[] bytes = toJsonl(records);
        if (bytes.length > MAX_CALL_BYTES) {
            throw tooLarge("A single write may carry at most " + MAX_CALL_BYTES / (1024 * 1024) + " MB");
        }
        UUID outputId = UUID.randomUUID();
        String key = "outputs/" + principal.runId() + "/" + outputId + ".jsonl";
        storage.put(key, bytes);
        try {
            tx.executeWithoutResult(status -> {
                Run run = runs.findByIdForUpdate(principal.runId()).orElseThrow();
                if (run.getStatus().isTerminal()) {
                    throw new RunTerminatedException(run.getStatus());
                }
                if (outputs.totalBytes(principal.runId()) + bytes.length > MAX_RUN_BYTES) {
                    throw tooLarge("This run has written the most it may (" + MAX_RUN_BYTES / (1024 * 1024) + " MB)");
                }
                outputs.save(new RunOutput(outputId, principal.runId(), resource,
                        requiresApproval ? OutputStatus.STAGED : OutputStatus.COMMITTED, records.size(), bytes.length, key));
            });
        } catch (RuntimeException e) {
            storage.deleteQuietly(key);
            throw e;
        }
        return new ResourceBackend.WriteResult(records.size(), requiresApproval);
    }

    private void validate(RunPrincipal principal, String resource, List<Map<String, Object>> records) {
        ResourceSchema schema = principal.manifest().resource(resource).map(ResourceDecl::schema).orElse(null);
        // The required columns are part of the column set so a record that lacks one is reported by row, not only as a
        // missing column of the whole call.
        Set<String> columns = new LinkedHashSet<>();
        if (schema != null) {
            columns.addAll(schema.required());
        }
        records.forEach(r -> columns.addAll(r.keySet()));
        SchemaChecker.CheckResult result = SchemaChecker.check(schema, List.copyOf(columns), records.iterator());
        if (!result.ok()) {
            throw new RuntimeApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                    "The records do not match the schema of '" + resource + "'", result.rowErrors());
        }
    }

    private static byte[] toJsonl(List<Map<String, Object>> records) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (Map<String, Object> record : records) {
                out.write(Jsonl.MAPPER.writeValueAsBytes(record));
                out.write('\n');
                if (out.size() > MAX_CALL_BYTES) {
                    break;   // already too large; the caller rejects it, no need to serialize the rest
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static RuntimeApiException tooLarge(String message) {
        return new RuntimeApiException(HttpStatus.PAYLOAD_TOO_LARGE, "WRITE_TOO_LARGE", message);
    }
}
```

An empty-`records` write never reaches here (`@NotEmpty` on the request).

- [ ] **Step 4: Implement the transport-level limit**

`runtime/RuntimeBodyLimitFilter.java`:

```java
package com.agentstore.platform.runtime;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;

/**
 * Caps an agent's record-write body at the transport, before Jackson reads it into memory: a declared Content-Length
 * over the cap is refused outright, and a chunked body is cut off as it crosses the cap. Runs after RuntimeAuthFilter.
 */
public class RuntimeBodyLimitFilter extends OncePerRequestFilter {

    /** 5 MB of records plus the JSON envelope. */
    static final long MAX_WRITE_BODY_BYTES = 5L * 1024 * 1024 + 4096;
    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final String DETAIL = "A write may carry at most 5 MB";

    public static class PayloadTooLargeException extends IOException {
        PayloadTooLargeException() {
            super(DETAIL);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return !(HttpMethod.POST.matches(request.getMethod()) && MATCHER.match("/runtime/v1/resources/*/records", path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_WRITE_BODY_BYTES) {
            ProblemResponses.write(response, HttpStatus.PAYLOAD_TOO_LARGE, "WRITE_TOO_LARGE", DETAIL, null);
            return;
        }
        chain.doFilter(new LimitedRequest(request), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long seen;

                @Override
                public int read() throws IOException {
                    int b = delegate.read();
                    if (b >= 0) {
                        count(1);
                    }
                    return b;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int n = delegate.read(buffer, offset, length);
                    if (n > 0) {
                        count(n);
                    }
                    return n;
                }

                private void count(int n) throws IOException {
                    seen += n;
                    if (seen > MAX_WRITE_BODY_BYTES) {
                        throw new PayloadTooLargeException();
                    }
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    delegate.setReadListener(listener);
                }
            };
        }
    }
}
```

In `identity/SecurityConfig.java`, in `runtimeFilterChain`, after the existing `.addFilterBefore(new RuntimeAuthFilter(...), UsernamePasswordAuthenticationFilter.class)` add `.addFilterAfter(new RuntimeBodyLimitFilter(), RuntimeAuthFilter.class)` (import `com.agentstore.platform.runtime.RuntimeBodyLimitFilter`; keep the trailing `;` on the last call).

In `runtime/RuntimeExceptionHandler.java` replace the `unreadable` handler:

```java
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemBody> unreadable(HttpMessageNotReadableException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof RuntimeBodyLimitFilter.PayloadTooLargeException) {
                return problem(HttpStatus.PAYLOAD_TOO_LARGE, "WRITE_TOO_LARGE", "A write may carry at most 5 MB");
            }
        }
        return problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Request body is missing or not valid JSON");
    }
```

- [ ] **Step 5: Un-disable the write test and run everything runtime + data**

Remove the `@Disabled` annotation from `aGrantedWriteIsAcceptedAndStagedBecauseTheScopeRequiresApproval` in `RuntimeResourceEndpointsTest`.

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='RuntimeWriteTest,RuntimeResourceEndpointsTest,RuntimeEndToEndTest,RuntimeEndpointMatrixTest,RuntimeAuthTest,RuntimeContractTest,DatasetResourceBackendReadTest'`
Expected: PASS. If the chunked test hangs or reports a connection reset instead of 413 (Tomcat may close the connection when the handler stops reading), have the test also accept `IOException` from `client.send`, but keep asserting that no output row exists — the contract is "the body is never fully parsed and nothing is stored".

- [ ] **Step 6: Commit**

```bash
git add -A backend
git commit -m "feat(data): agent writes — schema-validated, staged in the object store, capped at the transport"
```

---

### Task 9: `GateOutcomeEvent`, `OutputCommitService`, run-end discard

**Files:**
- Create: `execution/GateOutcomeEvent.java`, `execution/RunFinishedEvent.java`, `data/OutputCommitService.java`
- Modify: `execution/GateService.java`, `execution/RunLifecycleService.java`
- Test: `data/StagedOutputsLifecycleTest.java`

**Interfaces:**
- Consumes: Task 8 (`RunOutputWriter`, `RunOutputRepository.transition/discardStaged`).
- Produces: `record GateOutcomeEvent(UUID runId, UUID gateId, GateStatus status, List<String> affectsResources, Instant requestedAt)` and `record RunFinishedEvent(UUID runId)`, both in `execution`, published synchronously (same transaction) with `ApplicationEventPublisher`.

- [ ] **Step 1: Write the failing test**

`data/StagedOutputsLifecycleTest.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.GateRequest;
import com.agentstore.platform.execution.GateService;
import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.runtime.RunPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Drives the real GateService and RunLifecycleService; nothing here is faked. */
class StagedOutputsLifecycleTest extends AbstractStorageIntegrationTest {

    @Autowired
    DataFixtures fx;
    @Autowired
    RunOutputWriter writer;
    @Autowired
    RunOutputRepository outputRepository;
    @Autowired
    GateService gateService;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    RunRepository runRepository;
    @Autowired
    ManifestParser parser;

    DataFixtures.RunningAgent agent;
    RunPrincipal principal;

    void setUp() {
        agent = fx.runningAgent();
        principal = RunPrincipal.forRun(runRepository.findById(agent.run().getId()).orElseThrow(), parser);
    }

    void stage(String resource) {
        writer.write(principal, resource, List.of(Map.of("bank_line", "l", "amount", 1)), true);
    }

    GateRequest openGate(String... affects) {
        String json = "[" + String.join(",", java.util.Arrays.stream(affects).map(a -> "\"" + a + "\"").toList()) + "]";
        return gateService.open(agent.run().getId(), "post_journal_entries", 5, "Post entries", "{}", json);
    }

    List<OutputStatus> statuses() {
        return outputRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId()).stream().map(RunOutput::getStatus).toList();
    }

    @Test
    void approvingTheGateCommitsTheStagedRowsAndStampsTheGate() {
        setUp();
        stage("journal_entries");
        GateRequest gate = openGate("journal_entries");

        gateService.decide(agent.run().getId(), gate.getId(), true, UUID.randomUUID(), "ok");

        assertThat(statuses()).containsExactly(OutputStatus.COMMITTED);
        assertThat(outputRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId()).get(0).getGateRequestId())
                .isEqualTo(gate.getId());
    }

    @Test
    void rejectingTheGateDiscardsTheStagedRows() {
        setUp();
        stage("journal_entries");
        GateRequest gate = openGate("journal_entries");

        gateService.decide(agent.run().getId(), gate.getId(), false, UUID.randomUUID(), "no");

        assertThat(statuses()).containsExactly(OutputStatus.DISCARDED);
        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void anExpiredGateDiscardsTheStagedRows() {
        setUp();
        stage("journal_entries");
        openGate("journal_entries");

        boolean ended = gateService.expire(agent.run().getId(), RunStatus.AWAITING_APPROVAL, Map.of("summary", "expired"));

        assertThat(ended).isTrue();
        assertThat(statuses()).containsExactly(OutputStatus.DISCARDED);
    }

    @Test
    void aRunThatEndsWithoutEverAskingDiscardsWhatItStaged() {
        setUp();
        stage("journal_entries");

        lifecycle.complete(agent.run().getId(), Map.of("summary", "done"));

        assertThat(statuses()).containsExactly(OutputStatus.DISCARDED);
    }

    @Test
    void aRunThatFailsWhileAGateIsOpenDiscardsToo() {
        setUp();
        stage("journal_entries");
        openGate("journal_entries");

        lifecycle.fail(agent.run().getId(), Map.of("summary", "boom"));

        assertThat(statuses()).containsExactly(OutputStatus.DISCARDED);
    }

    @Test
    void anApprovedGateWithNoAffectedResourcesCommitsNothing() {
        setUp();
        stage("journal_entries");
        GateRequest gate = openGate();

        gateService.decide(agent.run().getId(), gate.getId(), true, UUID.randomUUID(), "ok");

        assertThat(statuses()).containsExactly(OutputStatus.STAGED);
        lifecycle.complete(agent.run().getId(), Map.of());
        assertThat(statuses()).containsExactly(OutputStatus.DISCARDED);
    }

    @Test
    void aGateOnlyCommitsRowsStagedBeforeItWasRequestedAndOnlyForTheListedResources() throws Exception {
        setUp();
        stage("journal_entries");                                    // before the gate
        outputRepository.save(new RunOutput(UUID.randomUUID(), agent.run().getId(), "other_resource",
                OutputStatus.STAGED, 1, 10, "outputs/other"));       // before the gate, but not listed
        GateRequest gate = openGate("journal_entries");
        Thread.sleep(5);
        stage("journal_entries");                                    // after the gate was requested

        gateService.decide(agent.run().getId(), gate.getId(), true, UUID.randomUUID(), "ok");

        List<RunOutput> rows = outputRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId());
        assertThat(rows).extracting(RunOutput::getResourceName, RunOutput::getStatus).containsExactly(
                org.assertj.core.groups.Tuple.tuple("journal_entries", OutputStatus.COMMITTED),
                org.assertj.core.groups.Tuple.tuple("other_resource", OutputStatus.STAGED),
                org.assertj.core.groups.Tuple.tuple("journal_entries", OutputStatus.STAGED));

        lifecycle.complete(agent.run().getId(), Map.of());
        assertThat(statuses()).containsExactly(OutputStatus.COMMITTED, OutputStatus.DISCARDED, OutputStatus.DISCARDED);
    }
}
```

The created-at ordering assertion relies on `findByRunIdOrderByCreatedAtAsc`; `other_resource` is saved between the two `stage` calls, so it is second.

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=StagedOutputsLifecycleTest`
Expected: FAIL (rows stay STAGED after approve).

- [ ] **Step 3: Implement events, publishers and the listener**

`execution/GateOutcomeEvent.java`:

```java
package com.agentstore.platform.execution;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A gate reached its final status. Published inside the deciding transaction so what it triggers (committing or
 * discarding staged outputs) succeeds or fails together with the decision. {@code requestedAt} is when the gate was
 * opened: only outputs staged before then are covered by it.
 */
public record GateOutcomeEvent(UUID runId, UUID gateId, GateStatus status, List<String> affectsResources,
                               Instant requestedAt) {
}
```

`execution/RunFinishedEvent.java`:

```java
package com.agentstore.platform.execution;

import java.util.UUID;

/** A run reached a terminal state. Published from {@code RunLifecycleService.finish}, in its transaction. */
public record RunFinishedEvent(UUID runId) {
}
```

In `GateService.java`:
- add imports `org.springframework.context.ApplicationEventPublisher`, `com.fasterxml.jackson.core.type.TypeReference`, `java.util.List`;
- add field `private final ApplicationEventPublisher events;` and a last constructor parameter `ApplicationEventPublisher events`;
- in `decide`, immediately after `gateRequestRepository.save(gate);` inside the `if (found.isPresent())` block, add:
  ```java
            events.publishEvent(new GateOutcomeEvent(runId, gate.getId(), gate.getStatus(),
                    affected(gate), gate.getCreatedAt()));
  ```
- add the helper (also used by `RunLifecycleService`, so make it `static` package-visible and put it in `GateOutcomeEvent`… simplest: put it on `GateRequest`). In `execution/GateRequest.java` add:
  ```java
    /** The resource names this gate covers, parsed from {@code affects_resources}. */
    public java.util.List<String> affected() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(affectsResources,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
        } catch (Exception e) {
            return java.util.List.of();
        }
    }
  ```
  and use `gate.affected()` in the event above (drop the `affected(gate)` helper and the extra imports).

In `RunLifecycleService.java`:
- add `import org.springframework.context.ApplicationEventPublisher;`, field `private final ApplicationEventPublisher events;` and a last constructor parameter `ApplicationEventPublisher events` (assign it);
- in `finish`, change the gate-closing lambda and add the run-finished event:
  ```java
        gateRequestRepository.findFirstByRunIdAndStatusOrderByCreatedAtAsc(run.getId(), GateStatus.PENDING).ifPresent(gate -> {
            gate.setStatus(GateStatus.EXPIRED);
            gate.setDecisionNote("The run ended before a decision was made");
            gate.setDecidedAt(Instant.now());
            gateRequestRepository.save(gate);
            events.publishEvent(new GateOutcomeEvent(run.getId(), gate.getId(), GateStatus.EXPIRED, gate.affected(),
                    gate.getCreatedAt()));
        });
        events.publishEvent(new RunFinishedEvent(run.getId()));
  ```
  (place the `publishEvent(new RunFinishedEvent…)` line right after the `ifPresent` block, before the sandbox kill).

`data/OutputCommitService.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.execution.GateOutcomeEvent;
import com.agentstore.platform.execution.GateStatus;
import com.agentstore.platform.execution.RunFinishedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns gate outcomes into output states. Plain {@code @EventListener} on purpose: it runs in the publisher's
 * transaction, so the decision and its effect are atomic.
 */
@Component
public class OutputCommitService {

    private final RunOutputRepository outputs;

    public OutputCommitService(RunOutputRepository outputs) {
        this.outputs = outputs;
    }

    @EventListener
    public void onGateOutcome(GateOutcomeEvent event) {
        // An approved gate with no listed resources commits nothing: the agent must say what to commit.
        if (event.affectsResources().isEmpty() || event.status() == GateStatus.PENDING) {
            return;
        }
        boolean approved = event.status() == GateStatus.APPROVED;
        outputs.transition(event.runId(), OutputStatus.STAGED, approved ? OutputStatus.COMMITTED : OutputStatus.DISCARDED,
                approved ? event.gateId() : null, event.affectsResources(), event.requestedAt());
    }

    @EventListener
    public void onRunFinished(RunFinishedEvent event) {
        outputs.discardStaged(event.runId());
    }
}
```

- [ ] **Step 4: Run to verify it passes, plus all gate and lifecycle tests**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='StagedOutputsLifecycleTest,GateServiceTest,GateClosedWhenRunEndsTest,GateDecisionEndpointTest,RunLifecycleServiceTest,RunLifecycleRuntimeTest,RunReaperTest,ApprovalGateTest,RuntimeGateEndpointsTest,RuntimeEndToEndTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A backend
git commit -m "feat(data): commit or discard staged outputs on gate outcome and run end"
```

---

### Task 10: Outputs API

**Files:**
- Create: `data/OutputFormats.java`, `data/OutputsController.java`
- Test: `data/OutputFormatsTest.java`, `data/OutputsApiTest.java`

**Interfaces:**
- Consumes: Tasks 2, 8 (`RunOutputRepository`, `RunOutputWriter`).
- Produces: `GET /api/v1/runs/{runId}/outputs[?preview=N]` → `[{id, resource, status, recordCount, createdAt, rows?}]` (`rows` only with `preview`, first `min(N,50)` rows of STAGED/COMMITTED outputs); `GET /api/v1/runs/{runId}/outputs/{outputId}/download?format=csv|json` (COMMITTED only). `OutputFormats.toCsv(List<Map<String,Object>>) : String`, `OutputFormats.toJsonArray(List<String> jsonlLines) : String`.

- [ ] **Step 1: Write the failing tests**

`data/OutputFormatsTest.java`:

```java
package com.agentstore.platform.data;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OutputFormatsTest {

    static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    @Test
    void headerIsTheUnionOfKeysInFirstSeenOrderAndMissingCellsAreEmpty() {
        String csv = OutputFormats.toCsv(List.of(row("a", 1, "b", "x"), row("c", true, "a", 2)));

        assertThat(csv).isEqualTo("a,b,c\r\n1,x,\r\n2,,true\r\n");
    }

    @Test
    void commasQuotesAndNewlinesAreEscaped() {
        String csv = OutputFormats.toCsv(List.of(row("note", "a,\"b\"\nc")));

        assertThat(csv).isEqualTo("note\r\n\"a,\"\"b\"\"\nc\"\r\n");
    }

    @Test
    void csvDownloadNeutralisesFormulas() {
        String csv = OutputFormats.toCsv(List.of(
                row("v", "=HYPERLINK(\"http://evil\")"), row("v", "+1+1"), row("v", "-2+3"), row("v", "@SUM(A1)"),
                row("v", "\tcmd"), row("v", "\rcmd"), row("v", "plain")));

        assertThat(csv).contains("'=HYPERLINK(").contains("'+1+1").contains("'-2+3").contains("'@SUM(A1)")
                .contains("'\tcmd").contains("plain");
        assertThat(csv).doesNotContain("\r\n=").doesNotContain("\r\n+").doesNotContain("\r\n@");
    }

    @Test
    void realNumbersAreNotPrefixedEvenWhenNegative() {
        String csv = OutputFormats.toCsv(List.of(row("amount", -5), row("amount", new java.math.BigDecimal("-12.50"))));

        assertThat(csv).isEqualTo("amount\r\n-5\r\n-12.50\r\n");
    }

    @Test
    void nestedValuesBecomeJsonText() {
        String csv = OutputFormats.toCsv(List.of(row("tags", List.of("a", "b"))));

        assertThat(csv).isEqualTo("tags\r\n\"[\"\"a\"\",\"\"b\"\"]\"\r\n");
    }

    @Test
    void jsonlLinesBecomeAJsonArray() {
        assertThat(OutputFormats.toJsonArray(List.of("{\"a\":1}", "{\"a\":2}"))).isEqualTo("[{\"a\":1},{\"a\":2}]");
        assertThat(OutputFormats.toJsonArray(List.of())).isEqualTo("[]");
    }
}
```

`data/OutputsApiTest.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.runtime.RunPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OutputsApiTest extends AbstractStorageIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    DataFixtures fx;
    @Autowired
    RunOutputWriter writer;
    @Autowired
    RunOutputRepository outputRepository;
    @Autowired
    com.agentstore.platform.execution.RunRepository runRepository;
    @Autowired
    ManifestParser parser;

    /** Insertion-ordered: the CSV header follows key order, and Map.of's order is not stable. */
    static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    RunOutput write(DataFixtures.RunningAgent agent, String resource, boolean approval, List<Map<String, Object>> rows) {
        var principal = RunPrincipal.forRun(runRepository.findById(agent.run().getId()).orElseThrow(), parser);
        writer.write(principal, resource, rows, approval);
        List<RunOutput> all = outputRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId());
        return all.get(all.size() - 1);
    }

    @SuppressWarnings("rawtypes")
    org.springframework.http.ResponseEntity<Map> call(String path, com.agentstore.platform.identity.Org org, UserRole role) {
        return rest.exchange("http://localhost:" + port + path, HttpMethod.GET,
                new HttpEntity<>(null, fx.bearer(org, role)), Map.class);
    }

    @Test
    void listsOutputsWithStatusAndAnOptionalPreview() {
        var agent = fx.runningAgent();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            rows.add(Map.of("event", "e" + i));
        }
        write(agent, "audit_log", false, rows);
        write(agent, "journal_entries", true, List.of(Map.of("bank_line", "l1", "amount", 1)));

        var list = rest.exchange("http://localhost:" + port + "/api/v1/runs/" + agent.run().getId() + "/outputs?preview=50",
                HttpMethod.GET, new HttpEntity<>(null, fx.bearer(agent.scenario().buyer(), UserRole.BUYER_APPROVER)), List.class);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).hasSize(2);
        Map<?, ?> audit = (Map<?, ?>) list.getBody().get(0);
        assertThat(audit.get("resource")).isEqualTo("audit_log");
        assertThat(audit.get("status")).isEqualTo("COMMITTED");
        assertThat(audit.get("recordCount")).isEqualTo(60);
        assertThat((List<?>) audit.get("rows")).hasSize(50);
        assertThat(((Map<?, ?>) list.getBody().get(1)).get("status")).isEqualTo("STAGED");

        var noPreview = rest.exchange("http://localhost:" + port + "/api/v1/runs/" + agent.run().getId() + "/outputs",
                HttpMethod.GET, new HttpEntity<>(null, fx.bearer(agent.scenario().buyer(), UserRole.BUYER_ADMIN)), List.class);
        assertThat(((Map<?, ?>) noPreview.getBody().get(0)).containsKey("rows")).isFalse();
    }

    @Test
    void onlyTheRunsBuyerOrgMaySeeThem() {
        var agent = fx.runningAgent();
        var other = fx.scenario();
        write(agent, "audit_log", false, List.of(Map.of("event", "e")));

        var response = call("/api/v1/runs/" + agent.run().getId() + "/outputs", other.buyer(), UserRole.BUYER_ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aCommittedOutputDownloadsAsCsvWithFormulasNeutralised() {
        var agent = fx.runningAgent();
        RunOutput output = write(agent, "audit_log", false,
                List.of(row("event", "=cmd|' /C calc'!A0", "amount", -5), row("event", "ok", "extra", "a,b")));

        var response = rest.exchange("http://localhost:" + port + "/api/v1/runs/" + agent.run().getId() + "/outputs/"
                        + output.getId() + "/download?format=csv", HttpMethod.GET,
                new HttpEntity<>(null, fx.bearer(agent.scenario().buyer(), UserRole.BUYER_APPROVER)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("attachment").contains("audit_log");
        assertThat(response.getBody()).startsWith("event,amount,extra\r\n").contains("'=cmd|").contains(",-5,")
                .contains("ok,,\"a,b\"");
    }

    @Test
    void aCommittedOutputDownloadsAsAJsonArray() {
        var agent = fx.runningAgent();
        RunOutput output = write(agent, "audit_log", false, List.of(row("event", "a"), row("event", "b")));

        var response = rest.exchange("http://localhost:" + port + "/api/v1/runs/" + agent.run().getId() + "/outputs/"
                        + output.getId() + "/download?format=json", HttpMethod.GET,
                new HttpEntity<>(null, fx.bearer(agent.scenario().buyer(), UserRole.BUYER_ADMIN)), String.class);

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isEqualTo("[{\"event\":\"a\"},{\"event\":\"b\"}]");
    }

    @Test
    void stagedDiscardedAndUnknownOutputsCannotBeDownloadedAndBadFormatsAreRejected() {
        var agent = fx.runningAgent();
        RunOutput staged = write(agent, "journal_entries", true, List.of(Map.of("bank_line", "l", "amount", 1)));
        RunOutput committed = write(agent, "audit_log", false, List.of(Map.of("event", "a")));
        String base = "/api/v1/runs/" + agent.run().getId() + "/outputs/";
        var org = agent.scenario().buyer();

        var notCommitted = call(base + staged.getId() + "/download", org, UserRole.BUYER_ADMIN);
        staged.setStatus(OutputStatus.DISCARDED);
        outputRepository.save(staged);
        var discarded = call(base + staged.getId() + "/download", org, UserRole.BUYER_ADMIN);
        var badFormat = call(base + committed.getId() + "/download?format=xlsx", org, UserRole.BUYER_ADMIN);
        var unknown = call(base + java.util.UUID.randomUUID() + "/download", org, UserRole.BUYER_ADMIN);

        assertThat(notCommitted.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notCommitted.getBody().get("code")).isEqualTo("OUTPUT_NOT_COMMITTED");
        assertThat(discarded.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(badFormat.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badFormat.getBody().get("code")).isEqualTo("INVALID_FORMAT");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='OutputFormatsTest,OutputsApiTest'`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement**

`data/OutputFormats.java`:

```java
package com.agentstore.platform.data;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Renders an output's records for download. A blob is at most 5 MB, so this works in memory. */
public final class OutputFormats {

    private OutputFormats() {
    }

    /**
     * RFC 4180 CSV with CRLF line ends. Agent-written text is untrusted: a string cell that a spreadsheet would read as
     * a formula (leading {@code = + - @}, tab or CR) gets a leading apostrophe. Real JSON numbers stay as they are, so
     * {@code -5} is still a number.
     */
    public static String toCsv(List<Map<String, Object>> rows) {
        Set<String> header = new LinkedHashSet<>();
        rows.forEach(r -> header.addAll(r.keySet()));
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", header.stream().map(OutputFormats::cell).toList())).append("\r\n");
        for (Map<String, Object> row : rows) {
            boolean first = true;
            for (String column : header) {
                if (!first) {
                    csv.append(',');
                }
                first = false;
                csv.append(cell(row.get(column)));
            }
            csv.append("\r\n");
        }
        return csv.toString();
    }

    public static String toJsonArray(List<String> jsonlLines) {
        return "[" + String.join(",", jsonlLines) + "]";
    }

    private static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text;
        boolean textual;
        if (value instanceof Number || value instanceof Boolean) {
            text = value.toString();
            textual = false;
        } else if (value instanceof String s) {
            text = s;
            textual = true;
        } else {
            try {
                text = Jsonl.MAPPER.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                text = String.valueOf(value);
            }
            textual = true;
        }
        if (textual && !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        boolean quote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0;
        return quote ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }
}
```

`data/OutputsController.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.storage.ObjectStorage;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/runs/{runId}/outputs")
@PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
public class OutputsController {

    static final int MAX_PREVIEW = 50;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutputResponse(UUID id, String resource, OutputStatus status, int recordCount, Instant createdAt,
                                 List<Map<String, Object>> rows) {
    }

    private final RunRepository runs;
    private final RunOutputRepository outputs;
    private final ObjectStorage storage;

    public OutputsController(RunRepository runs, RunOutputRepository outputs, ObjectStorage storage) {
        this.runs = runs;
        this.outputs = outputs;
        this.storage = storage;
    }

    @GetMapping
    public List<OutputResponse> list(@PathVariable UUID runId, @RequestParam(value = "preview", required = false) Integer preview) {
        requireOwnRun(runId);
        int rows = preview == null ? 0 : Math.max(0, Math.min(preview, MAX_PREVIEW));
        return outputs.findByRunIdOrderByCreatedAtAsc(runId).stream().map(o -> new OutputResponse(o.getId(),
                o.getResourceName(), o.getStatus(), o.getRecordCount(), o.getCreatedAt(),
                preview != null && o.getStatus() != OutputStatus.DISCARDED && o.getStorageKey() != null
                        ? preview(o, rows) : null)).toList();
    }

    @GetMapping("/{outputId}/download")
    public void download(@PathVariable UUID runId, @PathVariable UUID outputId,
                         @RequestParam(value = "format", defaultValue = "csv") String format,
                         HttpServletResponse response) throws IOException {
        requireOwnRun(runId);
        if (!format.equals("csv") && !format.equals("json")) {
            throw new DataApiException(HttpStatus.BAD_REQUEST, "INVALID_FORMAT", "format must be csv or json");
        }
        RunOutput output = outputs.findByIdAndRunId(outputId, runId)
                .orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Output not found"));
        if (output.getStatus() != OutputStatus.COMMITTED || output.getStorageKey() == null) {
            throw new DataApiException(HttpStatus.CONFLICT, "OUTPUT_NOT_COMMITTED",
                    "Only committed outputs can be downloaded; this one is " + output.getStatus());
        }
        List<String> lines = readLines(output.getStorageKey(), Integer.MAX_VALUE);
        String body;
        if (format.equals("csv")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String line : lines) {
                rows.add(Jsonl.MAPPER.readValue(line, Jsonl.ROW));
            }
            body = OutputFormats.toCsv(rows);
            response.setContentType("text/csv;charset=UTF-8");
        } else {
            body = OutputFormats.toJsonArray(lines);
            response.setContentType("application/json");
        }
        String safeName = output.getResourceName().replaceAll("[^A-Za-z0-9_.-]", "_");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + safeName + "-"
                + output.getId().toString().substring(0, 8) + "." + format + "\"");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }

    private List<Map<String, Object>> preview(RunOutput output, int max) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (String line : readLines(output.getStorageKey(), max)) {
                rows.add(Jsonl.MAPPER.readValue(line, Jsonl.ROW));
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return rows;
    }

    private List<String> readLines(String key, int max) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(storage.open(key), StandardCharsets.UTF_8))) {
            String line;
            while (lines.size() < max && (line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    private void requireOwnRun(UUID runId) {
        Run run = runs.findById(runId)
                .orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Run not found"));
        if (!run.getConnection().getBuyerOrg().getId().equals(Caller.current().orgId())) {
            throw new DataApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Run not owned by caller's org");
        }
    }
}
```

- [ ] **Step 4: Run to verify they pass**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='OutputFormatsTest,OutputsApiTest,RunDetailControllerTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A backend
git commit -m "feat(data): outputs API with preview and CSV/JSON download"
```

---

### Task 11: Retention job and tombstones

**Files:**
- Create: `data/DataRetentionService.java`, `data/DataRetentionJob.java`
- Test: `data/DataRetentionTest.java`

**Interfaces:**
- Consumes: Tasks 2, 4, 6 (`DatasetRepository.findByIdForUpdate/findDueForPurge`, `RunDatasetPinRepository.pinnedByOpenRun`, `DatasetBindingRepository.existsByDatasetId`, `RunOutputRepository`).
- Produces: `DataRetentionService.purgeOnce() : Summary`, `purgeOnce(Instant now) : Summary`, `record Summary(int datasetsPurged, int outputBlobsDeleted)`; `DataRetentionJob` (hourly, ShedLock `dataRetention`). A purged dataset keeps its row with `storageKey = recordsKey = null`, `purgedAt` set.

- [ ] **Step 1: Write the failing test**

`data/DataRetentionTest.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.runtime.RunPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DataRetentionTest extends AbstractStorageIntegrationTest {

    @Autowired
    DataFixtures fx;
    @Autowired
    DataRetentionService retention;
    @Autowired
    DatasetService datasetService;
    @Autowired
    DatasetRepository datasetRepository;
    @Autowired
    RunOutputRepository outputRepository;
    @Autowired
    RunOutputWriter writer;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    ManifestParser parser;

    @Test
    void anExplicitlyDeletedFileIsPurgedToATombstone() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "a.csv", DataFixtures.CSV);
        String original = d.getStorageKey();
        String records = d.getRecordsKey();
        datasetService.delete(s.buyer().getId(), d.getId());

        DataRetentionService.Summary summary = retention.purgeOnce(Instant.now().plusSeconds(1));

        assertThat(summary.datasetsPurged()).isGreaterThanOrEqualTo(1);
        Dataset after = datasetRepository.findById(d.getId()).orElseThrow();       // the row is a tombstone, not deleted
        assertThat(after.getPurgedAt()).isNotNull();
        assertThat(after.getStorageKey()).isNull();
        assertThat(after.getRecordsKey()).isNull();
        assertThat(after.getDisplayName()).isEqualTo("a.csv");
        assertThat(after.getSha256()).isEqualTo(d.getSha256());
        assertThat(storage.size(original)).isEmpty();
        assertThat(storage.size(records)).isEmpty();
        assertThat(datasetRepository.usedBytes(s.buyer().getId())).isZero();
    }

    @Test
    void anUnpluggedFileWaitsThirtyDays() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "a.csv", DataFixtures.CSV);
        fx.bind(s.connection(), "bank_statement", d);
        fx.unbindAll(s.connection());

        retention.purgeOnce(Instant.now().plus(Duration.ofDays(29)));
        assertThat(datasetRepository.findById(d.getId()).orElseThrow().getPurgedAt()).isNull();

        retention.purgeOnce(Instant.now().plus(Duration.ofDays(31)));
        assertThat(datasetRepository.findById(d.getId()).orElseThrow().getPurgedAt()).isNotNull();
    }

    @Test
    void aFileAnOpenRunPinsIsNeverPurgedEvenWhenDue() {
        var agent = fx.runningAgent();
        Dataset d = fx.upload(agent.scenario().buyer(), "a.csv", DataFixtures.CSV);
        fx.pin(agent.run(), "bank_statement", d);
        d.setPurgeAfter(Instant.now().minusSeconds(60));
        datasetRepository.save(d);

        retention.purgeOnce(Instant.now());
        assertThat(datasetRepository.findById(d.getId()).orElseThrow().getPurgedAt()).isNull();
        assertThat(storage.size(d.getRecordsKey())).isPresent();

        lifecycle.complete(agent.run().getId(), Map.of());
        retention.purgeOnce(Instant.now());
        assertThat(datasetRepository.findById(d.getId()).orElseThrow().getPurgedAt()).isNotNull();
    }

    @Test
    void aFileThatIsStillBoundIsNeverPurged() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "a.csv", DataFixtures.CSV);
        fx.bind(s.connection(), "bank_statement", d);
        d.setPurgeAfter(Instant.now().minusSeconds(60));     // stale timestamp; the binding wins
        datasetRepository.save(d);

        retention.purgeOnce(Instant.now());

        assertThat(datasetRepository.findById(d.getId()).orElseThrow().getPurgedAt()).isNull();
    }

    @Test
    void discardedOutputBlobsAreDeletedAfterThirtyDaysAndCommittedOnesNever() {
        var agent = fx.runningAgent();
        var principal = RunPrincipal.forRun(runRepository.findById(agent.run().getId()).orElseThrow(), parser);
        writer.write(principal, "journal_entries", List.of(Map.of("bank_line", "l", "amount", 1)), true);   // becomes DISCARDED
        writer.write(principal, "audit_log", List.of(Map.of("event", "e")), false);                          // COMMITTED
        lifecycle.complete(agent.run().getId(), Map.of());
        List<RunOutput> outputs = outputRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId());
        RunOutput discarded = outputs.stream().filter(o -> o.getStatus() == OutputStatus.DISCARDED).findFirst().orElseThrow();
        RunOutput committed = outputs.stream().filter(o -> o.getStatus() == OutputStatus.COMMITTED).findFirst().orElseThrow();
        String discardedKey = discarded.getStorageKey();

        retention.purgeOnce(Instant.now().plus(Duration.ofDays(29)));
        assertThat(storage.size(discardedKey)).isPresent();

        retention.purgeOnce(Instant.now().plus(Duration.ofDays(31)));
        assertThat(storage.size(discardedKey)).isEmpty();
        assertThat(outputRepository.findById(discarded.getId()).orElseThrow().getStorageKey()).isNull();
        assertThat(outputRepository.findById(committed.getId()).orElseThrow().getStorageKey()).isNotNull();
        assertThat(storage.size(committed.getStorageKey())).isPresent();
    }

    @Test
    void purgeIsIdempotent() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "a.csv", DataFixtures.CSV);
        datasetService.delete(s.buyer().getId(), d.getId());

        retention.purgeOnce(Instant.now().plusSeconds(1));
        Instant firstPurge = datasetRepository.findById(d.getId()).orElseThrow().getPurgedAt();
        retention.purgeOnce(Instant.now().plusSeconds(2));

        assertThat(datasetRepository.findById(d.getId()).orElseThrow().getPurgedAt()).isEqualTo(firstPurge);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=DataRetentionTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement**

`data/DataRetentionService.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * Hourly cleanup. A dataset whose grace ended (explicit delete: right away; unplugged: 30 days) is purged unless it is
 * bound again or an open run pins it: blobs deleted, keys nulled, {@code purged_at} set. The row stays as a tombstone
 * (name, size, sha) because run pins and traces refer to it; no buyer content survives. Blobs of DISCARDED outputs go
 * after 30 days. COMMITTED outputs are kept.
 */
@Service
public class DataRetentionService {

    static final Duration DISCARDED_OUTPUT_RETENTION = Duration.ofDays(30);
    private static final Logger log = LoggerFactory.getLogger(DataRetentionService.class);

    public record Summary(int datasetsPurged, int outputBlobsDeleted) {
    }

    private final DatasetRepository datasets;
    private final DatasetBindingRepository bindings;
    private final RunDatasetPinRepository pins;
    private final RunOutputRepository outputs;
    private final ObjectStorage storage;
    private final TransactionTemplate tx;

    public DataRetentionService(DatasetRepository datasets, DatasetBindingRepository bindings,
                                RunDatasetPinRepository pins, RunOutputRepository outputs, ObjectStorage storage,
                                TransactionTemplate tx) {
        this.datasets = datasets;
        this.bindings = bindings;
        this.pins = pins;
        this.outputs = outputs;
        this.storage = storage;
        this.tx = tx;
    }

    public Summary purgeOnce() {
        return purgeOnce(Instant.now());
    }

    public Summary purgeOnce(Instant now) {
        int purged = 0;
        for (Dataset due : datasets.findDueForPurge(now)) {
            try {
                if (Boolean.TRUE.equals(tx.execute(status -> purge(due.getId(), now)))) {
                    purged++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not purge dataset {}", due.getId(), e);   // the next run retries it
            }
        }
        int deleted = 0;
        for (RunOutput output : outputs.findByStatusAndCreatedAtBeforeAndStorageKeyIsNotNull(OutputStatus.DISCARDED,
                now.minus(DISCARDED_OUTPUT_RETENTION))) {
            try {
                storage.deleteQuietly(output.getStorageKey());
                output.setStorageKey(null);
                outputs.save(output);
                deleted++;
            } catch (RuntimeException e) {
                log.warn("Could not clear discarded output {}", output.getId(), e);
            }
        }
        return new Summary(purged, deleted);
    }

    /** Under the dataset's row lock, so a bind or delete cannot interleave. */
    private boolean purge(java.util.UUID datasetId, Instant now) {
        Dataset dataset = datasets.findByIdForUpdate(datasetId).orElse(null);
        if (dataset == null || dataset.getPurgedAt() != null || dataset.getPurgeAfter() == null
                || dataset.getPurgeAfter().isAfter(now)) {
            return false;
        }
        if (bindings.existsByDatasetId(datasetId) || pins.pinnedByOpenRun(datasetId)) {
            return false;
        }
        if (dataset.getStorageKey() != null) {
            storage.deleteQuietly(dataset.getStorageKey());
        }
        if (dataset.getRecordsKey() != null) {
            storage.deleteQuietly(dataset.getRecordsKey());
        }
        dataset.setStorageKey(null);
        dataset.setRecordsKey(null);
        dataset.setPurgedAt(Instant.now());
        datasets.save(dataset);
        return true;
    }
}
```

`data/DataRetentionJob.java`:

```java
package com.agentstore.platform.data;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class DataRetentionJob {

    private final DataRetentionService retention;

    public DataRetentionJob(DataRetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${agentstore.data.retention-interval:PT1H}",
            initialDelayString = "${agentstore.data.retention-interval:PT1H}")
    @SchedulerLock(name = "dataRetention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        retention.purgeOnce();
    }
}
```

- [ ] **Step 4: Run to verify it passes, plus the scheduling wiring test**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='DataRetentionTest,SchedulingWiringTest,BindingApiTest,DatasetApiTest'`
Expected: PASS. (`SchedulingWiringTest` enables jobs with `agentstore.data.retention-interval` at its default of one hour, so the new job does not fire during it.)

- [ ] **Step 5: Commit**

```bash
git add -A backend
git commit -m "feat(data): hourly retention job — purge to tombstones and clear discarded output blobs"
```

---

### Task 12: Docs and ontology, the acceptance test, full-suite run

**Files:**
- Modify: `ONTOLOGY.md`
- Test: `data/DataPlaneEndToEndTest.java`

**Interfaces:**
- Consumes: everything above.
- Produces: the acceptance test; documentation of the new classes, axioms and endpoints.

- [ ] **Step 1: Write the acceptance test**

`data/DataPlaneEndToEndTest.java` (the WS2 acceptance test — replaces a UI walk-through: upload CSVs, bind, trigger, read through `/runtime/v1`, stage a write, approve the gate, download the committed output; and a SANDBOX run can never read a `BUYER_DATA` dataset):

```java
package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunResponse;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.execution.RunTokenService;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.runtime.RuntimeFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataPlaneEndToEndTest extends AbstractStorageIntegrationTest {

    static Map<String, Object> entry(String bankLine, int amount) {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("bank_line", bankLine);
        entry.put("amount", amount);
        return entry;
    }

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    DataFixtures fx;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunDatasetPinRepository pinRepository;
    @Autowired
    BindingResolver resolver;
    @Autowired
    ManifestParser parser;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("rawtypes")
    private org.springframework.http.ResponseEntity<Map> call(HttpMethod method, String path, org.springframework.http.HttpHeaders headers, Object body) {
        return rest.exchange(url(path), method, new HttpEntity<>(body, headers), Map.class);
    }

    @Test
    void aBuyerUploadsBindsRunsAnAgentApprovesAndDownloadsWhatWasCommitted() {
        var s = fx.scenario();
        var buyer = fx.bearer(s.buyer(), UserRole.BUYER_ADMIN);
        var approver = fx.bearer(s.buyer(), UserRole.BUYER_APPROVER);

        // 1. upload a statement CSV and a PDF through the API
        var csv = rest.exchange(url("/api/v1/datasets"), HttpMethod.POST, new HttpEntity<>(
                DataFixtures.file("jan.csv", DataFixtures.CSV.getBytes(), "HDFC Jan"), fx.multipart(s.buyer(), UserRole.BUYER_ADMIN)), Map.class);
        var pdf = rest.exchange(url("/api/v1/datasets"), HttpMethod.POST, new HttpEntity<>(
                DataFixtures.file("jan.pdf", DataFixtures.PDF, "HDFC Jan PDF"), fx.multipart(s.buyer(), UserRole.BUYER_ADMIN)), Map.class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        String connection = s.connection().getId().toString();

        // 2. the trigger is blocked until every readable resource is bound
        assertThat(call(HttpMethod.POST, "/api/v1/runs", buyer, Map.of("connectionId", connection)).getBody().get("code"))
                .isEqualTo("MISSING_BINDING");

        // 3. bind both files
        assertThat(call(HttpMethod.PUT, "/api/v1/connections/" + connection + "/bindings/bank_statement", buyer,
                Map.of("datasetId", csv.getBody().get("id"))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PUT, "/api/v1/connections/" + connection + "/bindings/statement_pdf", buyer,
                Map.of("datasetId", pdf.getBody().get("id"))).getStatusCode()).isEqualTo(HttpStatus.OK);

        // 4. trigger; give the run a token we know (the launcher only stores its hash)
        var triggered = rest.exchange(url("/api/v1/runs"), HttpMethod.POST,
                new HttpEntity<>(Map.of("connectionId", connection), buyer), RunResponse.class);
        assertThat(triggered.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID runId = triggered.getBody().id();
        String token = "rt-" + UUID.randomUUID();
        runRepository.updateCallbackTokenHash(runId, RunTokenService.hash(token));
        Run run = runRepository.findById(runId).orElseThrow();
        run.setStatus(RunStatus.RUNNING);
        run.setDeadlineAt(Instant.now().plusSeconds(600));
        runRepository.save(run);
        assertThat(pinRepository.findByIdRunId(runId)).hasSize(2);
        var agent = RuntimeFixtures.bearer(token);

        // 5. the agent reads through /runtime/v1 only
        assertThat(call(HttpMethod.GET, "/runtime/v1/resources/bank_statement", agent, null).getBody().get("rowCount")).isEqualTo(2);
        var page = call(HttpMethod.GET, "/runtime/v1/resources/bank_statement/records", agent, null);
        assertThat((List<?>) page.getBody().get("records")).hasSize(2);
        var link = call(HttpMethod.GET, "/runtime/v1/resources/statement_pdf/file", agent, null);
        assertThat((String) link.getBody().get("url")).endsWith("/file/content");

        // 6. it stages a write and asks for approval covering it (insertion-ordered maps: the CSV header follows key
        //    order; whole-number amounts: a JSON write is parsed with the platform's mapper, which reads 12.50 as 12.5)
        var write = call(HttpMethod.POST, "/runtime/v1/resources/journal_entries/records", agent, Map.of("records",
                List.of(entry("Salary", 50000), entry("Rent", -12000))));
        assertThat(write.getBody().get("staged")).isEqualTo(true);
        var gate = call(HttpMethod.POST, "/runtime/v1/gates", agent, Map.of("action", "post_journal_entries",
                "summary", "Post 2 entries", "payload", Map.of("rows", 2), "affectsResources", List.of("journal_entries")));
        String gateId = (String) gate.getBody().get("gateId");

        // 7. before approval nothing is downloadable; then the buyer approves
        var outputsBefore = rest.exchange(url("/api/v1/runs/" + runId + "/outputs?preview=50"), HttpMethod.GET,
                new HttpEntity<>(null, approver), List.class);
        Map<?, ?> staged = (Map<?, ?>) outputsBefore.getBody().get(0);
        assertThat(staged.get("status")).isEqualTo("STAGED");
        assertThat((List<?>) staged.get("rows")).hasSize(2);
        assertThat(call(HttpMethod.GET, "/api/v1/runs/" + runId + "/outputs/" + staged.get("id") + "/download", approver, null)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.POST, "/api/v1/runs/" + runId + "/gates/" + gateId + "/decision", approver,
                Map.of("decision", "APPROVE", "note", "looks right")).getStatusCode()).isEqualTo(HttpStatus.OK);

        // 8. the output is committed and downloads as CSV
        var outputsAfter = rest.exchange(url("/api/v1/runs/" + runId + "/outputs"), HttpMethod.GET,
                new HttpEntity<>(null, approver), List.class);
        Map<?, ?> committed = (Map<?, ?>) outputsAfter.getBody().get(0);
        assertThat(committed.get("status")).isEqualTo("COMMITTED");
        var download = rest.exchange(url("/api/v1/runs/" + runId + "/outputs/" + committed.get("id") + "/download?format=csv"),
                HttpMethod.GET, new HttpEntity<>(null, approver), String.class);
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download.getBody()).isEqualTo("bank_line,amount\r\nSalary,50000\r\nRent,-12000\r\n");

        // 9. a SANDBOX run can never read buyer data, by the resolver or by the backend
        assertThatThrownBy(() -> resolver.resolve(s.connection(), parser.parse(s.version().getManifestJson()), RunMode.SANDBOX))
                .isInstanceOf(DataApiException.class);
        var sandbox = fx.runningAgent(s);
        Run sandboxRun = sandbox.run();
        sandboxRun.setMode(RunMode.SANDBOX);
        runRepository.save(sandboxRun);
        pinRepository.save(new RunDatasetPin(sandboxRun.getId(), "bank_statement", UUID.fromString((String) csv.getBody().get("id")),
                (String) csv.getBody().get("sha256")));
        var denied = call(HttpMethod.GET, "/runtime/v1/resources/bank_statement/records", RuntimeFixtures.bearer(sandbox.token()), null);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code")).isEqualTo("DATA_MODE_MISMATCH");
    }
}
```

- [ ] **Step 2: Run it**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=DataPlaneEndToEndTest`
Expected: PASS. If the trigger's fake sandbox leaves the run in a state that makes the `RUNNING` override in step 4 conflict (for example the launcher already moved it), keep the override — it only sets the state the real sandbox would have.

- [ ] **Step 3: Document in `ONTOLOGY.md`**

Read `ONTOLOGY.md` sections 2 (Core Classes — "Execution"), 3 (Properties), 4 (Axioms) and 5 (API mappings, "Agent Runtime API v1" and "Connections & runs") first, then add, matching each section's existing style:
- **Section 2, new subsection "Data plane":** `Dataset` (a buyer's file in the workspace library: `purpose` BUYER_DATA now, FIXTURE/SAMPLE/EVAL reserved; original + normalized JSONL; purged datasets remain as tombstones), `DatasetBinding` (a file plugged into one resource of one owner, unique per owner+resource; CONNECTION today), `RunDatasetPin` (what a run reads, fixed at trigger), `RunOutput` (one agent write call: STAGED/COMMITTED/DISCARDED), `BindingResolver`, `DatasetResourceBackend`, `OutputCommitService`, `DataRetentionService`.
- **Section 3:** Dataset —owned by→ Org; DatasetBinding —plugs→ Dataset into (Connection, resource); Run —pins→ Dataset per resource; RunOutput —belongs to→ Run, —committed by→ GateRequest; Manifest —declares→ ResourceDecl.
- **Section 4 axioms:** files are untyped until bound; a bind runs the schema check and stores `check_result`; a declared, granted READ resource with no binding blocks the trigger (`422 MISSING_BINDING`), manifests without `resources` are untyped and never blocked; runs read only their pins; buyer data reaches only PRODUCTION runs (resolver and backend both enforce); a gate commits only outputs staged before it was requested and only for the resources it lists; every terminal run discards its remaining STAGED outputs; explicit delete purges within ~1 hour and unplugging waits 30 days; purge tombstones the row; DISCARDED output blobs are deleted after 30 days, COMMITTED kept; agent writes are capped at 5 MB per call and 200 MB per run; files stream through the backend, never presigned; a pdf resource is READ only.
- **Section 5:** a "Datasets & bindings" block listing `POST/GET /api/v1/datasets`, `DELETE /api/v1/datasets/{id}`, `POST /api/v1/datasets/{id}/check`, `GET /api/v1/orgs/{orgId}/storage`, `PUT/DELETE/GET /api/v1/connections/{id}/bindings[/{resource}]`, `GET /api/v1/runs/{id}/outputs`, `GET /api/v1/runs/{id}/outputs/{outputId}/download`; and in the Agent Runtime API block change resources from "501 until WS2" to implemented, adding `GET /runtime/v1/resources/{resource}/file/content`.
- **"Inconsistencies & Unclear Areas":** note that `ConnectionStatus.REVOKED` is never set by any code path, so `BindingService.unbindAll` (which starts the 30-day clock for a revoked connection's files) is built but not yet wired.

- [ ] **Step 4: Full suites**

Run the whole backend suite, then CLI and frontend checks:

```bash
cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test
cd ../cli && npx vitest run
```

Expected: all green. Fix any failure at its cause; do not edit an existing test to make it pass unless it asserted the old 501 behavior (already handled in Task 7). Record the test counts (Tests run / Failures / Errors) for the final report.

- [ ] **Step 5: Update memory and commit**

Overwrite `/Users/amittiwari/.claude/projects/-Users-amittiwari-AgentStore/memory/agentstore-ws2-design-state.md` with the WS2 implementation state (branch `feat/ws2-data-plane`, tasks done, suite result, deviations from the spec listed above, follow-ups: wire `unbindAll` to connection revoke, `schemaRef` sugar, XLSX, FE-2 consuming the APIs, WS12 SSE-KMS), and update the WS2 line in `MEMORY.md` to match.

```bash
git add ONTOLOGY.md backend
git commit -m "docs: data plane ontology; test: WS2 acceptance"
```

- [ ] **Step 6: Hand off**


---

## Self-review

**Spec coverage** — §1 scope: Task 1 (manifest), 3–4 (datasets), 5–6 (check, bindings), 7–8 (backend), 9 (commit/discard), 10 (outputs), 11 (retention). §2: Task 1. §3 data model: Task 2. §4 datasets API incl. caps and quota: Task 4 (50 MB cap by multipart limit, config-shrunk in `DatasetUploadCapTest`). §5: Tasks 5–6, including `MISSING_BINDING`, mode guard, replace-clears-`purge_after`, unplug 30-day grace. §6 reads and writes, cursor, `file/content`, contract regeneration, 5 MB / 200 MB, 503: Tasks 7–8. §7 events, timing rule, run-end discard: Task 9. §8: Task 10. §9: Task 11. §10 tests: each task plus Task 12's acceptance. §11 task order: the same twelve. §12 settled decisions: none re-opened.

**Placeholder scan** — no TBD/TODO; every code step has code. Two spots carry a conditional fix-up because they depend on library behavior I could not run here (the exact exception commons-csv raises for invalid UTF-8 in Task 3 Step 6, and Tomcat's connection handling for the chunked 413 in Task 8 Step 5); each states the contract the test must keep.

**Type consistency** — `DatasetService.upload(...)` (Task 4) is what `DataFixtures.upload` calls; `DatasetCheckService.check(Dataset, ResourceDecl)` (Task 5) is what `BindingService` uses; `BindingService.bind/unbindAll` (Task 6) back `DataFixtures.bind/unbindAll`; `RunOutputWriter.write` (stub in Task 7, real in Task 8) is the signature `DatasetResourceBackend.write` delegates to and Tasks 9–11 call directly; `RunOutput(UUID, UUID, String, OutputStatus, int, long, String)` is used identically in Tasks 8, 9, 10, 11; `GateRequest.affected()` (Task 9) is the only parser of `affects_resources`; `ProblemBody` gains `errors` in Task 7 and `RunOutputWriter` uses it in Task 8; `SchemaChecker.CheckResult` fields (`ok, rowCount, columnsMatch, missingColumns, rowErrorCount, rowErrors`) match every test that reads them.

**Review Focus** — items 1–5 map to Tasks 10, 3/5, 7, 4/6/7/11, 8 respectively.
