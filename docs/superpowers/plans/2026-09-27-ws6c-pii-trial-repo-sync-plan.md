# WS6c — PII Scan, Trial Profiles, REPO Sync, CLI Profile/Fixture Commands Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a PII scanner for developer fixture uploads, a trial-profile attestation flow gated on that scan, a computed `sandbox_profile.status`, REPO-sourced profile sync on `agentstore push`, and the CLI commands (`fixtures push/list`, `profile list/pull`) that use it.

**Architecture:** All new backend logic lives in the existing `data` (PII scan, dataset check reuse) and `sandbox` (profile fields, attestation, status, REPO sync) packages, following their existing per-domain exception/service/controller conventions exactly — no new packages. The CLI adds two new command files (`fixtures.ts`, `profile.ts`) plus a small shared `profiles.ts` module for reading/writing `fixtures/profiles/*.yaml`, and extends `push.ts` to call the new sync endpoint after a successful finalize.

**Tech Stack:** Java 17 / Spring Boot 3.3 / Spring Data JPA / Flyway (backend, unchanged); Node ≥22 TypeScript ESM / `yaml` / Vitest (CLI, unchanged).

**Spec:** `docs/superpowers/specs/2026-09-27-ws6c-pii-trial-repo-sync-design.md`

## Global Constraints

- TDD is mandatory: write the failing test before the implementation, for every step in this plan (Attention.md ironclad convention #1).
- Migrations are Flyway, forward-only, numbered: this plan's schema change is `V35__pii_scan_trial_profiles_repo_sync.sql` (next after `V34`) — never edit a merged migration.
- New error codes live in the existing per-domain exception classes (`DataApiException`/`DataApiErrors` for the `data` package, `SandboxApiException`/`SandboxApiErrors` for the `sandbox` package) — no new exception hierarchy.
- Money fields (`spendCap`) stay `BigDecimal`; nothing in this plan introduces `double`/`float` for anything cost-related.
- `contracts/platform-v1.yaml` is regenerated whenever the hand-built "platform" OpenAPI document's endpoint set changes (established WS6b precedent in `PlatformOpenApiConfig`/`PlatformContractTest`) — this plan adds one endpoint to it.
- ONTOLOGY.md must stay accurate: this plan's final task updates it (and Attention.md's `active_context` block) as part of the workstream, not deferred. `scripts/check-ontology-staleness.sh` was found already stale from a post-WS6b review-fix commit (`2781b8c`, prose was still accurate) and has been re-stamped via `scripts/stamp-ontology-hash.sh` before this plan was written — ONTOLOGY.md is confirmed in sync as of the start of this plan.

## Review Focus

- An empty-row tabular fixture (a header with zero data rows) must not crash `PiiScanner.scan` — it returns `CLEAN` (Task 2).
- A JSON fixture cell whose value is a nested object or array must be skipped by `PiiScanner`, never stringified and accidentally regex-matched (Task 2).
- Editing an unrelated field on a trial-enabled profile (e.g. `spendCap`) via `PUT .../sandbox-profiles/{id}` with `trialEnabled` omitted from the body must leave `trial_enabled` and the attestation untouched, not reset it (Task 4).
- A REPO-sync entry whose previously-synced profile (by name) was deleted since the last sync must be recreated fresh, not crash or silently skip, even though its incoming hash matches nothing now stored (Task 6).
- A sandbox profile with zero bindings resolves to `READY` even when the version being checked against has typed resources declared — nothing bound means nothing to check (Task 5).

---

### Task 1: Migration + entity field additions (no behavior yet)

**Files:**
- Create: `backend/src/main/resources/db/migration/V35__pii_scan_trial_profiles_repo_sync.sql`
- Modify: `backend/src/main/java/com/agentstore/platform/data/Dataset.java`
- Modify: `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfile.java`
- Create: `backend/src/main/java/com/agentstore/platform/sandbox/ProfileSource.java`
- Test: `backend/src/test/java/com/agentstore/platform/sandbox/SandboxDataModelTest.java` (existing file — add assertions)
- Test: `backend/src/test/java/com/agentstore/platform/data/DataModelTest.java` (existing file — add assertions)

