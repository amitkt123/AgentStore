# WS7a — Developer Verification Submission Flow — Implementation Plan


**Goal:** Let a developer org fill out a resumable, step-by-step identity-verification form (About → Tax IDs → Phone → Agreement), upload a PAN document, and submit for review, landing the row in `PENDING`. No checks run yet (that's 7b).

**Architecture:** One new package `com.agentstore.platform.verification` holding a single JPA entity (`developer_verification`, one row per org, lazily created on first touch), a service that owns per-step validation/encryption/lock rules, and one REST controller (`GET`/`PATCH /api/v1/developer/verification`, `POST .../pan-document`, `POST .../submit`). Follows the exact shape of `com.agentstore.platform.llm` (`LlmKeyController`/`ByokKeyService`/`AesGcmCipher` for encrypted fields) and `com.agentstore.platform.sandbox` (`SandboxProfileController`/`Caller.current()` for the org-scoped JWT pattern, `SandboxApiException`/`SandboxApiErrors` for the `{code, message, ...extra}` error shape).

**Tech Stack:** Spring Boot (JPA/Hibernate, Spring Security `@PreAuthorize`, Spring MVC multipart), Flyway migration, `AesGcmCipher` (AES-GCM, existing bean), `ObjectStorage` (S3-compatible, existing bean), JUnit 5 + Testcontainers (`AbstractStorageIntegrationTest`), `TestRestTemplate`.

**Spec:** `docs/superpowers/specs/2026-09-27-ws7a-developer-verification-design.md`

## Global Constraints

- Backend and contract only — no frontend work (out of scope per spec §1; FE-3/FE-4 own the UI).
- No bank/payout columns anywhere in the schema (spec §1, [I1]) — do not add them "for later."
- No OTP delivery, no vendor KYC call — format-only validation for PAN/GSTIN/phone, exact same regexes as `PiiScanner` (`^[A-Z]{5}[0-9]{4}[A-Z]$` for PAN, `^[0-9]{2}[A-Z0-9]{13}$` for GSTIN, `^[6-9]\d{9}$` or `^\+\d{10,15}$` for phone) (spec §3, [I2]).
- Migration file is `V36__developer_verification.sql` (last existing migration is `V35`); the table is exactly as given in spec §2 — do not add `verification_check`, `phone_verified_at`, or any bank/payout column.
- No admin/decision code, no `org.developer_verified_at`/`verification_status` writes — 7b's job entirely (spec §1). This plan's code never touches those columns.
- API response for `GET`/every mutating endpoint must never include raw `pan`, `gstin`, or `phone` — only `*Masked` fields (spec §3).
- Error codes and HTTP statuses are exactly the table in spec §4 (`VERIFICATION_INCOMPLETE` 400, `INVALID_FIELD` 400, `VERIFICATION_LOCKED` 409, `DOCUMENT_TOO_LARGE` 413, `UNSUPPORTED_DOCUMENT_TYPE` 415).
- `ONTOLOGY.md` must be updated as part of this workstream, not deferred (standing project instruction).

## Review Focus

- A `PATCH` that omits a field entirely (not blank, just absent) must still save the fields that *are* present and must not throw `INVALID_FIELD` for the missing one — only a field that is present-but-blank/malformed is rejected. Partial saves are the whole point of "resumable."
- A multipart upload with no `Content-Type` at all (some HTTP clients omit it) must be rejected as `UNSUPPORTED_DOCUMENT_TYPE`, not NPE through to a 500.
- `country` is `CHAR(2)` in Postgres — a client sending a 3+ character country string must get `400 INVALID_FIELD` before it ever reaches the database (a raw DB error would otherwise surface as an opaque 500).
- Resubmission count must increment exactly once per successful `submit` call from `FIX_REQUESTED`, not once per `PATCH` while in that status — the fixture sets `FIX_REQUESTED` directly, so this needs its own submit-focused test, not an incidental one.
- `entityType` (and `step`, wire-deserialized straight into an enum) sent with unrecognized or wrong-case text must be a clean `400`, not a Jackson deserialization 500 — `step` is bound by Spring's enum converter (400 automatically) but `entityType` arrives as a `String` inside a `Map` and must be hand-validated.

---

## Task 1: Schema, entity, and the auto-creating `GET` endpoint

**Files:**
- Create: `backend/src/main/resources/db/migration/V36__developer_verification.sql`
- Create: `backend/src/main/java/com/agentstore/platform/verification/VerificationStatus.java`
- Create: `backend/src/main/java/com/agentstore/platform/verification/VerificationStep.java`
- Create: `backend/src/main/java/com/agentstore/platform/verification/VerificationEntityType.java`
- Create: `backend/src/main/java/com/agentstore/platform/verification/DeveloperVerification.java`
- Create: `backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationRepository.java`
- Create: `backend/src/main/java/com/agentstore/platform/verification/VerificationApiException.java`
- Create: `backend/src/main/java/com/agentstore/platform/verification/VerificationApiErrors.java`
- Create: `backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationView.java`
- Create: `backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationService.java`
- Create: `backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationController.java`
- Create: `backend/src/test/java/com/agentstore/platform/verification/DeveloperVerificationFixtures.java`
- Test: `backend/src/test/java/com/agentstore/platform/verification/DeveloperVerificationApiTest.java`

**Interfaces:**
- Produces: `DeveloperVerificationService.getOrCreate(UUID orgId): DeveloperVerification` — used by every later task's service method as the first line (lazy row creation, no separate "start" endpoint).
- Produces: `DeveloperVerificationView.from(DeveloperVerification): DeveloperVerificationView` — the only response shape every controller method returns.
- Produces: `VerificationApiException` static factories `locked()`, `invalidField(String)`, `incomplete(List<String>)`, `documentTooLarge()`, `unsupportedDocumentType()` — later tasks throw these, never construct the exception directly.
- Produces: `DeveloperVerificationFixtures.developer(): Org` and `.bearer(Org): HttpHeaders` — later tasks' tests call these for every request.

- [ ] **Step 1: Write the migration**

```sql
CREATE TABLE developer_verification (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL REFERENCES org(id) UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',        -- DRAFT, PENDING, VERIFIED, FIX_REQUESTED, REJECTED
    current_step VARCHAR(20) NOT NULL DEFAULT 'ABOUT',  -- ABOUT, TAX_IDS, PHONE, AGREEMENT
    entity_type VARCHAR(20),                            -- INDIVIDUAL, LLP, PRIVATE_LIMITED, PARTNERSHIP, OTHER
    legal_name VARCHAR(255),
    display_name VARCHAR(255),
    country CHAR(2),
    city VARCHAR(100),
    pan_encrypted BYTEA, pan_nonce BYTEA, pan_masked VARCHAR(20),
    gstin_encrypted BYTEA, gstin_nonce BYTEA, gstin_masked VARCHAR(20),
    pan_document_key VARCHAR(512),
    phone_encrypted BYTEA, phone_nonce BYTEA, phone_masked VARCHAR(20),
    agreement_version VARCHAR(20),
    agreement_accepted_at TIMESTAMPTZ,
    resubmission_count INT NOT NULL DEFAULT 0,
    reviewer_user_id UUID REFERENCES app_user(id),
    decision_note TEXT,
    submitted_at TIMESTAMPTZ,
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Save this as `backend/src/main/resources/db/migration/V36__developer_verification.sql`. Flyway runs it automatically on the next app/test start — no separate step needed.

- [ ] **Step 2: Write the enums**

`backend/src/main/java/com/agentstore/platform/verification/VerificationStatus.java`:
```java
package com.agentstore.platform.verification;

public enum VerificationStatus {
    DRAFT, PENDING, VERIFIED, FIX_REQUESTED, REJECTED
}
```

`backend/src/main/java/com/agentstore/platform/verification/VerificationStep.java`:
```java
package com.agentstore.platform.verification;

/** Fixed order: ABOUT then TAX_IDS then PHONE then AGREEMENT. {@code next()} loops on itself at the last step. */
public enum VerificationStep {
    ABOUT, TAX_IDS, PHONE, AGREEMENT;

    public VerificationStep next() {
        return switch (this) {
            case ABOUT -> TAX_IDS;
            case TAX_IDS -> PHONE;
            case PHONE -> AGREEMENT;
            case AGREEMENT -> AGREEMENT;
        };
    }
}
```

`backend/src/main/java/com/agentstore/platform/verification/VerificationEntityType.java`:
```java
package com.agentstore.platform.verification;

public enum VerificationEntityType {
    INDIVIDUAL, LLP, PRIVATE_LIMITED, PARTNERSHIP, OTHER
}
```

- [ ] **Step 3: Write the entity**

`backend/src/main/java/com/agentstore/platform/verification/DeveloperVerification.java`:
```java
package com.agentstore.platform.verification;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One org's identity-verification submission: at most one row per org, created lazily on first touch. */
@Entity
@Table(name = "developer_verification")
@Getter
@Setter
@NoArgsConstructor
public class DeveloperVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID orgId;

    @Enumerated(EnumType.STRING)
    private VerificationStatus status = VerificationStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    private VerificationStep currentStep = VerificationStep.ABOUT;

    @Enumerated(EnumType.STRING)
    private VerificationEntityType entityType;

    private String legalName;
    private String displayName;
    private String country;
    private String city;

    private byte[] panEncrypted;
    private byte[] panNonce;
    private String panMasked;

    private byte[] gstinEncrypted;
    private byte[] gstinNonce;
    private String gstinMasked;

    private String panDocumentKey;

    private byte[] phoneEncrypted;
    private byte[] phoneNonce;
    private String phoneMasked;

    private String agreementVersion;
    private Instant agreementAcceptedAt;

    private int resubmissionCount;

    private UUID reviewerUserId;
    private String decisionNote;

    private Instant submittedAt;
    private Instant decidedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public DeveloperVerification(UUID orgId) {
        this.orgId = orgId;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
```

`backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationRepository.java`:
```java
package com.agentstore.platform.verification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeveloperVerificationRepository extends JpaRepository<DeveloperVerification, UUID> {

    Optional<DeveloperVerification> findByOrgId(UUID orgId);
}
```

- [ ] **Step 4: Write the exception and its `@RestControllerAdvice`**

`backend/src/main/java/com/agentstore/platform/verification/VerificationApiException.java`:
```java
package com.agentstore.platform.verification;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/** A developer-verification failure with a stable code; {@code extra} fields are added to the response body. */
public class VerificationApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> extra;

    public VerificationApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public VerificationApiException(HttpStatus status, String code, String message, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.code = code;
        this.extra = extra;
    }

    public static VerificationApiException locked() {
        return new VerificationApiException(HttpStatus.CONFLICT, "VERIFICATION_LOCKED",
                "This verification cannot be edited in its current status");
    }

    public static VerificationApiException invalidField(String message) {
        return new VerificationApiException(HttpStatus.BAD_REQUEST, "INVALID_FIELD", message);
    }

    public static VerificationApiException incomplete(List<String> missingSteps) {
        return new VerificationApiException(HttpStatus.BAD_REQUEST, "VERIFICATION_INCOMPLETE",
                "Complete all steps before submitting: " + missingSteps, Map.of("missingSteps", missingSteps));
    }

    public static VerificationApiException documentTooLarge() {
        return new VerificationApiException(HttpStatus.PAYLOAD_TOO_LARGE, "DOCUMENT_TOO_LARGE",
                "PAN document must be at most 5 MB");
    }

    public static VerificationApiException unsupportedDocumentType() {
        return new VerificationApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_DOCUMENT_TYPE",
                "PAN document must be image/jpeg, image/png, or application/pdf");
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

`backend/src/main/java/com/agentstore/platform/verification/VerificationApiErrors.java`:
```java
package com.agentstore.platform.verification;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class VerificationApiErrors {

    @ExceptionHandler(VerificationApiException.class)
    ResponseEntity<Map<String, Object>> api(VerificationApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("message", e.getMessage());
        body.putAll(e.extra());
        return ResponseEntity.status(e.status()).body(body);
    }
}
```

- [ ] **Step 5: Write the response view, service, and controller**

`backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationView.java`:
```java
package com.agentstore.platform.verification;

import java.time.Instant;

/** Never carries raw {@code pan}/{@code gstin}/{@code phone} — only the masked forms. */
public record DeveloperVerificationView(
        VerificationStatus status,
        VerificationStep currentStep,
        VerificationEntityType entityType,
        String legalName,
        String displayName,
        String country,
        String city,
        String panMasked,
        String gstinMasked,
        boolean panDocumentUploaded,
        String phoneMasked,
        String agreementVersion,
        Instant agreementAcceptedAt,
        int resubmissionCount,
        Instant submittedAt) {

    static DeveloperVerificationView from(DeveloperVerification v) {
        return new DeveloperVerificationView(v.getStatus(), v.getCurrentStep(), v.getEntityType(), v.getLegalName(),
                v.getDisplayName(), v.getCountry(), v.getCity(), v.getPanMasked(), v.getGstinMasked(),
                v.getPanDocumentKey() != null, v.getPhoneMasked(), v.getAgreementVersion(), v.getAgreementAcceptedAt(),
                v.getResubmissionCount(), v.getSubmittedAt());
    }
}
```

`backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationService.java` (only `getOrCreate` for now — later tasks add methods to this same file):
```java
package com.agentstore.platform.verification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class DeveloperVerificationService {

    private final DeveloperVerificationRepository repo;

    public DeveloperVerificationService(DeveloperVerificationRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public DeveloperVerification getOrCreate(UUID orgId) {
        return repo.findByOrgId(orgId).orElseGet(() -> repo.save(new DeveloperVerification(orgId)));
    }
}
```

`backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationController.java` (only `GET` for now):
```java
package com.agentstore.platform.verification;

import com.agentstore.platform.data.Caller;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/developer/verification")
@PreAuthorize("hasRole('DEVELOPER')")
public class DeveloperVerificationController {

    private final DeveloperVerificationService service;

    public DeveloperVerificationController(DeveloperVerificationService service) {
        this.service = service;
    }

    @GetMapping
    public DeveloperVerificationView get() {
        return DeveloperVerificationView.from(service.getOrCreate(Caller.current().orgId()));
    }
}
```

- [ ] **Step 6: Write the fixtures component**

`backend/src/test/java/com/agentstore/platform/verification/DeveloperVerificationFixtures.java`:
```java
package com.agentstore.platform.verification;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.UserRole;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Shared setup for developer-verification tests: developer orgs, developer JWTs, raw-row/status inspection. */
@Component
public class DeveloperVerificationFixtures {

    private final OrgRepository orgs;
    private final JwtService jwt;
    private final JdbcTemplate jdbc;

    public DeveloperVerificationFixtures(OrgRepository orgs, JwtService jwt, JdbcTemplate jdbc) {
        this.orgs = orgs;
        this.jwt = jwt;
        this.jdbc = jdbc;
    }

    public Org developer() {
        return orgs.save(new Org(null, "DevCo-" + UUID.randomUUID(), OrgType.DEVELOPER));
    }

    /** A fresh bearer token (new random user id) for the org — call again to simulate "log out, log back in". */
    public HttpHeaders bearer(Org org) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt.issue(UUID.randomUUID(), org.getId(), UserRole.DEVELOPER));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Sets status directly via SQL — simulates a state 7b's (not-yet-built) admin decision would set. */
    public void setStatus(Org org, String status) {
        jdbc.update("UPDATE developer_verification SET status = ? WHERE org_id = ?", status, org.getId());
    }

    public record RawRow(byte[] panEncrypted, byte[] panNonce, byte[] gstinEncrypted, byte[] phoneEncrypted) {}

    public RawRow rawRow(Org org) {
        return jdbc.queryForObject(
                "SELECT pan_encrypted, pan_nonce, gstin_encrypted, phone_encrypted FROM developer_verification WHERE org_id = ?",
                (rs, i) -> new RawRow(rs.getBytes(1), rs.getBytes(2), rs.getBytes(3), rs.getBytes(4)), org.getId());
    }
}
```

- [ ] **Step 7: Write the failing test**

`backend/src/test/java/com/agentstore/platform/verification/DeveloperVerificationApiTest.java`:
```java
package com.agentstore.platform.verification;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.identity.Org;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeveloperVerificationApiTest extends AbstractStorageIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DeveloperVerificationFixtures fx;
    @Autowired DeveloperVerificationRepository repo;

    String base() {
        return "http://localhost:" + port + "/api/v1/developer/verification";
    }

    ResponseEntity<Map> call(HttpMethod method, String url, Object body, Org org) {
        return rest.exchange(url, method, new HttpEntity<>(body, fx.bearer(org)), Map.class);
    }

    @Test
    void firstGetAutoCreatesADraftRowAndASecondGetReturnsTheSameRow() {
        var dev = fx.developer();

        var first = call(HttpMethod.GET, base(), null, dev);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("status")).isEqualTo("DRAFT");
        assertThat(first.getBody().get("currentStep")).isEqualTo("ABOUT");
        assertThat(repo.findByOrgId(dev.getId())).isPresent();

        var second = call(HttpMethod.GET, base(), null, dev);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void anotherOrgsJwtNeverSeesThisOrgsRow() {
        var dev = fx.developer();
        var other = fx.developer();
        call(HttpMethod.GET, base(), null, dev);

        call(HttpMethod.GET, base(), null, other);

        assertThat(repo.count()).isEqualTo(2);
        assertThat(repo.findByOrgId(other.getId()).orElseThrow().getId())
                .isNotEqualTo(repo.findByOrgId(dev.getId()).orElseThrow().getId());
    }
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=DeveloperVerificationApiTest`
Expected: both tests PASS. (There's no "fails first" step here since Task 1 writes the schema and code together — there's no prior state for the test to fail against other than compile errors, which is the actual first checkpoint: if it doesn't compile, fix imports/signatures until it does, then it should pass immediately.)

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/resources/db/migration/V36__developer_verification.sql \
        backend/src/main/java/com/agentstore/platform/verification \
        backend/src/test/java/com/agentstore/platform/verification
git commit -m "feat(verification): add developer_verification schema and auto-creating GET endpoint"
```

---

## Task 2: `PATCH` — per-step validation, encryption, and lock gating

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationService.java`
- Modify: `backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationController.java`
- Test: `backend/src/test/java/com/agentstore/platform/verification/DeveloperVerificationApiTest.java`

**Interfaces:**
- Consumes: `DeveloperVerificationService.getOrCreate(UUID orgId)` (Task 1).
- Consumes: `VerificationApiException.locked()`, `.invalidField(String)` (Task 1).
- Produces: `DeveloperVerificationService.patchStep(UUID orgId, VerificationStep step, Map<String, String> fields): DeveloperVerification` — Tasks 3 and 4's tests reuse this via the controller to set up state (e.g. completing all steps before testing submit).
- Produces: `DeveloperVerificationController.StepUpdateRequest(VerificationStep step, Map<String, String> fields)` record — the exact request body shape the frontend (FE-3, later) will send.

- [ ] **Step 1: Write the failing tests**

Add to `DeveloperVerificationApiTest`:
```java
    ResponseEntity<Map> patch(Org org, String step, Map<String, String> fields) {
        return call(HttpMethod.PATCH, base(), Map.of("step", step, "fields", fields), org);
    }

    @Test
    void stepByStepSaveAndResumeAcrossAFreshJwt() {
        var dev = fx.developer();

        var afterAbout = patch(dev, "ABOUT", Map.of("legalName", "Acme LLP", "displayName", "Acme",
                "country", "IN", "city", "Pune", "entityType", "LLP"));
        assertThat(afterAbout.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(afterAbout.getBody().get("currentStep")).isEqualTo("TAX_IDS");

        // fresh JWT ("logout"): a new bearer token for the same org must resume the same row
        var afterTax = patch(dev, "TAX_IDS", Map.of("pan", "ABCDE1234F"));
        assertThat(afterTax.getBody().get("panMasked")).isEqualTo("234F");
        assertThat(afterTax.getBody().get("currentStep")).isEqualTo("PHONE");

        var afterPhone = patch(dev, "PHONE", Map.of("phone", "9876543210"));
        assertThat(afterPhone.getBody().get("phoneMasked")).isEqualTo("3210");
        assertThat(afterPhone.getBody().get("currentStep")).isEqualTo("AGREEMENT");

        var afterAgreement = patch(dev, "AGREEMENT", Map.of("agreementVersion", "2.1"));
        assertThat(afterAgreement.getBody().get("agreementVersion")).isEqualTo("2.1");
        assertThat(afterAgreement.getBody().get("agreementAcceptedAt")).isNotNull();
        assertThat(afterAgreement.getBody().get("currentStep")).isEqualTo("AGREEMENT");
    }

    @Test
    void partialAboutSavesWithoutAdvancingAndWithoutRejectingOmittedFields() {
        var dev = fx.developer();

        var partial = patch(dev, "ABOUT", Map.of("legalName", "Acme LLP"));

        assertThat(partial.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(partial.getBody().get("legalName")).isEqualTo("Acme LLP");
        assertThat(partial.getBody().get("currentStep")).isEqualTo("ABOUT");
    }

    @Test
    void patchingAnEarlierStepThanCurrentStillSavesButLeavesCurrentStepUnchanged() {
        var dev = fx.developer();
        patch(dev, "ABOUT", Map.of("legalName", "Acme LLP", "displayName", "Acme", "country", "IN", "city", "Pune",
                "entityType", "LLP"));
        patch(dev, "TAX_IDS", Map.of("pan", "ABCDE1234F"));

        var backToAbout = patch(dev, "ABOUT", Map.of("city", "Mumbai"));

        assertThat(backToAbout.getBody().get("city")).isEqualTo("Mumbai");
        assertThat(backToAbout.getBody().get("currentStep")).isEqualTo("PHONE");
    }

    @Test
    void panGstinAndPhoneShapeRejectionDoesNotSave() {
        var dev = fx.developer();
        call(HttpMethod.GET, base(), null, dev); // commit a DRAFT row first: a failed PATCH rolls its own transaction back

        var badPan = patch(dev, "TAX_IDS", Map.of("pan", "not-a-pan"));
        assertThat(badPan.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badPan.getBody().get("code")).isEqualTo("INVALID_FIELD");

        var badGstin = patch(dev, "TAX_IDS", Map.of("pan", "ABCDE1234F", "gstin", "not-a-gstin"));
        assertThat(badGstin.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var badPhone = patch(dev, "PHONE", Map.of("phone", "12345"));
        assertThat(badPhone.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(repo.findByOrgId(dev.getId()).orElseThrow().getPanEncrypted()).isNull();
    }

    @Test
    void countryMustBeExactlyTwoLettersElseInvalidFieldNotADatabaseError() {
        var dev = fx.developer();

        var response = patch(dev, "ABOUT", Map.of("country", "IND"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_FIELD");
    }

    @Test
    void unknownEntityTypeIsACleanBadRequestNotA500() {
        var dev = fx.developer();

        var response = patch(dev, "ABOUT", Map.of("entityType", "NOT_A_TYPE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_FIELD");
    }

    @Test
    void wrongAgreementVersionIsInvalidField() {
        var dev = fx.developer();

        var response = patch(dev, "AGREEMENT", Map.of("agreementVersion", "1.0"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_FIELD");
    }

    @Test
    void encryptionRoundTripNeverExposesRawValuesButDbHasCiphertextAndNonce() {
        var dev = fx.developer();

        var response = patch(dev, "TAX_IDS", Map.of("pan", "ABCDE1234F", "gstin", "29AAAPL1234C1ZS"));

        assertThat(response.getBody().toString()).doesNotContain("ABCDE1234F").doesNotContain("29AAAPL1234C1ZS");
        var raw = fx.rawRow(dev);
        assertThat(raw.panEncrypted()).isNotNull();
        assertThat(raw.panNonce()).isNotNull();
        assertThat(raw.gstinEncrypted()).isNotNull();
    }

    @Test
    void patchWhilePendingIsLocked() {
        var dev = fx.developer();
        call(HttpMethod.GET, base(), null, dev);
        fx.setStatus(dev, "PENDING");

        var response = patch(dev, "ABOUT", Map.of("legalName", "New Name"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("VERIFICATION_LOCKED");
    }

    @Test
    void patchAfterVerifiedIsLocked() {
        var dev = fx.developer();
        call(HttpMethod.GET, base(), null, dev);
        fx.setStatus(dev, "VERIFIED");

        var response = patch(dev, "ABOUT", Map.of("legalName", "New Name"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=DeveloperVerificationApiTest`
Expected: FAIL — no `PATCH` mapping exists yet (405 Method Not Allowed on every new test).

- [ ] **Step 3: Implement `patchStep` in the service**

Replace the full content of `DeveloperVerificationService.java` with:
```java
package com.agentstore.platform.verification;

import com.agentstore.platform.common.AesGcmCipher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Service
public class DeveloperVerificationService {

    private static final Pattern PAN_PATTERN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    private static final Pattern GSTIN_PATTERN = Pattern.compile("^[0-9]{2}[A-Z0-9]{13}$");
    private static final Pattern PHONE_IN = Pattern.compile("^[6-9]\\d{9}$");
    private static final Pattern PHONE_E164 = Pattern.compile("^\\+\\d{10,15}$");

    private final DeveloperVerificationRepository repo;
    private final AesGcmCipher cipher;

    public DeveloperVerificationService(DeveloperVerificationRepository repo, AesGcmCipher cipher) {
        this.repo = repo;
        this.cipher = cipher;
    }

    @Transactional
    public DeveloperVerification getOrCreate(UUID orgId) {
        return repo.findByOrgId(orgId).orElseGet(() -> repo.save(new DeveloperVerification(orgId)));
    }

    @Transactional
    public DeveloperVerification patchStep(UUID orgId, VerificationStep step, Map<String, String> fields) {
        DeveloperVerification v = getOrCreate(orgId);
        requireEditable(v);
        switch (step) {
            case ABOUT -> applyAbout(v, fields);
            case TAX_IDS -> applyTaxIds(v, fields);
            case PHONE -> applyPhone(v, fields);
            case AGREEMENT -> applyAgreement(v, fields);
        }
        if (stepComplete(v, step)) {
            VerificationStep candidate = step.next();
            if (candidate.ordinal() > v.getCurrentStep().ordinal()) {
                v.setCurrentStep(candidate);
            }
        }
        return repo.save(v);
    }

    private void requireEditable(DeveloperVerification v) {
        if (v.getStatus() != VerificationStatus.DRAFT && v.getStatus() != VerificationStatus.FIX_REQUESTED) {
            throw VerificationApiException.locked();
        }
    }

    private void applyAbout(DeveloperVerification v, Map<String, String> fields) {
        if (fields.containsKey("legalName")) v.setLegalName(nonBlank(fields.get("legalName"), "legalName"));
        if (fields.containsKey("displayName")) v.setDisplayName(nonBlank(fields.get("displayName"), "displayName"));
        if (fields.containsKey("country")) {
            String country = nonBlank(fields.get("country"), "country");
            if (country.length() != 2) {
                throw VerificationApiException.invalidField("country must be a 2-letter code");
            }
            v.setCountry(country.toUpperCase(Locale.ROOT));
        }
        if (fields.containsKey("city")) v.setCity(nonBlank(fields.get("city"), "city"));
        if (fields.containsKey("entityType")) {
            try {
                v.setEntityType(VerificationEntityType.valueOf(fields.get("entityType")));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw VerificationApiException.invalidField(
                        "entityType must be one of " + Arrays.toString(VerificationEntityType.values()));
            }
        }
    }

    private void applyTaxIds(DeveloperVerification v, Map<String, String> fields) {
        if (fields.containsKey("pan")) {
            String pan = fields.get("pan");
            if (pan == null || !PAN_PATTERN.matcher(pan).matches()) {
                throw VerificationApiException.invalidField("pan must match " + PAN_PATTERN.pattern());
            }
            encryptInto(v::setPanEncrypted, v::setPanNonce, v::setPanMasked, pan);
        }
        if (fields.containsKey("gstin")) {
            String gstin = fields.get("gstin");
            if (gstin != null && !gstin.isBlank()) {
                if (!GSTIN_PATTERN.matcher(gstin).matches()) {
                    throw VerificationApiException.invalidField("gstin must match " + GSTIN_PATTERN.pattern());
                }
                encryptInto(v::setGstinEncrypted, v::setGstinNonce, v::setGstinMasked, gstin);
            }
        }
    }

    private void applyPhone(DeveloperVerification v, Map<String, String> fields) {
        if (fields.containsKey("phone")) {
            String phone = fields.get("phone");
            if (phone == null || !(PHONE_IN.matcher(phone).matches() || PHONE_E164.matcher(phone).matches())) {
                throw VerificationApiException.invalidField("phone must be a valid Indian mobile or E.164 number");
            }
            encryptInto(v::setPhoneEncrypted, v::setPhoneNonce, v::setPhoneMasked, phone);
        }
    }

    private void applyAgreement(DeveloperVerification v, Map<String, String> fields) {
        if (fields.containsKey("agreementVersion")) {
            String version = fields.get("agreementVersion");
            if (!DeveloperAgreement.CURRENT_VERSION.equals(version)) {
                throw VerificationApiException.invalidField(
                        "agreementVersion must be " + DeveloperAgreement.CURRENT_VERSION);
            }
            v.setAgreementVersion(version);
            v.setAgreementAcceptedAt(java.time.Instant.now());
        }
    }

    private void encryptInto(Consumer<byte[]> setCipher, Consumer<byte[]> setNonce, Consumer<String> setMasked,
                             String plaintext) {
        AesGcmCipher.Encrypted encrypted = cipher.encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
        setCipher.accept(encrypted.ciphertext());
        setNonce.accept(encrypted.nonce());
        setMasked.accept(last4(plaintext));
    }

    private static String last4(String value) {
        return value.length() < 8 ? "****" : value.substring(value.length() - 4);
    }

    private static String nonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw VerificationApiException.invalidField(field + " must not be blank");
        }
        return value;
    }

    static boolean aboutComplete(DeveloperVerification v) {
        return isSet(v.getLegalName()) && isSet(v.getDisplayName()) && isSet(v.getCountry()) && isSet(v.getCity())
                && v.getEntityType() != null;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean stepComplete(DeveloperVerification v, VerificationStep step) {
        return switch (step) {
            case ABOUT -> aboutComplete(v);
            case TAX_IDS -> v.getPanEncrypted() != null;
            case PHONE -> v.getPhoneEncrypted() != null;
            case AGREEMENT -> v.getAgreementAcceptedAt() != null;
        };
    }
}
```

Also create `backend/src/main/java/com/agentstore/platform/verification/DeveloperAgreement.java`:
```java
package com.agentstore.platform.verification;

public final class DeveloperAgreement {

    public static final String CURRENT_VERSION = "2.1";

    private DeveloperAgreement() {
    }
}
```

- [ ] **Step 4: Add the `PATCH` endpoint to the controller**

Add to `DeveloperVerificationController.java` (new imports `PatchMapping`, `RequestBody`, `java.util.Map`):
```java
    public record StepUpdateRequest(VerificationStep step, Map<String, String> fields) {
    }

    @PatchMapping
    public DeveloperVerificationView patch(@RequestBody StepUpdateRequest request) {
        return DeveloperVerificationView.from(
                service.patchStep(Caller.current().orgId(), request.step(), request.fields()));
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=DeveloperVerificationApiTest`
Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/agentstore/platform/verification \
        backend/src/test/java/com/agentstore/platform/verification
git commit -m "feat(verification): add PATCH step endpoint with validation, encryption, and lock gating"
```

---

## Task 3: PAN document upload

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationService.java`
- Modify: `backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationController.java`
- Test: `backend/src/test/java/com/agentstore/platform/verification/DeveloperVerificationApiTest.java`

**Interfaces:**
- Consumes: `DeveloperVerificationService.getOrCreate`, `requireEditable`-equivalent lock check (Tasks 1–2).
- Consumes: `ObjectStorage.put(String key, byte[] bytes)` (existing bean, `backend/src/main/java/com/agentstore/platform/storage/ObjectStorage.java`).
- Produces: `DeveloperVerificationService.uploadPanDocument(UUID orgId, MultipartFile file): DeveloperVerification` — Task 4's submit-completeness test uses this to make a verification submittable.

- [ ] **Step 1: Write the failing tests**

Add to `DeveloperVerificationApiTest` (new imports: `org.springframework.core.io.ByteArrayResource`, `org.springframework.http.HttpHeaders`, `org.springframework.http.MediaType`, `org.springframework.util.LinkedMultiValueMap`, `org.springframework.util.MultiValueMap`). Note the helper's parameter is named `dev`, not `org` — a local named `org` would shadow the `org.springframework.*` package prefix used inline below and fail to compile:
```java
    ResponseEntity<Map> uploadPanDocument(Org dev, String contentType, byte[] bytes) {
        var headers = fx.bearer(dev);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        // ByteArrayResource carries no real Content-Type of its own; wrapping it in an HttpEntity with its
        // own part-level headers is how the declared type reaches the server's per-part Content-Type.
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "pan.jpg";
            }
        }, partHeaders));
        return rest.exchange(base() + "/pan-document", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    @Test
    void oversizedPanDocumentIsRejected() {
        var dev = fx.developer();
        byte[] tooBig = new byte[6 * 1024 * 1024];

        var response = uploadPanDocument(dev, "image/jpeg", tooBig);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().get("code")).isEqualTo("DOCUMENT_TOO_LARGE");
    }

    @Test
    void wrongContentTypePanDocumentIsRejected() {
        var dev = fx.developer();

        var response = uploadPanDocument(dev, "text/plain", "not a document".getBytes());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody().get("code")).isEqualTo("UNSUPPORTED_DOCUMENT_TYPE");
    }

    @Test
    void validPanDocumentUploadSetsUploadedFlag() {
        var dev = fx.developer();

        var response = uploadPanDocument(dev, "image/jpeg", "fake-jpeg-bytes".getBytes());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("panDocumentUploaded")).isEqualTo(true);
    }

    @Test
    void panDocumentUploadWhilePendingIsLocked() {
        var dev = fx.developer();
        call(HttpMethod.GET, base(), null, dev);
        fx.setStatus(dev, "PENDING");

        var response = uploadPanDocument(dev, "image/jpeg", "fake-jpeg-bytes".getBytes());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("VERIFICATION_LOCKED");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=DeveloperVerificationApiTest`
Expected: FAIL — no `pan-document` mapping exists yet (404).

- [ ] **Step 3: Implement `uploadPanDocument` in the service**

In `DeveloperVerificationService.java`, add imports `com.agentstore.platform.storage.ObjectStorage`, `org.springframework.web.multipart.MultipartFile`, `java.io.IOException`, `java.util.Set`.

Replace the field declarations and constructor:
```java
    private final DeveloperVerificationRepository repo;
    private final AesGcmCipher cipher;

    public DeveloperVerificationService(DeveloperVerificationRepository repo, AesGcmCipher cipher) {
        this.repo = repo;
        this.cipher = cipher;
    }
```
with:
```java
    private static final Set<String> ALLOWED_DOCUMENT_TYPES = Set.of("image/jpeg", "image/png", "application/pdf");
    private static final long MAX_DOCUMENT_BYTES = 5L * 1024 * 1024;

    private final DeveloperVerificationRepository repo;
    private final AesGcmCipher cipher;
    private final ObjectStorage storage;

    public DeveloperVerificationService(DeveloperVerificationRepository repo, AesGcmCipher cipher, ObjectStorage storage) {
        this.repo = repo;
        this.cipher = cipher;
        this.storage = storage;
    }
```

Then add this new method to the class (anywhere after `getOrCreate`):
```java
    @Transactional
    public DeveloperVerification uploadPanDocument(UUID orgId, MultipartFile file) throws IOException {
        DeveloperVerification v = getOrCreate(orgId);
        requireEditable(v);
        if (file.getSize() > MAX_DOCUMENT_BYTES) {
            throw VerificationApiException.documentTooLarge();
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_DOCUMENT_TYPES.contains(contentType)) {
            throw VerificationApiException.unsupportedDocumentType();
        }
        String key = "developer-verification/" + orgId + "/pan-document";
        storage.put(key, file.getBytes());
        v.setPanDocumentKey(key);
        return repo.save(v);
    }
```

- [ ] **Step 4: Add the endpoint to the controller**

Add to `DeveloperVerificationController.java` (new imports `org.springframework.http.MediaType`, `org.springframework.web.bind.annotation.PostMapping`, `org.springframework.web.bind.annotation.RequestParam`, `org.springframework.web.multipart.MultipartFile`, `java.io.IOException`):
```java
    @PostMapping(value = "/pan-document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DeveloperVerificationView uploadPanDocument(@RequestParam("file") MultipartFile file) throws IOException {
        return DeveloperVerificationView.from(service.uploadPanDocument(Caller.current().orgId(), file));
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=DeveloperVerificationApiTest`
Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/agentstore/platform/verification \
        backend/src/test/java/com/agentstore/platform/verification
git commit -m "feat(verification): add PAN document upload endpoint"
```

---

## Task 4: Submit

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationService.java`
- Modify: `backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationController.java`
- Test: `backend/src/test/java/com/agentstore/platform/verification/DeveloperVerificationApiTest.java`

**Interfaces:**
- Consumes: `DeveloperVerificationService.aboutComplete(DeveloperVerification)` (package-private, Task 2), `getOrCreate`, `requireEditable`-equivalent lock check.
- Produces: `DeveloperVerificationService.submit(UUID orgId): DeveloperVerification` — Task 5's whole-flow test drives the happy path through this.

- [ ] **Step 1: Write the failing tests**

Add to `DeveloperVerificationApiTest`:
```java
    ResponseEntity<Map> submit(Org org) {
        return rest.exchange(base() + "/submit", HttpMethod.POST, new HttpEntity<>(null, fx.bearer(org)), Map.class);
    }

    void completeAllSteps(Org dev) {
        patch(dev, "ABOUT", Map.of("legalName", "Acme LLP", "displayName", "Acme", "country", "IN", "city", "Pune",
                "entityType", "LLP"));
        patch(dev, "TAX_IDS", Map.of("pan", "ABCDE1234F"));
        patch(dev, "PHONE", Map.of("phone", "9876543210"));
        patch(dev, "AGREEMENT", Map.of("agreementVersion", "2.1"));
        uploadPanDocument(dev, "image/jpeg", "fake-jpeg-bytes".getBytes());
    }

    @Test
    void submitWithMissingStepsIsIncompleteAndNamesWhatsMissing() {
        var dev = fx.developer();
        patch(dev, "ABOUT", Map.of("legalName", "Acme LLP", "displayName", "Acme", "country", "IN", "city", "Pune",
                "entityType", "LLP"));

        var response = submit(dev);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("VERIFICATION_INCOMPLETE");
        @SuppressWarnings("unchecked")
        var missing = (java.util.List<String>) response.getBody().get("missingSteps");
        assertThat(missing).contains("TAX_IDS", "PHONE", "AGREEMENT", "PAN_DOCUMENT");
    }

    @Test
    void submitSucceedsWhenEverythingIsPresentAndThenLocksFurtherEdits() {
        var dev = fx.developer();
        completeAllSteps(dev);

        var response = submit(dev);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("PENDING");
        assertThat(response.getBody().get("submittedAt")).isNotNull();

        var lockedPatch = patch(dev, "ABOUT", Map.of("legalName", "Changed"));
        assertThat(lockedPatch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var lockedUpload = uploadPanDocument(dev, "image/jpeg", "more-bytes".getBytes());
        assertThat(lockedUpload.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void resubmissionFromFixRequestedIncrementsCountExactlyOncePerSubmit() {
        var dev = fx.developer();
        completeAllSteps(dev);
        fx.setStatus(dev, "FIX_REQUESTED");

        var response = submit(dev);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("resubmissionCount")).isEqualTo(1);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=DeveloperVerificationApiTest`
Expected: FAIL — no `submit` mapping exists yet (404).

- [ ] **Step 3: Implement `submit` in the service**

Add to `DeveloperVerificationService.java` (new imports `java.time.Instant`, `java.util.ArrayList`, `java.util.List`):
```java
    @Transactional
    public DeveloperVerification submit(UUID orgId) {
        DeveloperVerification v = getOrCreate(orgId);
        requireEditable(v);
        List<String> missing = new ArrayList<>();
        if (!aboutComplete(v)) missing.add("ABOUT");
        if (v.getPanEncrypted() == null) missing.add("TAX_IDS");
        if (v.getPhoneEncrypted() == null) missing.add("PHONE");
        if (v.getAgreementAcceptedAt() == null) missing.add("AGREEMENT");
        if (v.getPanDocumentKey() == null) missing.add("PAN_DOCUMENT");
        if (!missing.isEmpty()) {
            throw VerificationApiException.incomplete(missing);
        }
        boolean wasFixRequested = v.getStatus() == VerificationStatus.FIX_REQUESTED;
        v.setStatus(VerificationStatus.PENDING);
        v.setSubmittedAt(Instant.now());
        if (wasFixRequested) {
            v.setResubmissionCount(v.getResubmissionCount() + 1);
        }
        return repo.save(v);
    }
```

- [ ] **Step 4: Add the endpoint to the controller**

Add to `DeveloperVerificationController.java`:
```java
    @PostMapping("/submit")
    public DeveloperVerificationView submit() {
        return DeveloperVerificationView.from(service.submit(Caller.current().orgId()));
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=DeveloperVerificationApiTest`
Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/agentstore/platform/verification \
        backend/src/test/java/com/agentstore/platform/verification
git commit -m "feat(verification): add submit endpoint with completeness check and resubmission counting"
```

---

## Task 5: Cross-org isolation sweep, whole-flow PII assertion, and `ONTOLOGY.md`

**Files:**
- Test: `backend/src/test/java/com/agentstore/platform/verification/DeveloperVerificationApiTest.java`
- Modify: `ONTOLOGY.md`

**Interfaces:**
- Consumes: every endpoint from Tasks 1–4. No production code changes in this task — it closes the two remaining spec §5 bullets ("cross-org isolation", "no unmasked PAN/GSTIN/phone in any response across the whole test") that the per-endpoint tests in Tasks 1–4 only partially covered (Task 1 covered `GET` isolation only).

- [ ] **Step 1: Write the failing tests**

Add to `DeveloperVerificationApiTest`:
```java
    @Test
    void anotherOrgsJwtCannotPatchUploadOrSubmitThisOrgsRow() {
        var dev = fx.developer();
        var other = fx.developer();
        patch(dev, "ABOUT", Map.of("legalName", "Acme LLP", "displayName", "Acme", "country", "IN", "city", "Pune",
                "entityType", "LLP"));

        // "other" acts on its own org's row (there is no cross-org PATCH path — every call is org-scoped from the
        // caller's own JWT), so this instead proves each org's action lands on its own separate row, never the other's.
        var otherPatch = patch(other, "ABOUT", Map.of("legalName", "Other Co"));
        assertThat(otherPatch.getStatusCode()).isEqualTo(HttpStatus.OK);

        var devRow = call(HttpMethod.GET, base(), null, dev);
        assertThat(devRow.getBody().get("legalName")).isEqualTo("Acme LLP");
        var otherRow = call(HttpMethod.GET, base(), null, other);
        assertThat(otherRow.getBody().get("legalName")).isEqualTo("Other Co");
    }

    @Test
    void noUnmaskedPanGstinOrPhoneAppearsInAnyResponseAcrossTheWholeFlow() {
        var dev = fx.developer();
        String rawPan = "ABCDE1234F";
        String rawGstin = "29AAAPL1234C1ZS";
        String rawPhone = "9876543210";

        var about = patch(dev, "ABOUT", Map.of("legalName", "Acme LLP", "displayName", "Acme", "country", "IN",
                "city", "Pune", "entityType", "LLP"));
        var tax = patch(dev, "TAX_IDS", Map.of("pan", rawPan, "gstin", rawGstin));
        var phone = patch(dev, "PHONE", Map.of("phone", rawPhone));
        var agreement = patch(dev, "AGREEMENT", Map.of("agreementVersion", "2.1"));
        var doc = uploadPanDocument(dev, "image/jpeg", "fake-jpeg-bytes".getBytes());
        var submitted = submit(dev);
        var fetched = call(HttpMethod.GET, base(), null, dev);

        for (var response : java.util.List.of(about, tax, phone, agreement, doc, submitted, fetched)) {
            assertThat(response.getBody().toString()).doesNotContain(rawPan).doesNotContain(rawGstin)
                    .doesNotContain(rawPhone);
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail or pass**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=DeveloperVerificationApiTest`
Expected: both PASS immediately — this task adds no new production behavior, only closes test coverage gaps. If either fails, it means Tasks 1–4 have a real isolation or leakage bug; fix the service/controller code (not the test) before proceeding.

- [ ] **Step 3: Update `ONTOLOGY.md`**

Insert a new subsection right after `### PII scan, trial profiles, REPO sync (WS6c)` (before `## 3. Properties & Semantic Relationships`):
```markdown
### Developer verification submission (WS7a)
A developer org fills out a resumable four-step identity form and submits it for review; nothing here decides the outcome (7b).
* **`DeveloperVerification`** (`backend/src/main/java/com/agentstore/platform/verification/DeveloperVerification.java`, table `developer_verification`, migration `V36__developer_verification.sql`): one row per org (`org_id UNIQUE`), created lazily on first `GET`/`PATCH`/upload/`submit` — no separate "start" endpoint. `status` (`VerificationStatus`: `DRAFT | PENDING | VERIFIED | FIX_REQUESTED | REJECTED`) and `currentStep` (`VerificationStep`: `ABOUT | TAX_IDS | PHONE | AGREEMENT`, fixed order, never regresses). `pan`/`gstin`/`phone` are AES-GCM encrypted (`AesGcmCipher`, same key material as `ByokCredential`); only `*Masked` (last 4 characters, `ByokKeyService.last4`'s pattern) is ever read back. `panDocumentKey` points at an `ObjectStorage` object (`developer-verification/{orgId}/pan-document`, overwritten on re-upload). `reviewerUserId`, `decisionNote`, `decidedAt` exist but are unwritten until 7b. No bank/payout columns, no `phone_verified_at` — phone and PAN/GSTIN vendor checks are both deferred to 7b's `verification_check` (manual for phone; `ManualKycProvider` for PAN/GSTIN), which does not exist yet.
* **`DeveloperVerificationService`** (`backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationService.java`): `patchStep` validates one step's fields (PAN `^[A-Z]{5}[0-9]{4}[A-Z]$`, GSTIN `^[0-9]{2}[A-Z0-9]{13}$`, phone Indian-mobile-or-E.164 — same shapes as `PiiScanner`), merges only the fields present (partial saves), and advances `currentStep` only when the *patched* step's required fields are complete and doing so would move forward, never backward. `submit` requires `ABOUT` complete, `TAX_IDS` to have at least `pan`, `PHONE` complete, `AGREEMENT` accepted, and a PAN document present, naming each missing piece (including the synthetic name `PAN_DOCUMENT`, which is not a `VerificationStep`) in `400 VERIFICATION_INCOMPLETE`; on success sets `status = PENDING`, stamps `submittedAt`, and increments `resubmissionCount` only when the prior status was `FIX_REQUESTED`. `PATCH`/document-upload/`submit` all reject with `409 VERIFICATION_LOCKED` unless `status` is `DRAFT` or `FIX_REQUESTED`.
* **`DeveloperVerificationController`** (`backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationController.java`): `GET`/`PATCH /api/v1/developer/verification`, `POST .../pan-document` (multipart, `image/jpeg`/`image/png`/`application/pdf`, 5 MB max), `POST .../submit`. All `hasRole('DEVELOPER')`, org-scoped from the JWT via `Caller.current()`.
```

Also update the existing `developer_verified_at` bullet under `## Inconsistencies & Unclear Areas` (it currently says "until WS7"; 7a has landed and still does not write it):

Find:
```
- **`developer_verified_at` is never set by code:** `org.developer_verified_at` has no writer until WS7; tests set it directly, so BYOK is unusable in a real deployment until then. WS6a reads the same column for the sandbox quota tier (unverified vs. verified limits), so every org is "unverified" — the tighter limits — until WS7 ships.
```
Replace with:
```
- **`developer_verified_at` is never set by code:** `org.developer_verified_at` has no writer until WS7b; tests set it directly, so BYOK is unusable in a real deployment until then. WS7a (`DeveloperVerification`, above) builds the submission flow but deliberately does not touch this column — see its "Out of scope" list. WS6a reads the same column for the sandbox quota tier (unverified vs. verified limits), so every org is "unverified" — the tighter limits — until WS7b ships.
```

- [ ] **Step 4: Run the full backend test suite**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test`
Expected: PASS — no regressions in any other module.

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/java/com/agentstore/platform/verification/DeveloperVerificationApiTest.java ONTOLOGY.md
git commit -m "test(verification): add cross-org isolation and whole-flow PII assertions; update ONTOLOGY.md"
```