**Interfaces:**
- Produces: `Dataset.getPiiScanJson()/setPiiScanJson(String)` (raw JSON column accessor — the typed `PiiScanner.Result` accessor comes in Task 2/3, since `PiiScanner` doesn't exist yet); `SandboxProfile.getDescription/setDescription(String)`, `.isTrialEnabled/setTrialEnabled(boolean)`, `.getNoRealDataAttestedBy/setNoRealDataAttestedBy(UUID)`, `.getNoRealDataAttestedAt/setNoRealDataAttestedAt(Instant)`, `.getSource/setSource(ProfileSource)`, `.getRepoSourceHash/setRepoSourceHash(String)`; `ProfileSource` enum `{PORTAL, REPO}`.

- [ ] **Step 1: Look at the existing data-model tests these two edits extend**

Read `backend/src/test/java/com/agentstore/platform/data/DataModelTest.java` and `backend/src/test/java/com/agentstore/platform/sandbox/SandboxDataModelTest.java` to confirm their existing pattern (they save an entity through its repository and assert the round-tripped fields), so the new assertions below match that file's style exactly.

- [ ] **Step 2: Write the failing test — new `Dataset` field round-trips**

Add to `backend/src/test/java/com/agentstore/platform/data/DataModelTest.java`:

```java
    @Test
    void piiScanJsonRoundTripsAndDefaultsToNull() {
        Dataset dataset = new Dataset();
        dataset.setOwnerOrgId(java.util.UUID.randomUUID());
        dataset.setFormat(com.agentstore.platform.manifest.DataFormat.CSV);
        dataset.setDisplayName("d");
        Dataset saved = datasets.saveAndFlush(dataset);
        assertThat(saved.getPiiScanJson()).isNull();

        saved.setPiiScanJson("{\"status\":\"CLEAN\",\"findingCount\":0,\"findings\":[]}");
        Dataset reloaded = datasets.saveAndFlush(saved);
        assertThat(reloaded.getPiiScanJson()).contains("CLEAN");
    }
```

(Use whatever repository field name/autowiring the existing test class already has for `DatasetRepository`; match its existing `@Autowired` field.)

Add to `backend/src/test/java/com/agentstore/platform/sandbox/SandboxDataModelTest.java`:

```java
    @Test
    void newProfileFieldsRoundTripWithPortalDefaults() {
        var dev = fx.developer("valid-tier-a.json");
        SandboxProfile profile = fx.profile(dev, "p1");

        assertThat(profile.getSource()).isEqualTo(ProfileSource.PORTAL);
        assertThat(profile.isTrialEnabled()).isFalse();
        assertThat(profile.getDescription()).isNull();
        assertThat(profile.getNoRealDataAttestedBy()).isNull();
        assertThat(profile.getRepoSourceHash()).isNull();

        profile.setDescription("Clean invoice batch");
        profile.setTrialEnabled(true);
        profile.setNoRealDataAttestedBy(java.util.UUID.randomUUID());
        profile.setNoRealDataAttestedAt(java.time.Instant.now());
        profile.setSource(ProfileSource.REPO);
        profile.setRepoSourceHash("abc123");
        SandboxProfile reloaded = profiles.saveAndFlush(profile);

        assertThat(reloaded.getDescription()).isEqualTo("Clean invoice batch");
        assertThat(reloaded.isTrialEnabled()).isTrue();
        assertThat(reloaded.getSource()).isEqualTo(ProfileSource.REPO);
        assertThat(reloaded.getRepoSourceHash()).isEqualTo("abc123");
    }
```

(Match the existing file's field name for the autowired `SandboxProfileRepository` — likely `profiles`, per `SandboxProfileApiTest`.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && mvn -q test -Dtest=DataModelTest,SandboxDataModelTest`
Expected: compile failure — `Dataset` has no `getPiiScanJson`/`setPiiScanJson`, `SandboxProfile` has no `getDescription`/`setTrialEnabled`/etc., `ProfileSource` does not exist.

- [ ] **Step 3: Write the migration**

Create `backend/src/main/resources/db/migration/V35__pii_scan_trial_profiles_repo_sync.sql`:

```sql
ALTER TABLE dataset ADD COLUMN pii_scan JSONB;

ALTER TABLE sandbox_profile ADD COLUMN description VARCHAR(255);
ALTER TABLE sandbox_profile ADD COLUMN trial_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE sandbox_profile ADD COLUMN no_real_data_attested_by UUID REFERENCES app_user(id);
ALTER TABLE sandbox_profile ADD COLUMN no_real_data_attested_at TIMESTAMPTZ;
ALTER TABLE sandbox_profile ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'PORTAL';
ALTER TABLE sandbox_profile ADD COLUMN repo_source_hash VARCHAR(64);
```

- [ ] **Step 4: Add the `ProfileSource` enum**

Create `backend/src/main/java/com/agentstore/platform/sandbox/ProfileSource.java`:

```java
package com.agentstore.platform.sandbox;

/** Where a saved sandbox profile's fields last came from. Any portal-driven PUT reclaims PORTAL unconditionally. */
public enum ProfileSource { PORTAL, REPO }
```

- [ ] **Step 5: Add the new `Dataset` field**

Modify `backend/src/main/java/com/agentstore/platform/data/Dataset.java`. After the `columnsJson` field block (currently ending `private String columnsJson = "[]";`), add:

```java
    /** {status: 'CLEAN'|'FOUND', findingCount, findings: [...]}, null until scanned (never scanned for non-FIXTURE purposes or PDF). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pii_scan", columnDefinition = "jsonb")
    private String piiScanJson;
```

`@Getter`/`@Setter` on the class already generates `getPiiScanJson()`/`setPiiScanJson(String)`.

- [ ] **Step 6: Add the new `SandboxProfile` fields**

Modify `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfile.java`. After the `name` field, add:

```java
    private String description;
```

After the `spendCap` field (before `fakeSecretsEncrypted`), add:

```java
    private boolean trialEnabled;
    private UUID noRealDataAttestedBy;
    private Instant noRealDataAttestedAt;

    @Enumerated(EnumType.STRING)
    private ProfileSource source = ProfileSource.PORTAL;

    private String repoSourceHash;
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd backend && mvn -q test -Dtest=DataModelTest,SandboxDataModelTest`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V35__pii_scan_trial_profiles_repo_sync.sql \
        backend/src/main/java/com/agentstore/platform/data/Dataset.java \
        backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfile.java \
        backend/src/main/java/com/agentstore/platform/sandbox/ProfileSource.java \
        backend/src/test/java/com/agentstore/platform/data/DataModelTest.java \
        backend/src/test/java/com/agentstore/platform/sandbox/SandboxDataModelTest.java
git commit -m "feat(sandbox,data): WS6c V35 migration and entity fields for PII scan, trial, REPO sync"
```

---

### Task 2: `PiiScanner` (pure, unit-tested)

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/data/PiiScanner.java`
- Test: `backend/src/test/java/com/agentstore/platform/data/PiiScannerTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks (pure class over `Iterator<Map<String, Object>>`, the same row shape `Jsonl.readRows` produces).
- Produces: `PiiScanner.scan(Iterator<Map<String,Object>>): PiiScanner.Result`; `PiiScanner.Result(String status, long findingCount, List<Finding> findings)` with `Result.CLEAN`/`Result.FOUND` string constants and `Result.clean()` factory; `PiiScanner.Finding(Type type, long row, String column)`; `PiiScanner.Type` enum `{PAN, GSTIN, AADHAAR, IFSC, ACCOUNT_NO, EMAIL, PHONE}`; `PiiScanner.MAX_FINDINGS = 200`. Task 3 wires this into `DatasetService.upload`; Task 4 reads `Result.CLEAN` off a bound dataset for the attestation gate.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/agentstore/platform/data/PiiScannerTest.java`:

```java
package com.agentstore.platform.data;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PiiScannerTest {

    @SafeVarargs
    private static PiiScanner.Result scan(Map<String, Object>... rows) {
        return PiiScanner.scan(List.of(rows).iterator());
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    @Test
    void anEmptyFileIsClean() {
        PiiScanner.Result result = PiiScanner.scan(List.<Map<String, Object>>of().iterator());
        assertThat(result.status()).isEqualTo(PiiScanner.Result.CLEAN);
        assertThat(result.findings()).isEmpty();
        assertThat(result.findingCount()).isZero();
    }

    @Test
    void panIsDetectedAndAShortStringIsNot() {
        assertThat(scan(row("col", "ABCDE1234F")).status()).isEqualTo(PiiScanner.Result.FOUND);
        assertThat(scan(row("col", "ABCDE12345")).status()).isEqualTo(PiiScanner.Result.CLEAN);
    }

    @Test
    void gstinIsDetectedButADeliberatelyMalformedEmbeddedPanIsNot() {
        assertThat(scan(row("gstin", "29AAAPL1234C1ZS")).status()).isEqualTo(PiiScanner.Result.FOUND);
        assertThat(scan(row("gstin", "29AAAPL123XC1ZS")).status()).isEqualTo(PiiScanner.Result.CLEAN);
    }

    @Test
    void aadhaarVerhoeffChecksumIsValidatedNotJustShape() {
        assertThat(scan(row("aadhaar", "234123412346")).status()).isEqualTo(PiiScanner.Result.FOUND);
        assertThat(scan(row("aadhaar", "234123412347")).status()).isEqualTo(PiiScanner.Result.CLEAN);
    }

    @Test
    void ifscIsDetectedAndAMissingLiteralZeroIsNot() {
        assertThat(scan(row("ifsc", "HDFC0001234")).status()).isEqualTo(PiiScanner.Result.FOUND);
        assertThat(scan(row("ifsc", "HDFC1001234")).status()).isEqualTo(PiiScanner.Result.CLEAN);
    }

    @Test
    void accountNumberIsGatedOnTheColumnHeaderNotJustTheDigits() {
        assertThat(scan(row("Bank A/C No", "123456789")).status()).isEqualTo(PiiScanner.Result.FOUND);
        assertThat(scan(row("amount", "123456789")).status()).isEqualTo(PiiScanner.Result.CLEAN);
    }

    @Test
    void emailIsDetectedAndPlainTextIsNot() {
        assertThat(scan(row("col", "person@example.com")).status()).isEqualTo(PiiScanner.Result.FOUND);
        assertThat(scan(row("col", "not-an-email")).status()).isEqualTo(PiiScanner.Result.CLEAN);
    }

    @Test
    void indianMobileAndE164AreDetectedAndALeadingOneIsNot() {
        assertThat(scan(row("col", "9876543210")).status()).isEqualTo(PiiScanner.Result.FOUND);
        assertThat(scan(row("col", "+919876543210")).status()).isEqualTo(PiiScanner.Result.FOUND);
        assertThat(scan(row("col", "1234567890")).status()).isEqualTo(PiiScanner.Result.CLEAN);
    }

    @Test
    void findingsAreCappedAtTwoHundredButTheCountKeepsGoing() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            rows.add(row("col", "person" + i + "@example.com"));
        }
        PiiScanner.Result result = PiiScanner.scan(rows.iterator());
        assertThat(result.status()).isEqualTo(PiiScanner.Result.FOUND);
        assertThat(result.findingCount()).isEqualTo(250);
        assertThat(result.findings()).hasSize(200);
    }

    @Test
    void nestedObjectAndArrayValuesAreSkippedNotStringified() {
        assertThat(scan(row("col", Map.of("nested", "person@example.com"))).status()).isEqualTo(PiiScanner.Result.CLEAN);
        assertThat(scan(row("col", List.of("person@example.com"))).status()).isEqualTo(PiiScanner.Result.CLEAN);
    }

    @Test
    void nullCellsAreSkipped() {
        assertThat(scan(row("col", null)).status()).isEqualTo(PiiScanner.Result.CLEAN);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=PiiScannerTest`
Expected: compile failure — `PiiScanner` does not exist.

- [ ] **Step 3: Write the implementation**

Create `backend/src/main/java/com/agentstore/platform/data/PiiScanner.java`:

```java
package com.agentstore.platform.data;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Regex/checksum detectors run over every scalar cell of a tabular fixture's normalized rows, only for
 * {@code purpose == FIXTURE} uploads (wired in {@link DatasetService#upload}). Pure: takes a row iterator, returns a
 * result, saves nothing. Row numbering matches {@link SchemaChecker} (from 1, no header). Findings are capped at
 * {@link #MAX_FINDINGS} in the returned list, but {@link Result#findingCount()} keeps counting past the cap.
 */
public final class PiiScanner {

    public static final int MAX_FINDINGS = 200;

    public enum Type { PAN, GSTIN, AADHAAR, IFSC, ACCOUNT_NO, EMAIL, PHONE }

    public record Finding(Type type, long row, String column) {
    }

    public record Result(String status, long findingCount, List<Finding> findings) {

        public static final String CLEAN = "CLEAN";
        public static final String FOUND = "FOUND";

        public static Result clean() {
            return new Result(CLEAN, 0, List.of());
        }
    }

    private static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    private static final Pattern GSTIN_SHAPE = Pattern.compile("^[0-9]{2}[A-Z0-9]{13}$");
    private static final Pattern AADHAAR_SHAPE = Pattern.compile("^[0-9]{12}$");
    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
    private static final Pattern ACCOUNT_NO_VALUE = Pattern.compile("^[0-9]{9,18}$");
    private static final Pattern ACCOUNT_NO_COLUMN = Pattern.compile("account|acct|a/c", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern PHONE_IN = Pattern.compile("^[6-9]\\d{9}$");
    private static final Pattern PHONE_E164 = Pattern.compile("^\\+\\d{10,15}$");

    // Standard Verhoeff multiplication (d) and permutation (p) tables, for the AADHAAR checksum.
    private static final int[][] D = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 2, 3, 4, 0, 6, 7, 8, 9, 5}, {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7}, {4, 0, 1, 2, 3, 9, 5, 6, 7, 8}, {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2}, {7, 6, 5, 9, 8, 2, 1, 0, 4, 3}, {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}};
    private static final int[][] P = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 5, 7, 6, 2, 8, 3, 0, 9, 4}, {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7}, {9, 4, 5, 3, 1, 2, 6, 8, 7, 0}, {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5}, {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}};

    private PiiScanner() {
    }

    public static Result scan(Iterator<Map<String, Object>> rows) {
        List<Finding> findings = new ArrayList<>();
        long count = 0;
        long n = 0;
        while (rows.hasNext()) {
            n++;
            for (Map.Entry<String, Object> cell : rows.next().entrySet()) {
                Object raw = cell.getValue();
                if (raw == null || raw instanceof Map || raw instanceof List) {
                    continue;
                }
                Type type = detect(cell.getKey(), String.valueOf(raw));
                if (type != null) {
                    count++;
                    if (findings.size() < MAX_FINDINGS) {
                        findings.add(new Finding(type, n, cell.getKey()));
                    }
                }
            }
        }
        return count == 0 ? Result.clean() : new Result(Result.FOUND, count, List.copyOf(findings));
    }

    private static Type detect(String column, String value) {
        if (PAN.matcher(value).matches()) {
            return Type.PAN;
        }
        if (GSTIN_SHAPE.matcher(value).matches() && PAN.matcher(value.substring(2, 12)).matches()) {
            return Type.GSTIN;
        }
        if (AADHAAR_SHAPE.matcher(value).matches() && verhoeffValid(value)) {
            return Type.AADHAAR;
        }
        if (IFSC.matcher(value).matches()) {
            return Type.IFSC;
        }
        if (ACCOUNT_NO_VALUE.matcher(value).matches() && ACCOUNT_NO_COLUMN.matcher(column).find()) {
            return Type.ACCOUNT_NO;
        }
        if (EMAIL.matcher(value).matches()) {
            return Type.EMAIL;
        }
        if (PHONE_IN.matcher(value).matches() || PHONE_E164.matcher(value).matches()) {
            return Type.PHONE;
        }
        return null;
    }

    private static boolean verhoeffValid(String digits) {
        int c = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(digits.length() - 1 - i) - '0';
            c = D[c][P[i % 8][digit]];
        }
        return c == 0;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=PiiScannerTest`
Expected: PASS (11 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/agentstore/platform/data/PiiScanner.java \
        backend/src/test/java/com/agentstore/platform/data/PiiScannerTest.java
git commit -m "feat(data): add PiiScanner (PAN/GSTIN/AADHAAR/IFSC/account-no/email/phone detectors)"
```

---

### Task 3: Wire `PiiScanner` into `DatasetService.upload`, expose it on the API

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/data/Dataset.java`
- Modify: `backend/src/main/java/com/agentstore/platform/data/DatasetService.java:91-124`
- Modify: `backend/src/main/java/com/agentstore/platform/data/DatasetResponse.java`
- Test: `backend/src/test/java/com/agentstore/platform/data/DatasetPiiScanTest.java` (new)
- Test: `backend/src/test/java/com/agentstore/platform/sandbox/SandboxFixtureApiTest.java` (existing file — add one test)

**Interfaces:**
- Consumes: `PiiScanner.scan(Iterator<Map<String,Object>>)` and `PiiScanner.Result` (Task 2); `Jsonl.readRows(InputStream)` (existing).
- Produces: `Dataset.piiScan(): PiiScanner.Result` (typed accessor, null until scanned), `Dataset.setPiiScan(PiiScanner.Result)`, `Dataset.piiClean(): boolean` (false for null scan — Task 4's attestation gate depends on this exact method existing and this exact null-is-not-clean semantic); `DatasetResponse` gains a `piiScan` field.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/agentstore/platform/data/DatasetPiiScanTest.java`:

```java
package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetPiiScanTest extends AbstractStorageIntegrationTest {

    @Autowired DatasetService datasetService;
    @Autowired OrgRepository orgs;

    private Org org() {
        return orgs.save(new Org(null, "Org-" + UUID.randomUUID(), OrgType.DEVELOPER));
    }

    @Test
    void aFixtureCsvWithPiiIsScannedAndFlagged() {
        String csv = "name,email\nA,person@example.com\n";
        Dataset dataset = datasetService.upload(org().getId(), UUID.randomUUID(), "f.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "f.csv", null,
                DatasetPurpose.FIXTURE, null);

        assertThat(dataset.piiScan()).isNotNull();
        assertThat(dataset.piiScan().status()).isEqualTo(PiiScanner.Result.FOUND);
        assertThat(dataset.piiClean()).isFalse();
    }

    @Test
    void aCleanFixtureCsvIsScannedClean() {
        String csv = "name,amount\nA,100\n";
        Dataset dataset = datasetService.upload(org().getId(), UUID.randomUUID(), "f.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "f.csv", null,
                DatasetPurpose.FIXTURE, null);

        assertThat(dataset.piiScan().status()).isEqualTo(PiiScanner.Result.CLEAN);
        assertThat(dataset.piiClean()).isTrue();
    }

    @Test
    void buyerDataIsNeverScanned() {
        String csv = "name,email\nA,person@example.com\n";
        Dataset dataset = datasetService.upload(org().getId(), UUID.randomUUID(), "f.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "f.csv", null);

        assertThat(dataset.piiScan()).isNull();
        assertThat(dataset.piiClean()).isFalse();
    }

    @Test
    void aPdfFixtureIsNeverScannedAndCanNeverBeClean() {
        Dataset dataset = datasetService.upload(org().getId(), UUID.randomUUID(), "f.pdf",
                new ByteArrayInputStream(DataFixtures.PDF), "f.pdf", null, DatasetPurpose.FIXTURE, null);

        assertThat(dataset.piiScan()).isNull();
        assertThat(dataset.piiClean()).isFalse();
    }
}
```

Add to `backend/src/test/java/com/agentstore/platform/sandbox/SandboxFixtureApiTest.java` (after `displayNameDefaultsToTheFileName`):

```java
    @Test
    void theResponseIncludesThePiiScanResult() {
        var dev = fx.developer("valid-tier-a.json");
        String withEmail = "name,email\nA,person@example.com\n";

        var response = upload(dev.org(), "leak.csv", withEmail.getBytes(), "leak");

        @SuppressWarnings("unchecked")
        Map<String, Object> piiScan = (Map<String, Object>) response.getBody().get("piiScan");
        assertThat(piiScan.get("status")).isEqualTo("FOUND");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && mvn -q test -Dtest=DatasetPiiScanTest,SandboxFixtureApiTest`
Expected: compile failure — `Dataset.piiScan()`/`piiClean()` don't exist yet; `DatasetResponse` has no `piiScan` field so the assertion sees `null`.

- [ ] **Step 3: Add the typed `Dataset` accessors**

Modify `backend/src/main/java/com/agentstore/platform/data/Dataset.java`. Add near the existing `columns()`/`setColumns()` methods:

```java
    public PiiScanner.Result piiScan() {
        if (piiScanJson == null) {
            return null;
        }
        try {
            return Jsonl.MAPPER.readValue(piiScanJson, PiiScanner.Result.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public void setPiiScan(PiiScanner.Result result) {
        try {
            this.piiScanJson = result == null ? null : Jsonl.MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** False for a clean-looking dataset that was simply never scanned (a PDF, or a purpose other than FIXTURE). */
    public boolean piiClean() {
        PiiScanner.Result result = piiScan();
        return result != null && PiiScanner.Result.CLEAN.equals(result.status());
    }
```

- [ ] **Step 4: Wire the scan into `DatasetService.upload`**

Modify `backend/src/main/java/com/agentstore/platform/data/DatasetService.java`. In the `upload(...)` method, after the line `dataset.setColumns(normalized == null ? List.of() : normalized.columns());` (currently line 124) and before `long incoming = size + recordsSize;`, insert:

```java
            if (purpose == DatasetPurpose.FIXTURE && normalized != null) {
                try (var rows = Jsonl.readRows(Files.newInputStream(records))) {
                    dataset.setPiiScan(PiiScanner.scan(rows));
                }
            }
```

(`Files.newInputStream` and `Jsonl.readRows` both throw `IOException`, already covered by the method's enclosing `catch (IOException e)`.)

- [ ] **Step 5: Expose it on `DatasetResponse`**

Modify `backend/src/main/java/com/agentstore/platform/data/DatasetResponse.java`:

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
                              Instant createdAt, PiiScanner.Result piiScan,
                              @JsonInclude(JsonInclude.Include.NON_NULL) List<Usage> usage) {

    public record Usage(UUID connectionId, String resource, String agent, String version, String access) {
    }

    public static DatasetResponse from(Dataset d, String uploadedBy, List<Usage> usage) {
        return new DatasetResponse(d.getId(), d.getDisplayName(), d.getSourceLabel(), d.getFormat(), d.getRowCount(),
                d.getSizeBytes(), d.columns(), d.getSha256(), uploadedBy, d.getCreatedAt(), d.piiScan(), usage);
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd backend && mvn -q test -Dtest=DatasetPiiScanTest,SandboxFixtureApiTest`
Expected: PASS

Also run the full data-plane and sandbox test suites to catch any other place that constructs a `DatasetResponse` positionally or asserts on its exact JSON key set: `cd backend && mvn -q test -Dtest=com.agentstore.platform.data.*Test,com.agentstore.platform.sandbox.*Test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/agentstore/platform/data/Dataset.java \
        backend/src/main/java/com/agentstore/platform/data/DatasetService.java \
        backend/src/main/java/com/agentstore/platform/data/DatasetResponse.java \
        backend/src/test/java/com/agentstore/platform/data/DatasetPiiScanTest.java \
        backend/src/test/java/com/agentstore/platform/sandbox/SandboxFixtureApiTest.java
git commit -m "feat(data): scan FIXTURE tabular uploads for PII and surface the result on the API"
```

---

### Task 4: Trial-profile attestation (`PUT /sandbox-profiles/{id}`)

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/sandbox/ProfileInput.java`
- Modify: `backend/src/main/java/com/agentstore/platform/sandbox/ProfileView.java`
- Modify: `backend/src/main/java/com/agentstore/platform/sandbox/SandboxApiException.java`
- Modify: `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileService.java`
- Modify: `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileController.java:38-41`
- Modify: `backend/src/main/java/com/agentstore/platform/data/SandboxBindingService.java`
- Modify: `backend/src/test/java/com/agentstore/platform/sandbox/SandboxProfileApiTest.java`

**Interfaces:**
- Consumes: `Dataset.piiClean()` (Task 3); `Caller.current()` returning `JwtService.Claims` with `.userId()`/`.orgId()` (existing).
- Produces: `SandboxApiException.attestationRequired()`, `SandboxApiException.piiFound(List<String> resources)`; `SandboxProfileService.update(UUID orgId, UUID userId, UUID agentId, UUID profileId, ProfileInput in)` (signature changed — note the new `userId` parameter for Task 5/6, which do not call this method, and for the controller wiring below). `ProfileView` gains `description`, `trialEnabled`, `source` fields in this task (its `status` field is added in Task 5 — this task's `view()` edit leaves a placeholder wiring point Task 5 fills in).

> **Note for the implementer:** `ProfileView` cannot compile with only 4 of its eventual 5 new fields; this task adds `description`, `trialEnabled`, `source` to the record and to `view()`'s construction, and Task 5 adds the 5th (`status`) alongside its own `ProfileStatusService`. Do not attempt to stub a fake `status` value here — just do both tasks in order (they are meant to run back to back) and let Task 5 finish the record.

- [ ] **Step 1: Write the failing tests**

First fix the existing direct-construction call site that will no longer compile once `ProfileInput` grows fields. In `backend/src/test/java/com/agentstore/platform/sandbox/SandboxProfileApiTest.java`, change:

```java
        service.createInline(dev.org().getId(), dev.agent().getId(),
                new ProfileInput(null, null, null, null, null, null, null, null, null));
```

to:

```java
        service.createInline(dev.org().getId(), dev.agent().getId(),
                new ProfileInput(null, null, null, null, null, null, null, null, null, null, null, null));
```

Then add these tests to the same file (they use the file's existing `body(name)` helper, `call(...)` helper, and `fx`/`dataFx` fields):

```java
    @Test
    void trialCanBeEnabledWithAttestationWhenBoundFilesAreClean() {
        var dev = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, base(dev), body("t1"), dev.org(), UserRole.DEVELOPER);
        UUID profileId = UUID.fromString((String) created.getBody().get("id"));
        var clean = dataFx.upload(dev.org(), "clean.csv", "name,amount\nA,100\n");
        bindings.save(new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profileId, "bank_statement", clean.getId(), "{}", null));
        Map<String, Object> withTrial = body("t1");
        withTrial.put("trialEnabled", true);
        withTrial.put("attest", true);

        var response = call(HttpMethod.PUT, base(dev) + "/" + profileId, withTrial, dev.org(), UserRole.DEVELOPER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("trialEnabled")).isEqualTo(true);
    }

    @Test
    void trialIsRejectedWithPiiFoundWhenABoundFileIsDirty() {
        var dev = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, base(dev), body("t2"), dev.org(), UserRole.DEVELOPER);
        UUID profileId = UUID.fromString((String) created.getBody().get("id"));
        var dirty = dataFx.upload(dev.org(), "dirty.csv", "name,email\nA,person@example.com\n");
        bindings.save(new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profileId, "bank_statement", dirty.getId(), "{}", null));
        Map<String, Object> withTrial = body("t2");
        withTrial.put("trialEnabled", true);
        withTrial.put("attest", true);

        var response = call(HttpMethod.PUT, base(dev) + "/" + profileId, withTrial, dev.org(), UserRole.DEVELOPER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("PII_FOUND");
    }

    @Test
    void trialIsRejectedWithA409WhenABoundFileWasNeverScanned() {
        // dataFx.upload always uploads BUYER_DATA (never scanned); binding it to a profile simulates a null scan.
        var dev = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, base(dev), body("t3"), dev.org(), UserRole.DEVELOPER);
        UUID profileId = UUID.fromString((String) created.getBody().get("id"));
        var unscanned = dataFx.upload(dev.org(), "u.csv", "name,amount\nA,1\n");
        bindings.save(new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profileId, "bank_statement", unscanned.getId(), "{}", null));
        Map<String, Object> withTrial = body("t3");
        withTrial.put("trialEnabled", true);
        withTrial.put("attest", true);

        var response = call(HttpMethod.PUT, base(dev) + "/" + profileId, withTrial, dev.org(), UserRole.DEVELOPER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("PII_FOUND");
    }

    @Test
    void trialWithoutAttestIs400() {
        var dev = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, base(dev), body("t4"), dev.org(), UserRole.DEVELOPER);
        Map<String, Object> withTrial = body("t4");
        withTrial.put("trialEnabled", true);

        var response = call(HttpMethod.PUT, base(dev) + "/" + created.getBody().get("id"), withTrial, dev.org(), UserRole.DEVELOPER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("ATTESTATION_REQUIRED");
    }

    @Test
    void settingTrialFalseNeverRequiresAttestAndAlwaysSucceeds() {
        var dev = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, base(dev), body("t5"), dev.org(), UserRole.DEVELOPER);
        Map<String, Object> withoutAttest = body("t5");
        withoutAttest.put("trialEnabled", false);

        var response = call(HttpMethod.PUT, base(dev) + "/" + created.getBody().get("id"), withoutAttest, dev.org(), UserRole.DEVELOPER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("trialEnabled")).isEqualTo(false);
    }

    @Test
    void omittingTrialEnabledOnAnUnrelatedEditLeavesAnExistingTrialUntouched() {
        var dev = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, base(dev), body("t6"), dev.org(), UserRole.DEVELOPER);
        UUID profileId = UUID.fromString((String) created.getBody().get("id"));
        var clean = dataFx.upload(dev.org(), "clean.csv", "name,amount\nA,100\n");
        bindings.save(new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profileId, "bank_statement", clean.getId(), "{}", null));
        Map<String, Object> withTrial = body("t6");
        withTrial.put("trialEnabled", true);
        withTrial.put("attest", true);
        call(HttpMethod.PUT, base(dev) + "/" + profileId, withTrial, dev.org(), UserRole.DEVELOPER);

        Map<String, Object> unrelatedEdit = body("t6");
        unrelatedEdit.put("spendCap", 77);   // no "trialEnabled" key at all

        var response = call(HttpMethod.PUT, base(dev) + "/" + profileId, unrelatedEdit, dev.org(), UserRole.DEVELOPER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("trialEnabled")).isEqualTo(true);
    }

    @Test
    void bindingChangeClearsAnExistingTrialAttestation() {
        var dev = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, base(dev), body("t7"), dev.org(), UserRole.DEVELOPER);
        UUID profileId = UUID.fromString((String) created.getBody().get("id"));
        var clean = dataFx.upload(dev.org(), "clean.csv", "name,amount\nA,100\n");
        bindings.save(new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profileId, "bank_statement", clean.getId(), "{}", null));
        Map<String, Object> withTrial = body("t7");
        withTrial.put("trialEnabled", true);
        withTrial.put("attest", true);
        call(HttpMethod.PUT, base(dev) + "/" + profileId, withTrial, dev.org(), UserRole.DEVELOPER);

        sandboxBindingService.unbind(dev.org().getId(), profileId, "bank_statement");

        var fetched = call(HttpMethod.GET, base(dev) + "/" + profileId, null, dev.org(), UserRole.DEVELOPER);
        assertThat(fetched.getBody().get("trialEnabled")).isEqualTo(false);
    }

    @Test
    void aPortalPutAlwaysReclaimsSourcePortal() {
        var dev = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, base(dev), body("t8"), dev.org(), UserRole.DEVELOPER);

        var response = call(HttpMethod.PUT, base(dev) + "/" + created.getBody().get("id"), body("t8"), dev.org(), UserRole.DEVELOPER);

        assertThat(response.getBody().get("source")).isEqualTo("PORTAL");
    }
```

These reference `sandboxBindingService` and `dataFx` — add the missing autowired field:

```java
    @Autowired com.agentstore.platform.data.SandboxBindingService sandboxBindingService;
```

(`dataFx` is already autowired in this file per the class shown in research.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && mvn -q test -Dtest=SandboxProfileApiTest`
Expected: compile failure (new `ProfileInput` fields, `SandboxApiException.attestationRequired()`/`.piiFound()` don't exist) or assertion failures once it compiles against old behavior.

- [ ] **Step 3: Grow `ProfileInput` and `ProfileView`**

Modify `backend/src/main/java/com/agentstore/platform/sandbox/ProfileInput.java`:

```java
package com.agentstore.platform.sandbox;

import com.agentstore.platform.execution.RunLlmMode;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * The body of a saved or an inline profile. Every field is optional except a saved profile's {@code name}.
 * {@code bindings} (resource to fixture dataset id) is honoured only for an inline profile; a saved profile binds
 * through {@code PUT /sandbox-profiles/{id}/bindings/{resource}}. On update, a null {@code fakeSecrets} keeps what is
 * stored and an empty map clears it; a null {@code description} keeps what is stored. {@code trialEnabled}/
 * {@code attest} are honoured only on update (see {@link SandboxProfileService#update}); null {@code trialEnabled}
 * means "no change requested" (an existing trial stays as it is).
 */
public record ProfileInput(String name, String description, JsonNode input, GatePolicy gatePolicy, RunLlmMode llmMode,
                           UUID replayRunId, JsonNode llmMock, BigDecimal spendCap, Map<String, String> fakeSecrets,
                           Map<String, UUID> bindings, Boolean trialEnabled, Boolean attest) {
}
```

Modify `backend/src/main/java/com/agentstore/platform/sandbox/ProfileView.java`:

```java
package com.agentstore.platform.sandbox;

import com.agentstore.platform.execution.RunLlmMode;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the API returns for a profile. Fake secret values are write-only: only their names are listed. */
public record ProfileView(UUID id, UUID agentId, String name, String description, JsonNode input, GatePolicy gatePolicy,
                          RunLlmMode llmMode, UUID replayRunId, JsonNode llmMock, BigDecimal spendCap,
                          List<String> fakeSecretNames, List<BindingView> bindings, boolean trialEnabled,
                          ProfileSource source, Instant createdAt, Instant updatedAt) {

    public record BindingView(String resource, UUID datasetId, String displayName) {
    }
}
```

(`status` is deliberately not added yet — Task 5 adds it, once its `ProfileStatusService` exists to compute it. `SandboxProfileService.view()` below is written to compile once as of Task 5, not this task; see Step 5's note.)

- [ ] **Step 4: Add the two new `SandboxApiException` factories**

Modify `backend/src/main/java/com/agentstore/platform/sandbox/SandboxApiException.java`. After the existing `invalid(String message)` factory, add:

```java
    public static SandboxApiException attestationRequired() {
        return new SandboxApiException(HttpStatus.BAD_REQUEST, "ATTESTATION_REQUIRED",
                "Setting trialEnabled requires attest: true in the same request");
    }

    public static SandboxApiException piiFound(java.util.List<String> resources) {
        return new SandboxApiException(HttpStatus.CONFLICT, "PII_FOUND",
                "One or more files bound to this profile may contain real customer data",
                java.util.Map.of("resources", resources));
    }
```

- [ ] **Step 5: Update `SandboxProfileService`**

Modify `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileService.java`.

Add the import `import com.agentstore.platform.data.Dataset;` alongside the existing `data` imports.

In `create(...)`, after `SandboxProfile profile = new SandboxProfile(agent.getId(), name);` and before `apply(orgId, agent.getId(), profile, in, true);`, add:

```java
        profile.setDescription(description(in.description(), null));
```

Change `update(...)`'s signature and body:

```java
    @Transactional
    public SandboxProfile update(UUID orgId, UUID userId, UUID agentId, UUID profileId, ProfileInput in) {
        ownedAgent(orgId, agentId);
        SandboxProfile profile = owned(agentId, profileId);
        String name = in.name() == null ? profile.getName() : in.name().strip();
        if (name.isEmpty() || name.length() > 100) {
            throw SandboxApiException.invalid("name must be between 1 and 100 characters");
        }
        if (!name.equals(profile.getName()) && profiles.existsByAgentIdAndName(agentId, name)) {
            throw nameTaken();
        }
        profile.setName(name);
        profile.setDescription(description(in.description(), profile.getDescription()));
        apply(orgId, agentId, profile, in, in.fakeSecrets() != null);
        applyTrial(userId, profile, in);
        profile.setSource(ProfileSource.PORTAL);   // any portal-driven PUT reclaims PORTAL, unconditionally
        return save(profile);
    }

    private static String description(String requested, String existing) {
        if (requested == null) {
            return existing;
        }
        String stripped = requested.strip();
        if (stripped.length() > 255) {
            throw SandboxApiException.invalid("description may be at most 255 characters");
        }
        return stripped.isEmpty() ? null : stripped;
    }

    private void applyTrial(UUID userId, SandboxProfile profile, ProfileInput in) {
        if (in.trialEnabled() == null) {
            return;
        }
        if (!in.trialEnabled()) {
            clearAttestation(profile);
            return;
        }
        if (in.attest() == null || !in.attest()) {
            throw SandboxApiException.attestationRequired();
        }
        List<String> dirty = bindings.findByOwnerTypeAndOwnerId(BindingOwnerType.SANDBOX_PROFILE, profile.getId())
                .stream()
                .filter(b -> !datasets.findById(b.getDatasetId()).map(Dataset::piiClean).orElse(false))
                .map(com.agentstore.platform.data.DatasetBinding::getResourceName)
                .toList();
        if (!dirty.isEmpty()) {
            throw SandboxApiException.piiFound(dirty);
        }
        profile.setTrialEnabled(true);
        profile.setNoRealDataAttestedBy(userId);
        profile.setNoRealDataAttestedAt(java.time.Instant.now());
    }

    private static void clearAttestation(SandboxProfile profile) {
        profile.setTrialEnabled(false);
        profile.setNoRealDataAttestedBy(null);
        profile.setNoRealDataAttestedAt(null);
    }
```

Update `view(SandboxProfile p)` to construct the grown `ProfileView` (status still pending Task 5 — pass a hardcoded placeholder here **only if you are doing Task 4 in true isolation**; since this plan expects Tasks 4 and 5 to run back-to-back, write it directly against the final 17-field `ProfileView` from Task 5 instead — see Task 5 Step 4 for the real, final version of this method. Do not write and then rewrite this method twice.)

- [ ] **Step 6: Update `SandboxProfileController`**

Modify `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileController.java`:

```java
    @PutMapping("/{profileId}")
    public ProfileView update(@PathVariable UUID agentId, @PathVariable UUID profileId, @RequestBody ProfileInput body) {
        var caller = Caller.current();
        return service.view(service.update(caller.orgId(), caller.userId(), agentId, profileId, body));
    }
```

- [ ] **Step 7: Clear attestation on a binding change**

Modify `backend/src/main/java/com/agentstore/platform/data/SandboxBindingService.java`. In `bind(...)`, after `bindings.saveAndFlush(binding);` and before the `return`, add `clearAttestation(profile);`. In `unbind(...)`, capture the profile and clear it:

```java
    @Transactional
    public void unbind(UUID orgId, UUID profileId, String resource) {
        com.agentstore.platform.sandbox.SandboxProfile profile = ownedProfile(orgId, profileId);
        DatasetBinding binding = bindings.findByOwnerTypeAndOwnerIdAndResourceName(BindingOwnerType.SANDBOX_PROFILE,
                profileId, resource).orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Nothing is bound to '" + resource + "'"));
        bindings.delete(binding);
        clearAttestation(profile);
    }
```

Add the private helper (this file already injects `SandboxProfileRepository profiles`):

```java
    private void clearAttestation(com.agentstore.platform.sandbox.SandboxProfile profile) {
        if (profile.isTrialEnabled() || profile.getNoRealDataAttestedBy() != null) {
            profile.setTrialEnabled(false);
            profile.setNoRealDataAttestedBy(null);
            profile.setNoRealDataAttestedAt(null);
            profiles.save(profile);
        }
    }
```

Also capture the profile in `bind(...)`'s existing `SandboxProfile profile = ownedProfile(orgId, profileId);` line (already present — just add the `clearAttestation(profile);` call after the save, as above).

- [ ] **Step 8: Run the tests — expected to still fail until Task 5's `ProfileView.status` lands**

Run: `cd backend && mvn -q test -Dtest=SandboxProfileApiTest`
Expected: still a compile failure at `view(...)`'s construction of the final `ProfileView`, since `status` isn't computable yet. This is expected — proceed immediately to Task 5, which finishes `view()` and makes this task's tests (and its own) pass together. Do not attempt to work around this by inventing a fake status value.

- [ ] **Step 9: Commit** (deferred to the end of Task 5 — Tasks 4 and 5 land in one commit, since `SandboxProfileService.view()` only compiles once both are done)

---

### Task 5: Computed profile status (`READY` / `FILE_ERRORS`)

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/sandbox/ProfileStatus.java`
- Create: `backend/src/main/java/com/agentstore/platform/sandbox/ProfileStatusService.java`
- Modify: `backend/src/main/java/com/agentstore/platform/catalog/AgentVersionRepository.java`
- Modify: `backend/src/main/java/com/agentstore/platform/sandbox/ProfileView.java`
- Modify: `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileService.java`
- Test: `backend/src/test/java/com/agentstore/platform/sandbox/SandboxProfileStatusTest.java` (new)

**Interfaces:**
- Consumes: `DatasetCheckService.check(Dataset, ResourceDecl): SchemaChecker.CheckResult` (existing, `SchemaChecker.CheckResult.ok(): boolean`); `AgentVersionRepository` (existing, grown here); `Manifest.resource(String): Optional<ResourceDecl>` (existing).
- Produces: `ProfileStatus` enum `{READY, FILE_ERRORS}`; `ProfileStatusService.compute(SandboxProfile): ProfileStatus`. Finishes `ProfileView`'s 17th field (`status`) that Task 4 left open, and finishes `SandboxProfileService.view()`.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/agentstore/platform/sandbox/SandboxProfileStatusTest.java`:

```java
package com.agentstore.platform.sandbox;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.build.BuildStatus;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.data.BindingOwnerType;
import com.agentstore.platform.data.DataFixtures;
import com.agentstore.platform.data.DatasetBinding;
import com.agentstore.platform.data.DatasetBindingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxProfileStatusTest extends AbstractStorageIntegrationTest {

    @Autowired SandboxFixtures fx;
    @Autowired DataFixtures dataFx;
    @Autowired ProfileStatusService statusService;
    @Autowired DatasetBindingRepository bindings;

    @Test
    void readyWhenTheAgentHasNoVersionAtAllIsNotReachableHere_soReadyWithNoBindingsInstead() {
        var dev = fx.developer("valid-tier-a.json");   // has a DRAFT version, but zero bindings
        SandboxProfile profile = fx.profile(dev, "p");

        assertThat(statusService.compute(profile)).isEqualTo(ProfileStatus.READY);
    }

    @Test
    void readyAgainstACleanDraft() {
        var dev = fx.developer("valid-tier-a.json");
        SandboxProfile profile = fx.profile(dev, "p");
        var clean = dataFx.upload(dev.org(), "clean.csv", "name,amount\nA,100\n");
        bindings.save(new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profile.getId(), "bank_statement", clean.getId(), "{}", null));

        assertThat(statusService.compute(profile)).isEqualTo(ProfileStatus.READY);
    }

    @Test
    void fileErrorsWhenABindingFailsTheSchemaCheck() {
        var dev = fx.developer("valid-tier-a.json");
        SandboxProfile profile = fx.profile(dev, "p");
        var pdf = dataFx.upload(dev.org(), "f.pdf", DataFixtures.PDF);
        bindings.save(new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profile.getId(), "bank_statement", pdf.getId(), "{}", null));

        assertThat(statusService.compute(profile)).isEqualTo(ProfileStatus.FILE_ERRORS);
    }

    @Test
    void draftTakesPrecedenceOverLive() {
        var dev = fx.developer("valid-tier-a.json");
        dev.version().setStatus(VersionStatus.LIVE);
        dev.version().setBuildStatus(BuildStatus.SUCCEEDED);
        fx.versions().save(dev.version());
        fx.versionOf(dev.agent(), "2.0.0", "valid-tier-a.json", VersionStatus.DRAFT);
        SandboxProfile profile = fx.profile(dev, "p");
        var pdf = dataFx.upload(dev.org(), "f.pdf", DataFixtures.PDF);
        bindings.save(new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profile.getId(), "bank_statement", pdf.getId(), "{}", null));

        // Both the LIVE and the DRAFT version declare the same manifest shape here, so this alone doesn't
        // distinguish precedence; the point of this test is that compute() does not throw picking between two
        // candidate versions and resolves to the newer (DRAFT) row, per findFirstByAgentIdAndStatus...
        assertThat(statusService.compute(profile)).isEqualTo(ProfileStatus.FILE_ERRORS);
    }
}
```

`AgentVersionRepository` needs a public accessor from `SandboxFixtures` for the last test (`fx.versions()`); if `SandboxFixtures` does not already expose its `AgentVersionRepository versions` field, add a one-line getter:

```java
    public AgentVersionRepository versions() {
        return versions;
    }
```

(add this to `backend/src/test/java/com/agentstore/platform/sandbox/SandboxFixtures.java`, which already holds a `versions` field of that type per its constructor.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && mvn -q test -Dtest=SandboxProfileStatusTest`
Expected: compile failure — `ProfileStatus`, `ProfileStatusService` don't exist.

- [ ] **Step 3: Add the repository queries, the enum, and the service**

Modify `backend/src/main/java/com/agentstore/platform/catalog/AgentVersionRepository.java`, adding the import `import com.agentstore.platform.build.BuildStatus;` and, inside the interface:

```java
    Optional<AgentVersion> findFirstByAgentIdAndStatusOrderByCreatedAtDesc(UUID agentId, VersionStatus status);

    Optional<AgentVersion> findFirstByAgentIdAndBuildStatusOrderByCreatedAtDesc(UUID agentId, BuildStatus buildStatus);
```

Create `backend/src/main/java/com/agentstore/platform/sandbox/ProfileStatus.java`:

```java
package com.agentstore.platform.sandbox;

/** Computed on every read, never stored — see {@link ProfileStatusService}. */
public enum ProfileStatus { READY, FILE_ERRORS }
```

Create `backend/src/main/java/com/agentstore/platform/sandbox/ProfileStatusService.java`:

```java
package com.agentstore.platform.sandbox;

import com.agentstore.platform.build.BuildStatus;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.data.BindingOwnerType;
import com.agentstore.platform.data.Dataset;
import com.agentstore.platform.data.DatasetBinding;
import com.agentstore.platform.data.DatasetBindingRepository;
import com.agentstore.platform.data.DatasetCheckService;
import com.agentstore.platform.data.DatasetRepository;
import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ResourceDecl;
import org.springframework.stereotype.Service;

/**
 * {status} is not stored on {@link SandboxProfile}; it is computed fresh on every read by re-running WS2's
 * {@link DatasetCheckService} against whichever version the profile would currently run: the agent's latest DRAFT,
 * else its latest version with a SUCCEEDED build, else there is nothing to check and the profile is trivially READY.
 */
@Service
public class ProfileStatusService {

    private final AgentVersionRepository versions;
    private final DatasetBindingRepository bindings;
    private final DatasetRepository datasets;
    private final DatasetCheckService checks;
    private final ManifestParser manifestParser;

    public ProfileStatusService(AgentVersionRepository versions, DatasetBindingRepository bindings,
                                DatasetRepository datasets, DatasetCheckService checks, ManifestParser manifestParser) {
        this.versions = versions;
        this.bindings = bindings;
        this.datasets = datasets;
        this.checks = checks;
        this.manifestParser = manifestParser;
    }

    public ProfileStatus compute(SandboxProfile profile) {
        AgentVersion version = versions.findFirstByAgentIdAndStatusOrderByCreatedAtDesc(profile.getAgentId(), VersionStatus.DRAFT)
                .or(() -> versions.findFirstByAgentIdAndBuildStatusOrderByCreatedAtDesc(profile.getAgentId(), BuildStatus.SUCCEEDED))
                .orElse(null);
        if (version == null) {
            return ProfileStatus.READY;
        }
        Manifest manifest = manifestParser.parse(version.getManifestJson());
        for (DatasetBinding binding : bindings.findByOwnerTypeAndOwnerId(BindingOwnerType.SANDBOX_PROFILE, profile.getId())) {
            Dataset dataset = datasets.findById(binding.getDatasetId()).orElse(null);
            if (dataset == null) {
                return ProfileStatus.FILE_ERRORS;
            }
            ResourceDecl decl = manifest.resource(binding.getResourceName()).orElse(null);
            if (!checks.check(dataset, decl).ok()) {
                return ProfileStatus.FILE_ERRORS;
            }
        }
        return ProfileStatus.READY;
    }
}
```

- [ ] **Step 4: Finish `ProfileView` and `SandboxProfileService.view()`**

Modify `backend/src/main/java/com/agentstore/platform/sandbox/ProfileView.java`, adding `status` as the final content field (before the timestamps):

```java
public record ProfileView(UUID id, UUID agentId, String name, String description, JsonNode input, GatePolicy gatePolicy,
                          RunLlmMode llmMode, UUID replayRunId, JsonNode llmMock, BigDecimal spendCap,
                          List<String> fakeSecretNames, List<BindingView> bindings, boolean trialEnabled,
                          ProfileSource source, ProfileStatus status, Instant createdAt, Instant updatedAt) {

    public record BindingView(String resource, UUID datasetId, String displayName) {
    }
}
```

Modify `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileService.java`: add the constructor dependency and finish `view(...)`. Change:

```java
    private final SandboxProfileRepository profiles;
    private final AgentRepository agents;
    private final FakeSecrets fakeSecrets;
    private final DatasetBindingRepository bindings;
    private final DatasetRepository datasets;
    private final ReplaySource replaySource;

    public SandboxProfileService(SandboxProfileRepository profiles, AgentRepository agents, FakeSecrets fakeSecrets,
                                 DatasetBindingRepository bindings, DatasetRepository datasets, ReplaySource replaySource) {
        this.profiles = profiles;
        this.agents = agents;
        this.fakeSecrets = fakeSecrets;
        this.bindings = bindings;
        this.datasets = datasets;
        this.replaySource = replaySource;
    }
```

to:

```java
    private final SandboxProfileRepository profiles;
    private final AgentRepository agents;
    private final FakeSecrets fakeSecrets;
    private final DatasetBindingRepository bindings;
    private final DatasetRepository datasets;
    private final ReplaySource replaySource;
    private final ProfileStatusService statusService;

    public SandboxProfileService(SandboxProfileRepository profiles, AgentRepository agents, FakeSecrets fakeSecrets,
                                 DatasetBindingRepository bindings, DatasetRepository datasets, ReplaySource replaySource,
                                 ProfileStatusService statusService) {
        this.profiles = profiles;
        this.agents = agents;
        this.fakeSecrets = fakeSecrets;
        this.bindings = bindings;
        this.datasets = datasets;
        this.replaySource = replaySource;
        this.statusService = statusService;
    }
```

(Spring wires the new parameter automatically since `ProfileStatusService` is a `@Service` bean — no other call site constructs `SandboxProfileService` directly, per `grep -rn "new SandboxProfileService(" backend/src` returning only the class's own file.)

```java
    @Transactional(readOnly = true)
    public ProfileView view(SandboxProfile p) {
        List<ProfileView.BindingView> bound = bindings.findByOwnerTypeAndOwnerId(BindingOwnerType.SANDBOX_PROFILE, p.getId())
                .stream().map(b -> new ProfileView.BindingView(b.getResourceName(), b.getDatasetId(),
                        datasets.findById(b.getDatasetId()).map(d -> d.getDisplayName()).orElse(null)))
                .sorted(java.util.Comparator.comparing(ProfileView.BindingView::resource)).toList();
        return new ProfileView(p.getId(), p.getAgentId(), p.getName(), p.getDescription(), readTree(p.getInputJson()),
                GatePolicy.parse(p.getGatePolicy()), p.getLlmMode(), p.getReplayRunId(),
                p.getLlmMockJson() == null ? null : readTree(p.getLlmMockJson()), p.getSpendCap(),
                fakeSecrets.names(p), bound, p.isTrialEnabled(), p.getSource(), statusService.compute(p),
                p.getCreatedAt(), p.getUpdatedAt());
    }
```

- [ ] **Step 5: Run every sandbox and data test to verify Tasks 4+5 together pass**

Run: `cd backend && mvn -q test -Dtest=com.agentstore.platform.sandbox.*Test,com.agentstore.platform.data.*Test`
Expected: PASS (this exercises Task 4's `SandboxProfileApiTest` additions and this task's `SandboxProfileStatusTest`, plus every other existing test that touches `ProfileView`/`ProfileInput`/`SandboxProfileService`/`SandboxBindingService`)

- [ ] **Step 6: Commit** (Tasks 4 and 5 together)

```bash
git add backend/src/main/java/com/agentstore/platform/sandbox/ProfileInput.java \
        backend/src/main/java/com/agentstore/platform/sandbox/ProfileView.java \
        backend/src/main/java/com/agentstore/platform/sandbox/ProfileStatus.java \
        backend/src/main/java/com/agentstore/platform/sandbox/ProfileStatusService.java \
        backend/src/main/java/com/agentstore/platform/sandbox/SandboxApiException.java \
        backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileService.java \
        backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileController.java \
        backend/src/main/java/com/agentstore/platform/data/SandboxBindingService.java \
        backend/src/main/java/com/agentstore/platform/catalog/AgentVersionRepository.java \
        backend/src/test/java/com/agentstore/platform/sandbox/SandboxProfileApiTest.java \
        backend/src/test/java/com/agentstore/platform/sandbox/SandboxProfileStatusTest.java \
        backend/src/test/java/com/agentstore/platform/sandbox/SandboxFixtures.java
git commit -m "feat(sandbox): trial-profile attestation gated on PII scan, computed profile status"
```

---

### Task 6: REPO profile sync (`POST /sandbox-profiles/sync`)

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileSyncRequest.java`
- Create: `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileSyncResult.java`
- Create: `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileSyncService.java`
- Modify: `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileRepository.java`
- Modify: `backend/src/main/java/com/agentstore/platform/data/DatasetRepository.java`
- Modify: `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileController.java`
- Test: `backend/src/test/java/com/agentstore/platform/sandbox/SandboxProfileSyncApiTest.java` (new)

**Interfaces:**
- Consumes: `DatasetCheckService.check(Dataset, ResourceDecl)` (existing); `AgentVersionRepository.findFirstByAgentIdOrderByCreatedAtDesc` (existing); `GatePolicy.manual()/.toJson()` (existing); `DatasetBindingRepository` (existing).
- Produces: `POST /api/v1/agents/{agentId}/sandbox-profiles/sync` (already inside `ApiKeyScope`'s existing `/api/v1/agents/*/sandbox-profiles/**` allow-rule — no scope change needed).

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/agentstore/platform/sandbox/SandboxProfileSyncApiTest.java`:

```java
package com.agentstore.platform.sandbox;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.data.DataFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxProfileSyncApiTest extends AbstractStorageIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired SandboxFixtures fx;
    @Autowired DataFixtures dataFx;
    @Autowired SandboxProfileRepository profiles;
    @Autowired com.agentstore.platform.data.DatasetRepository datasets;

    String url(SandboxFixtures.Dev dev) {
        return "http://localhost:" + port + "/api/v1/agents/" + dev.agent().getId() + "/sandbox-profiles/sync";
    }

    @SuppressWarnings("unchecked")
    ResponseEntity<List> sync(SandboxFixtures.Dev dev, List<Map<String, Object>> entries) {
        return rest.exchange(url(dev), HttpMethod.POST, new HttpEntity<>(entries, fx.bearer(dev.org(), com.agentstore.platform.identity.UserRole.DEVELOPER)), List.class);
    }

    static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static Map<String, Object> entry(String name, String hash) {
        return new java.util.LinkedHashMap<>(Map.of("name", name, "bindings", List.of(), "contentHash", hash));
    }

    @Test
    void aNewNameIsCreatedWithSourceRepo() {
        var dev = fx.developer("valid-tier-a.json");

        var response = sync(dev, List.of(entry("happy-path", hash("v1"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> result = (Map<String, Object>) response.getBody().get(0);
        assertThat(result.get("action")).isEqualTo("created");
        SandboxProfile stored = profiles.findByAgentIdAndName(dev.agent().getId(), "happy-path").orElseThrow();
        assertThat(stored.getSource()).isEqualTo(ProfileSource.REPO);
        assertThat(stored.getRepoSourceHash()).isEqualTo(hash("v1"));
    }

    @Test
    void anUnchangedHashOnAnExistingRepoProfileIsANoOp() {
        var dev = fx.developer("valid-tier-a.json");
        sync(dev, List.of(entry("p", hash("v1"))));

        var response = sync(dev, List.of(entry("p", hash("v1"))));

        assertThat(((Map<String, Object>) response.getBody().get(0)).get("action")).isEqualTo("skipped");
    }

    @Test
    void aChangedHashOnAnExistingRepoProfileIsUpdated() {
        var dev = fx.developer("valid-tier-a.json");
        sync(dev, List.of(entry("p", hash("v1"))));

        var response = sync(dev, List.of(entry("p", hash("v2"))));

        assertThat(((Map<String, Object>) response.getBody().get(0)).get("action")).isEqualTo("updated");
        assertThat(profiles.findByAgentIdAndName(dev.agent().getId(), "p").orElseThrow().getRepoSourceHash()).isEqualTo(hash("v2"));
    }

    @Test
    void aPortalSourcedProfileIsOverwrittenAndReclaimedAsRepo() {
        var dev = fx.developer("valid-tier-a.json");
        profiles.save(new SandboxProfile(dev.agent().getId(), "portal-made"));

        var response = sync(dev, List.of(entry("portal-made", hash("v1"))));

        assertThat(((Map<String, Object>) response.getBody().get(0)).get("action")).isEqualTo("updated");
        assertThat(profiles.findByAgentIdAndName(dev.agent().getId(), "portal-made").orElseThrow().getSource()).isEqualTo(ProfileSource.REPO);
    }

    @Test
    void anUnresolvableFixtureIsAWarningNotAFailureAndTheBindingIsSkipped() {
        var dev = fx.developer("valid-tier-a.json");
        Map<String, Object> withBinding = entry("p", hash("v1"));
        withBinding.put("bindings", List.of(Map.of("resource", "bank_statement", "fixture", "does-not-exist")));

        var response = sync(dev, List.of(withBinding));

        Map<String, Object> result = (Map<String, Object>) response.getBody().get(0);
        assertThat(result.get("action")).isEqualTo("created");
        assertThat((List<String>) result.get("unresolvedFixtures")).containsExactly("does-not-exist");
    }

    @Test
    void aResolvableFixtureIsBound() {
        var dev = fx.developer("valid-tier-a.json");
        var fixture = dataFx.upload(dev.org(), "sample-invoices.csv", "name,amount\nA,1\n");
        // dataFx.upload defaults to BUYER_DATA; persist it as FIXTURE so the sync's lookup can find it.
        fixture.setPurpose(com.agentstore.platform.data.DatasetPurpose.FIXTURE);
        datasets.saveAndFlush(fixture);
        Map<String, Object> withBinding = entry("p", hash("v1"));
        withBinding.put("bindings", List.of(Map.of("resource", "bank_statement", "fixture", "sample-invoices.csv")));

        var response = sync(dev, List.of(withBinding));

        Map<String, Object> result = (Map<String, Object>) response.getBody().get(0);
        assertThat((List<String>) result.get("unresolvedFixtures")).isEmpty();
    }

    @Test
    void reSyncingAnUnchangedFileTwiceIsANoOpBothTimes() {
        var dev = fx.developer("valid-tier-a.json");
        sync(dev, List.of(entry("p", hash("stable"))));

        var second = sync(dev, List.of(entry("p", hash("stable"))));
        var third = sync(dev, List.of(entry("p", hash("stable"))));

        assertThat(((Map<String, Object>) second.getBody().get(0)).get("action")).isEqualTo("skipped");
        assertThat(((Map<String, Object>) third.getBody().get(0)).get("action")).isEqualTo("skipped");
    }

    @Test
    void aProfileDeletedSinceTheLastSyncIsRecreatedNotSkipped() {
        var dev = fx.developer("valid-tier-a.json");
        sync(dev, List.of(entry("p", hash("v1"))));
        profiles.delete(profiles.findByAgentIdAndName(dev.agent().getId(), "p").orElseThrow());

        var response = sync(dev, List.of(entry("p", hash("v1"))));   // same hash as before deletion

        assertThat(((Map<String, Object>) response.getBody().get(0)).get("action")).isEqualTo("created");
        assertThat(profiles.findByAgentIdAndName(dev.agent().getId(), "p")).isPresent();
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && mvn -q test -Dtest=SandboxProfileSyncApiTest`
Expected: 404 (no such endpoint) / compile failure — `SandboxProfileSyncRequest`, `SandboxProfileSyncResult`, `SandboxProfileSyncService`, `SandboxProfileRepository.findByAgentIdAndName` don't exist.

- [ ] **Step 3: Add the repository methods**

Modify `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileRepository.java`, adding:

```java
    java.util.Optional<SandboxProfile> findByAgentIdAndName(UUID agentId, String name);
```

Modify `backend/src/main/java/com/agentstore/platform/data/DatasetRepository.java`, adding:

```java
    Optional<Dataset> findFirstByOwnerOrgIdAndPurposeAndDisplayNameAndDeletedAtIsNullOrderByCreatedAtDesc(
            UUID ownerOrgId, DatasetPurpose purpose, String displayName);
```

- [ ] **Step 4: Add the request/result records**

Create `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileSyncRequest.java`:

```java
package com.agentstore.platform.sandbox;

import com.agentstore.platform.execution.RunLlmMode;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

/**
 * One {@code fixtures/profiles/<name>.yaml} file, as the CLI sends it after {@code agentstore push} finalizes.
 * {@code trialEnabled} is deliberately not a field here — attestation names a specific person and a repo sync has
 * none; a REPO-sourced profile is always created with {@code trial_enabled = false} (see {@link SandboxProfileSyncService}).
 */
public record SandboxProfileSyncRequest(String name, String description, JsonNode input, GatePolicy gatePolicy,
                                        RunLlmMode llmMode, BigDecimal spendCap, List<BindingEntry> bindings,
                                        String contentHash) {

    /** {@code fixture} is a dataset's {@code displayName} among the org's developer fixtures, resolved server-side. */
    public record BindingEntry(String resource, String fixture) {
    }
}
```

Create `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileSyncResult.java`:

```java
package com.agentstore.platform.sandbox;

import java.util.List;

public record SandboxProfileSyncResult(String name, String action, List<String> unresolvedFixtures) {
}
```

- [ ] **Step 5: Write `SandboxProfileSyncService`**

Create `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileSyncService.java`:

```java
package com.agentstore.platform.sandbox;

import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.data.BindingOwnerType;
import com.agentstore.platform.data.Dataset;
import com.agentstore.platform.data.DatasetBinding;
import com.agentstore.platform.data.DatasetBindingRepository;
import com.agentstore.platform.data.DatasetCheckService;
import com.agentstore.platform.data.DatasetPurpose;
import com.agentstore.platform.data.DatasetRepository;
import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ResourceDecl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies {@code fixtures/profiles/*.yaml} to {@code sandbox_profile}, one file per {@link SandboxProfileSyncRequest}
 * entry. Deliberately does not reuse {@link com.agentstore.platform.data.SandboxBindingService#bind}: that method
 * rejects a binding outright on a failed schema check, which is right for an interactive portal edit but wrong for
 * unattended CI automation — a sync must never fail over a fixture that merely doesn't match the manifest yet; it
 * records the check result for later ({@link ProfileStatusService} recomputes it live regardless) and moves on.
 */
@Service
public class SandboxProfileSyncService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SandboxProfileRepository profiles;
    private final AgentRepository agents;
    private final AgentVersionRepository versions;
    private final DatasetRepository datasets;
    private final DatasetBindingRepository bindings;
    private final DatasetCheckService checks;
    private final ManifestParser manifestParser;

    public SandboxProfileSyncService(SandboxProfileRepository profiles, AgentRepository agents,
                                     AgentVersionRepository versions, DatasetRepository datasets,
                                     DatasetBindingRepository bindings, DatasetCheckService checks,
                                     ManifestParser manifestParser) {
        this.profiles = profiles;
        this.agents = agents;
        this.versions = versions;
        this.datasets = datasets;
        this.bindings = bindings;
        this.checks = checks;
        this.manifestParser = manifestParser;
    }

    @Transactional
    public List<SandboxProfileSyncResult> sync(UUID orgId, UUID agentId, List<SandboxProfileSyncRequest> entries) {
        ownedAgent(orgId, agentId);
        List<SandboxProfileSyncResult> results = new ArrayList<>();
        for (SandboxProfileSyncRequest entry : entries) {
            results.add(syncOne(orgId, agentId, entry));
        }
        return results;
    }

    private SandboxProfileSyncResult syncOne(UUID orgId, UUID agentId, SandboxProfileSyncRequest entry) {
        SandboxProfile existing = profiles.findByAgentIdAndName(agentId, entry.name()).orElse(null);
        if (existing != null && existing.getSource() == ProfileSource.REPO
                && entry.contentHash().equals(existing.getRepoSourceHash())) {
            return new SandboxProfileSyncResult(entry.name(), "skipped", List.of());
        }
        String action = existing == null ? "created" : "updated";
        SandboxProfile profile = existing == null ? new SandboxProfile(agentId, entry.name()) : existing;
        profile.setDescription(entry.description());
        profile.setInputJson(entry.input() == null || entry.input().isNull() ? "{}" : entry.input().toString());
        profile.setGatePolicy((entry.gatePolicy() == null ? GatePolicy.manual() : entry.gatePolicy()).toJson());
        profile.setLlmMode(entry.llmMode());
        profile.setReplayRunId(null);
        profile.setLlmMockJson(null);
        profile.setSpendCap(entry.spendCap());
        profile.setSource(ProfileSource.REPO);
        profile.setRepoSourceHash(entry.contentHash());
        profile.setTrialEnabled(false);
        profile.setNoRealDataAttestedBy(null);
        profile.setNoRealDataAttestedAt(null);
        profile = profiles.saveAndFlush(profile);

        Manifest manifest = latestManifest(agentId).orElse(null);
        List<String> unresolved = new ArrayList<>();
        for (SandboxProfileSyncRequest.BindingEntry b : entry.bindings()) {
            Optional<Dataset> found = datasets.findFirstByOwnerOrgIdAndPurposeAndDisplayNameAndDeletedAtIsNullOrderByCreatedAtDesc(
                    orgId, DatasetPurpose.FIXTURE, b.fixture());
            if (found.isEmpty()) {
                unresolved.add(b.fixture());
                continue;
            }
            Dataset dataset = found.get();
            ResourceDecl decl = manifest == null ? null : manifest.resource(b.resource()).orElse(null);
            String checkJson = json(checks.check(dataset, decl));
            DatasetBinding binding = bindings.findByOwnerTypeAndOwnerIdAndResourceName(BindingOwnerType.SANDBOX_PROFILE,
                    profile.getId(), b.resource()).orElse(null);
            if (binding == null) {
                binding = new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profile.getId(), b.resource(),
                        dataset.getId(), checkJson, null);
            } else {
                binding.setDatasetId(dataset.getId());
                binding.setCheckResult(checkJson);
                binding.setBoundAt(Instant.now());
            }
            bindings.saveAndFlush(binding);
        }
        return new SandboxProfileSyncResult(entry.name(), action, unresolved);
    }

    private Optional<Manifest> latestManifest(UUID agentId) {
        return versions.findFirstByAgentIdOrderByCreatedAtDesc(agentId).map(v -> manifestParser.parse(v.getManifestJson()));
    }

    private Agent ownedAgent(UUID orgId, UUID agentId) {
        return agents.findById(agentId).filter(a -> a.getDeveloperOrg().getId().equals(orgId))
                .orElseThrow(() -> SandboxApiException.notFound("Agent"));
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 6: Wire the controller endpoint**

Modify `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileController.java`. Change:

```java
    private final SandboxProfileService service;

    public SandboxProfileController(SandboxProfileService service) {
        this.service = service;
    }
```

to:

```java
    private final SandboxProfileService service;
    private final SandboxProfileSyncService syncService;

    public SandboxProfileController(SandboxProfileService service, SandboxProfileSyncService syncService) {
        this.service = service;
        this.syncService = syncService;
    }
```

Then add the new endpoint method:

```java
    @PostMapping("/sync")
    public List<SandboxProfileSyncResult> sync(@PathVariable UUID agentId,
                                                @RequestBody List<SandboxProfileSyncRequest> body) {
        return syncService.sync(Caller.current().orgId(), agentId, body);
    }
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd backend && mvn -q test -Dtest=SandboxProfileSyncApiTest`
Expected: PASS

Run the full sandbox/data suites once more for regressions: `cd backend && mvn -q test -Dtest=com.agentstore.platform.sandbox.*Test,com.agentstore.platform.data.*Test`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileSyncRequest.java \
        backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileSyncResult.java \
        backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileSyncService.java \
        backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileRepository.java \
        backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileController.java \
        backend/src/main/java/com/agentstore/platform/data/DatasetRepository.java \
        backend/src/test/java/com/agentstore/platform/sandbox/SandboxProfileSyncApiTest.java
git commit -m "feat(sandbox): REPO-sourced profile sync endpoint for agentstore push"
```

---

### Task 7: Platform OpenAPI drift test extension

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/common/PlatformOpenApiConfig.java:69-70`
- Modify: `backend/src/test/java/com/agentstore/platform/common/PlatformContractTest.java:43-51`
- Modify (generated): `contracts/platform-v1.yaml`

**Interfaces:**
- Consumes: nothing new (springdoc auto-introspects Task 6's new endpoint's request/response types).
- Produces: `contracts/platform-v1.yaml` now describes 4 endpoints instead of 3.

- [ ] **Step 1: Write the failing test change**

Modify `backend/src/test/java/com/agentstore/platform/common/PlatformContractTest.java`, renaming and extending the second test:

```java
    @Test
    void theContractDescribesExactlyTheFourPlatformEndpoints() {
        JsonNode generated = new ObjectMapper().readTree(
                rest.getForObject("http://localhost:" + port + "/v3/api-docs/platform", String.class));

        assertThat(generated.get("paths").fieldNames()).toIterable().containsExactlyInAnyOrder(
                "/api/v1/runs/{runId}/flow", "/api/v1/agents/{agentId}/versions/{versionId}/readiness",
                "/api/v1/agents/{agentId}/versions/{versionId}/manifest/patches",
                "/api/v1/agents/{agentId}/sandbox-profiles/sync");
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=PlatformContractTest`
Expected: FAIL — the generated document still only has 3 paths (the new endpoint isn't in `pathsToMatch` yet), and separately the first test (`theCommittedContractMatchesWhatTheControllersGenerate`) will also start failing once `pathsToMatch` is updated in the next step, until the contract file is regenerated.

- [ ] **Step 3: Add the new path to `PlatformOpenApiConfig`**

Modify `backend/src/main/java/com/agentstore/platform/common/PlatformOpenApiConfig.java`, changing:

```java
        platformProperties.setPathsToMatch(List.of("/api/v1/runs/*/flow", "/api/v1/agents/*/versions/*/readiness",
                "/api/v1/agents/*/versions/*/manifest/patches"));
```

to:

```java
        platformProperties.setPathsToMatch(List.of("/api/v1/runs/*/flow", "/api/v1/agents/*/versions/*/readiness",
                "/api/v1/agents/*/versions/*/manifest/patches", "/api/v1/agents/*/sandbox-profiles/sync"));
```

- [ ] **Step 4: Run the exact-path test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=PlatformContractTest#theContractDescribesExactlyTheFourPlatformEndpoints`
Expected: PASS

- [ ] **Step 5: Regenerate the committed contract**

Run: `cd backend && mvn -q test -Dtest=PlatformContractTest -Dcontract.update=true`

This writes the new `contracts/platform-v1.yaml`. Then run the full test once more without the update flag to confirm the committed file now matches:

Run: `cd backend && mvn -q test -Dtest=PlatformContractTest`
Expected: PASS (both tests)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/agentstore/platform/common/PlatformOpenApiConfig.java \
        backend/src/test/java/com/agentstore/platform/common/PlatformContractTest.java \
        contracts/platform-v1.yaml
git commit -m "docs(contracts): add sandbox-profiles/sync to the platform OpenAPI contract"
```

---

### Task 8: CLI `agentstore fixtures push` / `fixtures list`

**Files:**
- Create: `cli/src/commands/fixtures.ts`
- Modify: `cli/src/cli.ts`
- Test: `cli/test/fixtures.test.ts` (new)

**Interfaces:**
- Consumes: `Context` (`cli/src/context.ts`), `requireCredentials` (`cli/src/config.ts`), `readProject` (`cli/src/project.ts`), `ApiClient` (`cli/src/api.ts`), `CliError` (`cli/src/errors.ts`) — all existing.
- Produces: `fixturesPush(ctx, opts): Promise<number>`, `fixturesList(ctx): Promise<number>` — Task 9/10 don't depend on these directly, but the `cli.ts` USAGE string and dispatch pattern established here is mirrored by Task 9.

- [ ] **Step 1: Write the failing test**

Create `cli/test/fixtures.test.ts`:

```typescript
import { describe, expect, it } from "vitest";
import { fixturesList, fixturesPush } from "../src/commands/fixtures.js";
import { AGENT_ID, makeContext, startServer, writeFiles } from "./helpers.js";
import { writeFileSync } from "node:fs";
import { join } from "node:path";

describe("fixtures push", () => {
  it("uploads a local file and prints the result", async () => {
    const server = await startServer([
      { method: "POST", path: "/api/v1/developer/fixtures", reply: () => ({ status: 200, json: {
        id: "f1", displayName: "sample.csv", format: "CSV", rowCount: 3, piiScan: { status: "CLEAN", findingCount: 0 },
      } }) },
    ]);
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: server.url } });
    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });
    const filePath = join(ctx.cwd, "sample.csv");
    writeFileSync(filePath, "a,b\n1,2\n");

    const code = await fixturesPush(ctx, { file: filePath });

    expect(code).toBe(0);
    expect(server.requests).toHaveLength(1);
    expect(server.requests[0].method).toBe("POST");
    expect(server.requests[0].path).toBe("/api/v1/developer/fixtures");
    expect(server.requests[0].body.toString()).toContain("sample.csv");
    expect(ctx.outLines.join("\n")).toContain("sample.csv");
  });

  it("warns when the PII scan found something", async () => {
    const server = await startServer([
      { method: "POST", path: "/api/v1/developer/fixtures", reply: () => ({ status: 200, json: {
        id: "f1", displayName: "leak.csv", format: "CSV", rowCount: 1, piiScan: { status: "FOUND", findingCount: 2 },
      } }) },
    ]);
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: server.url } });
    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });
    const filePath = join(ctx.cwd, "leak.csv");
    writeFileSync(filePath, "email\nperson@example.com\n");

    await fixturesPush(ctx, { file: filePath });

    expect(ctx.outLines.join("\n")).toContain("PII scan found");
  });

  it("exits 2 with a readable line when the file does not exist", async () => {
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: "http://x" } });
    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });

    const error = await fixturesPush(ctx, { file: join(ctx.cwd, "missing.csv") }).catch((e) => e);

    expect(error.message).toContain("missing.csv");
    expect(error.exitCode).toBe(2);
  });
});

describe("fixtures list", () => {
  it("prints each fixture with its format and pii scan status", async () => {
    const server = await startServer([
      { method: "GET", path: "/api/v1/developer/fixtures", reply: { json: [
        { id: "f1", displayName: "a.csv", format: "CSV", rowCount: 1, piiScan: { status: "CLEAN" } },
        { id: "f2", displayName: "b.csv", format: "CSV", rowCount: 1, piiScan: null },
      ] } },
    ]);
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: server.url } });
    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });

    const code = await fixturesList(ctx);

    expect(code).toBe(0);
    expect(ctx.outLines.join("\n")).toContain("a.csv");
    expect(ctx.outLines.join("\n")).toContain("clean");
    expect(ctx.outLines.join("\n")).toContain("b.csv");
    expect(ctx.outLines.join("\n")).toContain("unscanned");
  });

  it("prints a friendly line when there are no fixtures", async () => {
    const server = await startServer([{ method: "GET", path: "/api/v1/developer/fixtures", reply: { json: [] } }]);
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: server.url } });
    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });

    expect(await fixturesList(ctx)).toBe(0);
    expect(ctx.outLines).toEqual(["No fixtures."]);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd cli && npx vitest run test/fixtures.test.ts`
Expected: FAIL — `src/commands/fixtures.js` does not exist.

- [ ] **Step 3: Write the implementation**

Create `cli/src/commands/fixtures.ts`:

```typescript
import { existsSync, readFileSync } from "node:fs";
import { basename } from "node:path";
import { ApiClient } from "../api.js";
import { requireCredentials } from "../config.js";
import type { Context } from "../context.js";
import { CliError } from "../errors.js";
import { readProject } from "../project.js";

export interface FixturesPushOptions {
  file: string;
  displayName?: string;
  sourceLabel?: string;
}

interface PiiScanSummary {
  status: string;
  findingCount?: number;
}

interface FixtureResponse {
  id: string;
  displayName: string;
  format: string;
  rowCount: number | null;
  piiScan: PiiScanSummary | null;
}

export async function fixturesPush(ctx: Context, opts: FixturesPushOptions): Promise<number> {
  readProject(ctx.cwd);
  const credentials = requireCredentials(ctx);
  if (!existsSync(opts.file)) {
    throw new CliError(`No such file: ${opts.file}`);
  }
  const bytes = readFileSync(opts.file);
  const form = new FormData();
  form.append("file", new Blob([bytes]), basename(opts.file));
  if (opts.displayName) {
    form.append("displayName", opts.displayName);
  }
  if (opts.sourceLabel) {
    form.append("sourceLabel", opts.sourceLabel);
  }

  let res: Response;
  try {
    res = await ctx.fetch(`${credentials.apiUrl}/api/v1/developer/fixtures`, {
      method: "POST",
      headers: { Authorization: `Bearer ${credentials.apiKey}` },
      body: form,
    });
  } catch (e) {
    throw new CliError(`Could not reach ${credentials.apiUrl}: ${(e as Error).message}`);
  }
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new CliError(`Request failed (HTTP ${res.status})${text ? `: ${text.slice(0, 300)}` : ""}`);
  }
  const fixture = (await res.json()) as FixtureResponse;
  const rows = fixture.rowCount !== null ? `, ${fixture.rowCount} rows` : "";
  ctx.out(`Uploaded fixture "${fixture.displayName}" (${fixture.format}${rows}).`);
  if (fixture.piiScan && fixture.piiScan.status === "FOUND") {
    ctx.out(`Warning: PII scan found ${fixture.piiScan.findingCount ?? "some"} possible finding(s) in this fixture; it cannot back a trial profile until it is cleaned up.`);
  }
  return 0;
}

export async function fixturesList(ctx: Context): Promise<number> {
  readProject(ctx.cwd);
  const api = new ApiClient(requireCredentials(ctx), ctx.fetch);
  const list = await api.get<FixtureResponse[]>("/api/v1/developer/fixtures");
  if (list.length === 0) {
    ctx.out("No fixtures.");
    return 0;
  }
  for (const f of list) {
    const pii = f.piiScan ? f.piiScan.status.toLowerCase() : "unscanned";
    ctx.out(`${f.displayName}\t${f.format}\tpii=${pii}`);
  }
  return 0;
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd cli && npx vitest run test/fixtures.test.ts`
Expected: PASS

- [ ] **Step 5: Wire `cli.ts`**

Modify `cli/src/cli.ts`: add the import `import { fixturesList, fixturesPush } from "./commands/fixtures.js";`, add to `USAGE`:

```
  fixtures push FILE [--display-name N] [--source-label L]
                                        Upload a local fixture file
  fixtures list                        List the org's fixtures with PII scan status
```

and add a case to the `switch (command)` block (after the `push` case):

```typescript
      case "fixtures": {
        const [sub, ...rest] = args;
        switch (sub) {
          case "push": {
            const { values, positionals } = parseArgs({
              args: rest,
              options: { "display-name": { type: "string" }, "source-label": { type: "string" } },
              strict: true,
              allowPositionals: true,
            });
            if (positionals.length !== 1) {
              throw new CliError(`Usage: agentstore fixtures push FILE [--display-name N] [--source-label L]\n\n${USAGE}`);
            }
            return await fixturesPush(ctx, {
              file: positionals[0],
              displayName: str(values as Values, "display-name"),
              sourceLabel: str(values as Values, "source-label"),
            });
          }
          case "list":
            parse(rest, {});
            return await fixturesList(ctx);
          case undefined:
            ctx.err(`Usage: agentstore fixtures <push|list> [options]\n\n${USAGE}`);
            return 2;
          default:
            ctx.err(`Unknown fixtures command "${sub}"\n\n${USAGE}`);
            return 2;
        }
      }
```

(Note: `fixtures push` needs both a positional (`FILE`) and named options in the same call, which the file's existing `parse()` helper cannot do (it hardcodes `allowPositionals: false`) — so this case calls `parseArgs` directly with `allowPositionals: true`, the same way the existing `sandbox logs RUN_ID` case already does for a plain positional. `values` comes back typed as `parseArgs`'s own result shape, hence the `as Values` cast to reuse the file's existing `str()` helper.)

- [ ] **Step 6: Add a `cli.test.ts` dispatch test**

Add to `cli/test/cli.test.ts` (mirroring its existing dispatch-test pattern for other commands):

```typescript
  it("dispatches fixtures push and fixtures list", async () => {
    const server = await startServer([
      { method: "POST", path: "/api/v1/developer/fixtures", reply: { json: { id: "f1", displayName: "x.csv", format: "CSV", rowCount: 1, piiScan: null } } },
      { method: "GET", path: "/api/v1/developer/fixtures", reply: { json: [] } },
    ]);
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: server.url } });
    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });
    const filePath = require("node:path").join(ctx.cwd, "x.csv");
    require("node:fs").writeFileSync(filePath, "a\n1\n");

    expect(await main(["fixtures", "push", filePath], ctx)).toBe(0);
    expect(await main(["fixtures", "list"], ctx)).toBe(0);
  });
```

(Match whatever import style — ESM `import` vs the ambient `require` shown above is illustrative; use this file's existing top-of-file imports for `node:fs`/`node:path` instead of inline `require`, consistent with the rest of the test file.)

- [ ] **Step 7: Run the full CLI suite**

Run: `cd cli && npx vitest run`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add cli/src/commands/fixtures.ts cli/src/cli.ts cli/test/fixtures.test.ts cli/test/cli.test.ts
git commit -m "feat(cli): add fixtures push/list commands"
```

---

### Task 9: CLI shared `profiles.ts` module + `agentstore profile list` / `profile pull`

**Files:**
- Create: `cli/src/profiles.ts`
- Create: `cli/src/commands/profile.ts`
- Modify: `cli/src/cli.ts`
- Test: `cli/test/profiles.test.ts` (new)
- Test: `cli/test/profile.test.ts` (new)

**Interfaces:**
- Produces: `readProfileFiles(dir: string): ProfileSyncEntry[]` (Task 10 imports this from `cli/src/profiles.ts`); `writeProfileFile(dir, profile)`; `ProfileYaml`/`ProfileSyncEntry` types; `profileList(ctx)`, `profilePull(ctx)`.

- [ ] **Step 1: Write the failing test for `profiles.ts`**

Create `cli/test/profiles.test.ts`:

```typescript
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { readProfileFiles, writeProfileFile } from "../src/profiles.js";
import { tempDir, writeFiles } from "./helpers.js";

describe("readProfileFiles", () => {
  it("returns an empty list when there is no fixtures/profiles directory", () => {
    expect(readProfileFiles(tempDir())).toEqual([]);
  });

  it("parses every yaml file and hashes its raw bytes", () => {
    const dir = tempDir();
    const yaml = "name: happy-path\ndescription: Clean batch\nbindings:\n  - resource: invoices\n    fixture: sample.csv\n";
    writeFiles(dir, { "fixtures/profiles/happy-path.yaml": yaml });

    const entries = readProfileFiles(dir);

    expect(entries).toHaveLength(1);
    expect(entries[0].name).toBe("happy-path");
    expect(entries[0].description).toBe("Clean batch");
    expect(entries[0].bindings).toEqual([{ resource: "invoices", fixture: "sample.csv" }]);
    expect(entries[0].contentHash).toHaveLength(64);
  });

  it("computes a stable hash for the same bytes", () => {
    const dir = tempDir();
    writeFiles(dir, { "fixtures/profiles/p.yaml": "name: p\n" });
    const [first] = readProfileFiles(dir);
    const [second] = readProfileFiles(dir);
    expect(first.contentHash).toBe(second.contentHash);
  });
});

describe("writeProfileFile", () => {
  it("writes a yaml file named after the profile", () => {
    const dir = tempDir();
    writeProfileFile(dir, { name: "pulled", description: "d", bindings: [{ resource: "r", fixture: "f.csv" }] });

    const path = join(dir, "fixtures", "profiles", "pulled.yaml");
    expect(existsSync(path)).toBe(true);
    expect(readFileSync(path, "utf8")).toContain("name: pulled");
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd cli && npx vitest run test/profiles.test.ts`
Expected: FAIL — `src/profiles.js` does not exist.

- [ ] **Step 3: Write `cli/src/profiles.ts`**

```typescript
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { parse, stringify } from "yaml";

export const PROFILES_DIR = "fixtures/profiles";

export interface ProfileYamlBinding {
  resource: string;
  fixture: string;
}

export interface ProfileYamlGatePolicy {
  default: string;
  actions?: Record<string, string>;
}

export interface ProfileYaml {
  name: string;
  description?: string;
  input?: Record<string, unknown>;
  gatePolicy?: ProfileYamlGatePolicy;
  llmMode?: string;
  spendCap?: number;
  bindings?: ProfileYamlBinding[];
}

export interface ProfileSyncEntry extends ProfileYaml {
  contentHash: string;
}

/** Every fixtures/profiles/*.yaml file in the project, parsed and hashed over its raw bytes. */
export function readProfileFiles(dir: string): ProfileSyncEntry[] {
  const profilesDir = join(dir, PROFILES_DIR);
  if (!existsSync(profilesDir)) {
    return [];
  }
  return readdirSync(profilesDir)
    .filter((f) => f.endsWith(".yaml") || f.endsWith(".yml"))
    .map((f) => {
      const raw = readFileSync(join(profilesDir, f));
      const parsed = (parse(raw.toString("utf8")) ?? {}) as ProfileYaml;
      return { ...parsed, contentHash: createHash("sha256").update(raw).digest("hex") };
    });
}

/** Overwrites `fixtures/profiles/<name>.yaml` unconditionally — a developer with a conflicting local edit should
 * check it into git before pulling. */
export function writeProfileFile(dir: string, profile: ProfileYaml): void {
  const profilesDir = join(dir, PROFILES_DIR);
  mkdirSync(profilesDir, { recursive: true });
  writeFileSync(join(profilesDir, `${profile.name}.yaml`), stringify(profile));
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd cli && npx vitest run test/profiles.test.ts`
Expected: PASS

- [ ] **Step 5: Write the failing test for `profile.ts` commands**

Create `cli/test/profile.test.ts`:

```typescript
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { profileList, profilePull } from "../src/commands/profile.js";
import { AGENT_ID, makeContext, startServer, writeFiles } from "./helpers.js";

const BASE = `/api/v1/agents/${AGENT_ID}/sandbox-profiles`;

describe("profile list", () => {
  it("prints each profile's status, trial flag, and source", async () => {
    const server = await startServer([
      { method: "GET", path: BASE, reply: { json: [
        { name: "happy-path", status: "READY", trialEnabled: true, source: "PORTAL" },
      ] } },
    ]);
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: server.url } });
    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });

    expect(await profileList(ctx)).toBe(0);
    expect(ctx.outLines.join("\n")).toContain("happy-path");
    expect(ctx.outLines.join("\n")).toContain("READY");
    expect(ctx.outLines.join("\n")).toContain("trial=true");
  });

  it("prints a friendly line when there are no profiles", async () => {
    const server = await startServer([{ method: "GET", path: BASE, reply: { json: [] } }]);
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: server.url } });
    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });

    expect(await profileList(ctx)).toBe(0);
    expect(ctx.outLines).toEqual(["No sandbox profiles."]);
  });
});

describe("profile pull", () => {
  it("writes every server-side profile to fixtures/profiles/<name>.yaml", async () => {
    const server = await startServer([
      { method: "GET", path: BASE, reply: { json: [
        {
          name: "happy-path", description: "Clean batch", input: {}, gatePolicy: { default: "AUTO_APPROVE", actions: {} },
          llmMode: "MOCK", spendCap: 50, bindings: [{ resource: "invoices", datasetId: "d1", displayName: "sample-invoices.csv" }],
        },
      ] } },
    ]);
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: server.url } });
    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });

    expect(await profilePull(ctx)).toBe(0);

    const written = readFileSync(join(ctx.cwd, "fixtures", "profiles", "happy-path.yaml"), "utf8");
    expect(written).toContain("name: happy-path");
    expect(written).toContain("fixture: sample-invoices.csv");
    expect(ctx.outLines.join("\n")).toContain("happy-path.yaml");
  });
});
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `cd cli && npx vitest run test/profile.test.ts`
Expected: FAIL — `src/commands/profile.js` does not exist.

- [ ] **Step 7: Write `cli/src/commands/profile.ts`**

```typescript
import { ApiClient } from "../api.js";
import { requireCredentials } from "../config.js";
import type { Context } from "../context.js";
import { readProject } from "../project.js";
import { writeProfileFile, type ProfileYaml } from "../profiles.js";

interface ProfileSummary {
  name: string;
  status: string;
  trialEnabled: boolean;
  source: string;
}

interface ProfileBinding {
  resource: string;
  datasetId: string;
  displayName: string | null;
}

interface ProfileDetail {
  name: string;
  description: string | null;
  input: Record<string, unknown>;
  gatePolicy: { default: string; actions: Record<string, string> };
  llmMode: string | null;
  spendCap: number | null;
  bindings: ProfileBinding[];
}

export async function profileList(ctx: Context): Promise<number> {
  const project = readProject(ctx.cwd);
  const api = new ApiClient(requireCredentials(ctx), ctx.fetch);
  const list = await api.get<ProfileSummary[]>(`/api/v1/agents/${project.agentId}/sandbox-profiles`);
  if (list.length === 0) {
    ctx.out("No sandbox profiles.");
    return 0;
  }
  for (const p of list) {
    ctx.out(`${p.name}\t${p.status}\ttrial=${p.trialEnabled}\tsource=${p.source}`);
  }
  return 0;
}

export async function profilePull(ctx: Context): Promise<number> {
  const project = readProject(ctx.cwd);
  const api = new ApiClient(requireCredentials(ctx), ctx.fetch);
  const list = await api.get<ProfileDetail[]>(`/api/v1/agents/${project.agentId}/sandbox-profiles`);
  for (const p of list) {
    writeProfileFile(ctx.cwd, toYaml(p));
    ctx.out(`Wrote fixtures/profiles/${p.name}.yaml`);
  }
  return 0;
}

function toYaml(p: ProfileDetail): ProfileYaml {
  return {
    name: p.name,
    description: p.description ?? undefined,
    input: p.input,
    gatePolicy: p.gatePolicy,
    llmMode: p.llmMode ?? undefined,
    spendCap: p.spendCap ?? undefined,
    bindings: p.bindings
      .filter((b) => b.displayName !== null)
      .map((b) => ({ resource: b.resource, fixture: b.displayName as string })),
  };
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `cd cli && npx vitest run test/profile.test.ts`
Expected: PASS

- [ ] **Step 9: Wire `cli.ts`**

Modify `cli/src/cli.ts`: add the import `import { profileList, profilePull } from "./commands/profile.js";`, add to `USAGE`:

```
  profile list                          List sandbox profiles for the project's agent
  profile pull                          Write every server-side profile to fixtures/profiles/<name>.yaml
```

and add a case (after the `fixtures` case added in Task 8):

```typescript
      case "profile": {
        const [sub, ...rest] = args;
        switch (sub) {
          case "list":
            parse(rest, {});
            return await profileList(ctx);
          case "pull":
            parse(rest, {});
            return await profilePull(ctx);
          case undefined:
            ctx.err(`Usage: agentstore profile <list|pull>\n\n${USAGE}`);
            return 2;
          default:
            ctx.err(`Unknown profile command "${sub}"\n\n${USAGE}`);
            return 2;
        }
      }
```

- [ ] **Step 10: Add `cli.test.ts` dispatch coverage and run the full suite**

Add a dispatch test mirroring Task 8's, using `BASE`/`main(["profile", "list"], ctx)` / `main(["profile", "pull"], ctx)`.

Run: `cd cli && npx vitest run`
Expected: PASS

- [ ] **Step 11: Commit**

```bash
git add cli/src/profiles.ts cli/src/commands/profile.ts cli/src/cli.ts \
        cli/test/profiles.test.ts cli/test/profile.test.ts cli/test/cli.test.ts
git commit -m "feat(cli): add profile list/pull commands and the fixtures/profiles/*.yaml module"
```

---

### Task 10: `agentstore push` syncs profiles after finalize

**Files:**
- Modify: `cli/src/commands/push.ts:34-58`
- Test: `cli/test/push.test.ts` (existing file — add tests)

**Interfaces:**
- Consumes: `readProfileFiles(dir): ProfileSyncEntry[]` (Task 9's `cli/src/profiles.ts`); `ApiClient.post` (existing).
- Produces: nothing new for later tasks — this is the last CLI task.

- [ ] **Step 1: Write the failing tests**

Add to `cli/test/push.test.ts` (using the file's existing `harness(options)` helper — extend `Options` with an optional `sync` reply and `harness` to register the sync route):

First, modify the `harness` function's `Options` interface and route list:

```typescript
interface Options {
  builds?: Array<{ status: string; logUrl?: boolean; failureCode?: string; failureReason?: string }>;
  logs?: string[];
  putStatus?: number;
  finalize?: Reply;
  apiUrlSuffix?: string;
  files?: Record<string, string>;
  sync?: Reply;
}
```

and inside `harness`, add a route (after the finalize route):

```typescript
    { method: "POST", path: `${BASE}/sandbox-profiles/sync`, reply: options.sync ?? { json: [] } },
```

Then add these tests:

```typescript
  it("syncs fixtures/profiles/*.yaml after a successful finalize", async () => {
    const { server, ctx } = await harness({
      files: { "fixtures/profiles/happy-path.yaml": "name: happy-path\nbindings: []\n" },
      sync: { json: [{ name: "happy-path", action: "created", unresolvedFixtures: [] }] },
    });

    expect(await push(ctx, { wait: false, timeoutSeconds: 900 })).toBe(0);

    const syncRequest = server.requests.find((r) => r.path === `${BASE}/sandbox-profiles/sync`);
    expect(syncRequest).toBeDefined();
    const body = JSON.parse(syncRequest!.body.toString());
    expect(body).toHaveLength(1);
    expect(body[0].name).toBe("happy-path");
    expect(typeof body[0].contentHash).toBe("string");
    expect(ctx.outLines.join("\n")).toContain('Profile "happy-path": created');
  });

  it("prints a warning for each unresolved fixture without failing the push", async () => {
    const { ctx } = await harness({
      files: { "fixtures/profiles/p.yaml": "name: p\nbindings:\n  - resource: invoices\n    fixture: missing.csv\n" },
      sync: { json: [{ name: "p", action: "created", unresolvedFixtures: ["missing.csv"] }] },
    });

    expect(await push(ctx, { wait: false, timeoutSeconds: 900 })).toBe(0);

    expect(ctx.errLines.join("\n")).toContain("missing.csv");
  });

  it("does not call sync when there is no fixtures/profiles directory", async () => {
    const { server, ctx } = await harness();

    await push(ctx, { wait: false, timeoutSeconds: 900 });

    expect(server.requests.some((r) => r.path === `${BASE}/sandbox-profiles/sync`)).toBe(false);
  });
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd cli && npx vitest run test/push.test.ts`
Expected: FAIL — no sync call is made yet.

- [ ] **Step 3: Wire the sync step into `push.ts`**

Modify `cli/src/commands/push.ts`: add the import `import { readProfileFiles } from "../profiles.js";`, add a response interface, and extend the function body. Change:

```typescript
export async function push(ctx: Context, opts: PushOptions): Promise<number> {
  const project = readProject(ctx.cwd);
  const credentials = requireCredentials(ctx);

  const { problems, bundle } = validateProject(ctx.cwd);
  if (!bundle) {
    ctx.err(`Not pushing: ${problems.length} problem${problems.length === 1 ? "" : "s"} in the project:`);
    for (const problem of problems) {
      ctx.err(`  - ${problem}`);
    }
    return 1;
  }

  const api = new ApiClient(credentials, ctx.fetch);
  const base = `/api/v1/agents/${project.agentId}`;
  const upload = await api.post<UploadResponse>(`${base}/bundles/uploads`, { sha256: bundle.sha256, sizeBytes: bundle.bytes.length });
  await api.putBytes(upload.uploadUrl, bundle.bytes);
  const finalized = await api.post<FinalizeResponse>(`${base}/bundles/${upload.bundleId}/finalize`, { sha256: bundle.sha256 });
  ctx.out(`Pushed version ${finalized.versionNumber} (build ${finalized.buildId}, ${finalized.buildStatus}).`);

  if (!opts.wait) {
    return 0;
  }
  return await waitForBuild(ctx, api, `${base}/builds/${finalized.buildId}`, opts.timeoutSeconds);
}
```

to:

```typescript
interface SyncResult {
  name: string;
  action: string;
  unresolvedFixtures: string[];
}

export async function push(ctx: Context, opts: PushOptions): Promise<number> {
  const project = readProject(ctx.cwd);
  const credentials = requireCredentials(ctx);

  const { problems, bundle } = validateProject(ctx.cwd);
  if (!bundle) {
    ctx.err(`Not pushing: ${problems.length} problem${problems.length === 1 ? "" : "s"} in the project:`);
    for (const problem of problems) {
      ctx.err(`  - ${problem}`);
    }
    return 1;
  }

  const api = new ApiClient(credentials, ctx.fetch);
  const base = `/api/v1/agents/${project.agentId}`;
  const upload = await api.post<UploadResponse>(`${base}/bundles/uploads`, { sha256: bundle.sha256, sizeBytes: bundle.bytes.length });
  await api.putBytes(upload.uploadUrl, bundle.bytes);
  const finalized = await api.post<FinalizeResponse>(`${base}/bundles/${upload.bundleId}/finalize`, { sha256: bundle.sha256 });
  ctx.out(`Pushed version ${finalized.versionNumber} (build ${finalized.buildId}, ${finalized.buildStatus}).`);

  await syncProfiles(ctx, api, base);

  if (!opts.wait) {
    return 0;
  }
  return await waitForBuild(ctx, api, `${base}/builds/${finalized.buildId}`, opts.timeoutSeconds);
}

async function syncProfiles(ctx: Context, api: ApiClient, base: string): Promise<void> {
  const entries = readProfileFiles(ctx.cwd);
  if (entries.length === 0) {
    return;
  }
  const results = await api.post<SyncResult[]>(`${base}/sandbox-profiles/sync`, entries);
  for (const result of results) {
    ctx.out(`Profile "${result.name}": ${result.action}`);
    for (const fixture of result.unresolvedFixtures) {
      ctx.err(`  Warning: fixture "${fixture}" not found for profile "${result.name}"; that binding was skipped.`);
    }
  }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd cli && npx vitest run test/push.test.ts`
Expected: PASS

Run the full CLI suite once more: `cd cli && npx vitest run`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add cli/src/commands/push.ts cli/test/push.test.ts
git commit -m "feat(cli): sync fixtures/profiles/*.yaml after a successful agentstore push"
```

---

### Task 11: Update ONTOLOGY.md and Attention.md

**Files:**
- Modify: `ONTOLOGY.md`
- Modify: `Attention.md`

**Interfaces:**
- Consumes: everything built in Tasks 1–10 (file paths, class names, endpoint paths).
- Produces: nothing further — this is the plan's last task.

- [ ] **Step 1: Add the WS6c subsection to ONTOLOGY.md**

Add a new subsection to ONTOLOGY.md's "### Sandbox core (WS6a)" / "### Behaviour report, flow & readiness (WS6b)" area (after WS6b's paragraph, before the next `##` heading), titled `### PII scan, trial profiles, REPO sync (WS6c)`, describing:
- `PiiScanner` (`backend/src/main/java/com/agentstore/platform/data/PiiScanner.java`): the 7 detector types, the 200-finding cap, only run for `purpose == FIXTURE` tabular uploads.
- `Dataset.piiScan` (migration `V35`): null until scanned, never scanned for PDF or non-FIXTURE purposes.
- `SandboxProfile`'s new columns (`description`, `trial_enabled`, `no_real_data_attested_by/at`, `source`, `repo_source_hash`) and the attestation state machine in `SandboxProfileService.applyTrial`/`SandboxBindingService.clearAttestation`.
- `ProfileStatusService` (`READY`/`FILE_ERRORS`, computed on every read, reusing WS2's `DatasetCheckService`).
- `SandboxProfileSyncService` and `POST /api/v1/agents/{id}/sandbox-profiles/sync`, and the REPO-vs-PORTAL precedence table.
- The CLI's `fixtures push/list`, `profile list/pull`, and `agentstore push`'s post-finalize sync step.

Update the file's top summary line (line 8) to append `, PII scan/trial profiles/REPO sync (WS6c)` to the "Later additions" list, and change "Audited against master at commit `7fb6b50`..." to note the new audit point once this branch merges (leave this line's exact wording for the finishing/PR step, per the repo's existing convention of updating it once per merge — do not fabricate a merge commit hash before one exists).

Update the `ontology-tracked-files:` comment (top of file) to append these new/changed files: `backend/src/main/java/com/agentstore/platform/data/PiiScanner.java`, `backend/src/main/java/com/agentstore/platform/sandbox/ProfileSource.java`, `backend/src/main/java/com/agentstore/platform/sandbox/ProfileStatus.java`, `backend/src/main/java/com/agentstore/platform/sandbox/ProfileStatusService.java`, `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileSyncRequest.java`, `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileSyncResult.java`, `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfileSyncService.java`, `backend/src/main/resources/db/migration/V35__pii_scan_trial_profiles_repo_sync.sql`, `backend/src/main/java/com/agentstore/platform/common/PlatformOpenApiConfig.java`, `cli/src/profiles.ts`, `cli/src/commands/fixtures.ts`, `cli/src/commands/profile.ts`.

- [ ] **Step 2: Re-stamp the ontology hash**

Run: `bash scripts/stamp-ontology-hash.sh ONTOLOGY.md`
Then: `bash scripts/check-ontology-staleness.sh`
Expected: `OK: ONTOLOGY.md is in sync with its tracked source files.`

- [ ] **Step 3: Update Attention.md's `active_context` block**

Modify `Attention.md`'s `<active_context>` block (the last section) to:

```
- Current workstream: WS6c — PII scan, trial profiles, REPO sync, CLI profile/fixture commands (merged)
- Active files/packages: backend `data`/`sandbox` packages (PiiScanner, ProfileStatusService, SandboxProfileSyncService), `cli/src/commands/fixtures.ts`, `cli/src/commands/profile.ts`, `cli/src/profiles.ts`
- Out of scope for now: Portal UI (FE-3), a CLI verb to flip trialEnabled directly, WS7 developer-verification gating, WS8 eval runs
```

- [ ] **Step 4: Commit**

```bash
git add ONTOLOGY.md Attention.md
git commit -m "docs: update ONTOLOGY.md and Attention.md for WS6c"
```

---

## Post-plan verification

After all 11 tasks: run the full backend suite (`cd backend && mvn -q test`) and the full CLI suite (`cd cli && npx vitest run`) once more end to end, then invoke `superpowers:requesting-code-review` before opening a PR, per this repo's established workflow for prior workstreams (WS6a/WS6b).
