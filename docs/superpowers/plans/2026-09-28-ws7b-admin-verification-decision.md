# WS7b — Admin Verification Decision Flow Implementation Plan


**Goal:** Let a platform `ADMIN` review a `PENDING` developer-verification submission, record a manual PASS/FAIL per identity check, and decide the outcome (`VERIFIED`/`FIX_REQUESTED`/`REJECTED`) — a `VERIFIED` decision is what finally sets `Org.developerVerifiedAt`, unblocking BYOK and the verified sandbox-quota tier.

**Architecture:** A new append-only `verification_check` table records each check attempt behind a `KycProvider` interface (`ManualKycProvider` today, a future automated vendor later without touching callers). A new `DeveloperVerificationReviewService` + `AdminVerificationController` under `/api/v1/admin/verifications` mirror the existing `PublishingService`/`ReviewController` admin-review pattern, but with a single `POST .../decision` endpoint (three outcomes, not two) instead of separate `/approve`+`/reject` routes. Everything lives in the existing `com.agentstore.platform.verification` package alongside WS7a's submission flow.

**Tech Stack:** Spring Boot, Spring Data JPA, PostgreSQL (Flyway migrations), Testcontainers (Postgres + SeaweedFS) integration tests, Maven.

**Spec:** `docs/superpowers/specs/2026-09-28-ws7b-admin-verification-decision-design.md`

## Global Constraints

- Every endpoint in this slice is `@PreAuthorize("hasRole('ADMIN')")` — `ADMIN` is the platform-wide reviewer role (`UserRole`), not an org admin.
- `verification_check` rows are append-only: never update or delete an existing row; a resubmission cycle adds new rows, the newest per `(verificationId, checkType)` is the current verdict.
- A decision is only possible while `developer_verification.status == PENDING`; guard with a row lock (`findByIdForUpdate`) taken before the status check, closing the same concurrent-decision race WS7a's fix (`e61e1c1`) closed for first-touch.
- No new column on `Org` beyond the existing `developerVerifiedAt` — `developer_verification.status` remains the sole source of truth for verification state [spec §7 I2].
- One `POST /api/v1/admin/verifications/{id}/decision` endpoint with an `outcome` discriminator (`VERIFIED`/`FIX_REQUESTED`/`REJECTED`), not separate action routes [spec §7 I3].
- Next migration number is `V37` (last is `V36__developer_verification.sql`).
- Test command from `backend/`: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=<ClassName>` (Colima must be running first: `colima start` if down).

## Review Focus

- A `VERIFIED` decision with an empty or missing `checks` list must still succeed — the spec deliberately does not require a check row per type; the admin's stated outcome is authoritative (Task 3).
- A `FIX_REQUESTED` or `REJECTED` decision that includes `checks` must still persist them — check persistence isn't conditional on the outcome being `VERIFIED` (Task 3).
- Deciding an already-decided (non-`PENDING`) verification must return `409 VERIFICATION_NOT_PENDING` and leave the first decision's fields untouched (Tasks 3 and 4).
- Two concurrent decision requests against the same `PENDING` verification: exactly one succeeds, the other observes the conflict — no double-write, no lost update (Task 4).
- A non-`ADMIN` caller (a `DEVELOPER`, whose org owns the very verification being decided) must get `403` on the queue, detail, *and* decision endpoints, not just one of them (Task 4).

---

### Task 1: `verification_check` data model and the `KycProvider` seam

**Files:**
- Create: `backend/src/main/resources/db/migration/V37__verification_check.sql`
- Create: `backend/src/main/java/com/agentstore/platform/verification/CheckType.java`
- Create: `backend/src/main/java/com/agentstore/platform/verification/CheckProvider.java`
- Create: `backend/src/main/java/com/agentstore/platform/verification/VerificationCheck.java`
- Create: `backend/src/main/java/com/agentstore/platform/verification/VerificationCheckRepository.java`
- Create: `backend/src/main/java/com/agentstore/platform/verification/KycProvider.java`
- Create: `backend/src/main/java/com/agentstore/platform/verification/ManualKycProvider.java`
- Test: `backend/src/test/java/com/agentstore/platform/verification/ManualKycProviderTest.java`

**Interfaces:**
- Consumes: `DeveloperVerification` (existing, for its `id`), `Org`/`OrgRepository`/`OrgType` (existing, test-only, to build a verification row's parent org).
- Produces: `CheckType` (`PAN`, `GSTIN`, `PHONE`), `CheckProvider` (`MANUAL`), `VerificationCheck` (getters: `getId()`, `getVerificationId()`, `getCheckType()`, `getProvider()`, `isPassed()`, `getNote()`, `getCheckedByUserId()`, `getCheckedAt()`), `VerificationCheckRepository.findByVerificationIdOrderByCheckedAtDesc(UUID verificationId): List<VerificationCheck>`, `KycProvider.check(UUID verificationId, CheckType type, boolean passed, String note, UUID adminUserId): VerificationCheck` implemented by `ManualKycProvider` (a `@Component`).

- [ ] **Step 1: Write the failing test**

```java
// backend/src/test/java/com/agentstore/platform/verification/ManualKycProviderTest.java
package com.agentstore.platform.verification;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ManualKycProviderTest extends AbstractIntegrationTest {

    @Autowired ManualKycProvider provider;
    @Autowired VerificationCheckRepository checks;
    @Autowired DeveloperVerificationRepository verifications;
    @Autowired OrgRepository orgs;

    DeveloperVerification verification() {
        Org org = orgs.save(new Org(null, "DevCo-" + UUID.randomUUID(), OrgType.DEVELOPER));
        return verifications.save(new DeveloperVerification(org.getId()));
    }

    @Test
    void checkPersistsAManualVerdictWithAllFields() {
        var v = verification();
        UUID adminId = UUID.randomUUID();

        VerificationCheck result = provider.check(v.getId(), CheckType.PAN, true, "matches document", adminId);

        assertThat(result.getId()).isNotNull();
        assertThat(result.getVerificationId()).isEqualTo(v.getId());
        assertThat(result.getCheckType()).isEqualTo(CheckType.PAN);
        assertThat(result.getProvider()).isEqualTo(CheckProvider.MANUAL);
        assertThat(result.isPassed()).isTrue();
        assertThat(result.getNote()).isEqualTo("matches document");
        assertThat(result.getCheckedByUserId()).isEqualTo(adminId);
        assertThat(result.getCheckedAt()).isNotNull();
    }

    @Test
    void repeatedChecksForTheSameTypeAreKeptNotOverwrittenNewestFirst() throws InterruptedException {
        var v = verification();

        provider.check(v.getId(), CheckType.PAN, false, "blurry image", UUID.randomUUID());
        Thread.sleep(5);
        provider.check(v.getId(), CheckType.PAN, true, "resubmitted, clear now", UUID.randomUUID());

        List<VerificationCheck> history = checks.findByVerificationIdOrderByCheckedAtDesc(v.getId());

        assertThat(history).hasSize(2);
        assertThat(history.get(0).isPassed()).isTrue();
        assertThat(history.get(1).isPassed()).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=ManualKycProviderTest`
Expected: compile failure — `ManualKycProvider`, `VerificationCheckRepository`, `VerificationCheck`, `CheckType`, `CheckProvider` don't exist yet.

- [ ] **Step 3: Write the migration**

```sql
-- backend/src/main/resources/db/migration/V37__verification_check.sql
CREATE TABLE verification_check (
    id UUID PRIMARY KEY,
    verification_id UUID NOT NULL REFERENCES developer_verification(id),
    check_type VARCHAR(20) NOT NULL,
    provider VARCHAR(20) NOT NULL,
    passed BOOLEAN NOT NULL,
    note TEXT,
    checked_by_user_id UUID NOT NULL REFERENCES app_user(id),
    checked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_verification_check_verification_id ON verification_check(verification_id);
```

- [ ] **Step 4: Write the enums, entity, repository, and provider**

```java
// backend/src/main/java/com/agentstore/platform/verification/CheckType.java
package com.agentstore.platform.verification;

public enum CheckType {
    PAN, GSTIN, PHONE
}
```

```java
// backend/src/main/java/com/agentstore/platform/verification/CheckProvider.java
package com.agentstore.platform.verification;

public enum CheckProvider {
    MANUAL
}
```

```java
// backend/src/main/java/com/agentstore/platform/verification/VerificationCheck.java
package com.agentstore.platform.verification;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** One admin's PASS/FAIL verdict for one identity check, appended on every decision — never overwritten, so a
 * resubmission's full history survives alongside the original attempt. */
@Entity
@Table(name = "verification_check")
@Getter
@NoArgsConstructor
public class VerificationCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID verificationId;

    @Enumerated(EnumType.STRING)
    private CheckType checkType;

    @Enumerated(EnumType.STRING)
    private CheckProvider provider;

    private boolean passed;

    private String note;

    private UUID checkedByUserId;

    private Instant checkedAt;

    public VerificationCheck(UUID verificationId, CheckType checkType, CheckProvider provider, boolean passed,
                              String note, UUID checkedByUserId) {
        this.verificationId = verificationId;
        this.checkType = checkType;
        this.provider = provider;
        this.passed = passed;
        this.note = note;
        this.checkedByUserId = checkedByUserId;
    }

    @PrePersist
    void onCreate() {
        if (checkedAt == null) checkedAt = Instant.now();
    }
}
```

```java
// backend/src/main/java/com/agentstore/platform/verification/VerificationCheckRepository.java
package com.agentstore.platform.verification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VerificationCheckRepository extends JpaRepository<VerificationCheck, UUID> {

    List<VerificationCheck> findByVerificationIdOrderByCheckedAtDesc(UUID verificationId);
}
```

```java
// backend/src/main/java/com/agentstore/platform/verification/KycProvider.java
package com.agentstore.platform.verification;

import java.util.UUID;

/** The seam between an identity check's verdict and how it was reached. {@link ManualKycProvider} is the only
 * implementation today — a human enters PASS/FAIL directly; a future automated vendor integration for PAN/GSTIN
 * or phone would implement this same contract without any change to the caller. */
public interface KycProvider {

    VerificationCheck check(UUID verificationId, CheckType type, boolean passed, String note, UUID adminUserId);
}
```

```java
// backend/src/main/java/com/agentstore/platform/verification/ManualKycProvider.java
package com.agentstore.platform.verification;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ManualKycProvider implements KycProvider {

    private final VerificationCheckRepository repo;

    public ManualKycProvider(VerificationCheckRepository repo) {
        this.repo = repo;
    }

    @Override
    public VerificationCheck check(UUID verificationId, CheckType type, boolean passed, String note, UUID adminUserId) {
        return repo.save(new VerificationCheck(verificationId, type, CheckProvider.MANUAL, passed, note, adminUserId));
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=ManualKycProviderTest`
Expected: PASS (2 tests)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V37__verification_check.sql \
        backend/src/main/java/com/agentstore/platform/verification/CheckType.java \
        backend/src/main/java/com/agentstore/platform/verification/CheckProvider.java \
        backend/src/main/java/com/agentstore/platform/verification/VerificationCheck.java \
        backend/src/main/java/com/agentstore/platform/verification/VerificationCheckRepository.java \
        backend/src/main/java/com/agentstore/platform/verification/KycProvider.java \
        backend/src/main/java/com/agentstore/platform/verification/ManualKycProvider.java \
        backend/src/test/java/com/agentstore/platform/verification/ManualKycProviderTest.java
git commit -m "feat(verification): add verification_check table and ManualKycProvider"
```

---

### Task 2: Queue lookup, id-based row lock, and the admin test fixture

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationRepository.java`
- Modify: `backend/src/main/java/com/agentstore/platform/verification/VerificationApiException.java`
- Modify: `backend/src/test/java/com/agentstore/platform/verification/DeveloperVerificationFixtures.java`
- Test: `backend/src/test/java/com/agentstore/platform/verification/DeveloperVerificationRepositoryTest.java`

**Interfaces:**
- Consumes: `VerificationCheck`/`CheckType`/`CheckProvider`/`KycProvider`/`ManualKycProvider` (Task 1), existing `DeveloperVerificationService.patchStep`/`uploadPanDocument`/`submit`, `VerificationStep`, `DeveloperAgreement.CURRENT_VERSION`.
- Produces: `DeveloperVerificationRepository.findByStatus(VerificationStatus): List<DeveloperVerification>`, `DeveloperVerificationRepository.findByIdForUpdate(UUID id): Optional<DeveloperVerification>`, `VerificationApiException.notPending(): VerificationApiException` (409, code `VERIFICATION_NOT_PENDING`), `DeveloperVerificationFixtures.pendingSubmission(Org org): DeveloperVerification` (drives a full submission to `PENDING` via the service), `DeveloperVerificationFixtures.adminBearer(): HttpHeaders`.

- [ ] **Step 1: Write the failing test**

```java
// backend/src/test/java/com/agentstore/platform/verification/DeveloperVerificationRepositoryTest.java
package com.agentstore.platform.verification;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class DeveloperVerificationRepositoryTest extends AbstractStorageIntegrationTest {

    @Autowired DeveloperVerificationFixtures fx;
    @Autowired DeveloperVerificationRepository repo;

    @Test
    void findByStatusReturnsOnlyPendingSubmissions() throws Exception {
        var pending = fx.developer();
        fx.pendingSubmission(pending);
        var untouched = fx.developer();

        assertThat(repo.findByOrgId(untouched.getId())).isEmpty();

        var results = repo.findByStatus(VerificationStatus.PENDING);

        assertThat(results).extracting(DeveloperVerification::getOrgId).contains(pending.getId());
        assertThat(results).extracting(DeveloperVerification::getOrgId).doesNotContain(untouched.getId());
    }

    @Test
    void findByIdForUpdateLocksARowByItsOwnId() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);

        var locked = repo.findByIdForUpdate(verification.getId());

        assertThat(locked).isPresent();
        assertThat(locked.get().getStatus()).isEqualTo(VerificationStatus.PENDING);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=DeveloperVerificationRepositoryTest`
Expected: compile failure — `findByStatus`, `findByIdForUpdate`, `pendingSubmission` don't exist yet.

- [ ] **Step 3: Add the repository methods**

```java
// backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationRepository.java
package com.agentstore.platform.verification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeveloperVerificationRepository extends JpaRepository<DeveloperVerification, UUID> {

    Optional<DeveloperVerification> findByOrgId(UUID orgId);

    List<DeveloperVerification> findByStatus(VerificationStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from DeveloperVerification v where v.orgId = :orgId")
    Optional<DeveloperVerification> findByOrgIdForUpdate(@Param("orgId") UUID orgId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from DeveloperVerification v where v.id = :id")
    Optional<DeveloperVerification> findByIdForUpdate(@Param("id") UUID id);
}
```

- [ ] **Step 4: Add the exception factory**

In `backend/src/main/java/com/agentstore/platform/verification/VerificationApiException.java`, add this method alongside `locked()`:

```java
    public static VerificationApiException notPending() {
        return new VerificationApiException(HttpStatus.CONFLICT, "VERIFICATION_NOT_PENDING",
                "This verification is not awaiting a decision");
    }
```

- [ ] **Step 5: Extend the test fixture**

Replace the whole file `backend/src/test/java/com/agentstore/platform/verification/DeveloperVerificationFixtures.java`:

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/** Shared setup for developer-verification tests: developer orgs, developer/admin JWTs, raw-row/status
 * inspection, and driving a real submission to PENDING for WS7b's admin tests to decide on. */
@Component
public class DeveloperVerificationFixtures {

    private final OrgRepository orgs;
    private final JwtService jwt;
    private final JdbcTemplate jdbc;
    private final DeveloperVerificationService service;

    public DeveloperVerificationFixtures(OrgRepository orgs, JwtService jwt, JdbcTemplate jdbc,
                                          DeveloperVerificationService service) {
        this.orgs = orgs;
        this.jwt = jwt;
        this.jdbc = jdbc;
        this.service = service;
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

    /** An admin bearer token; the org id is irrelevant to an ADMIN caller. */
    public HttpHeaders adminBearer() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt.issue(UUID.randomUUID(), UUID.randomUUID(), UserRole.ADMIN));
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

    /** Drives a real org through WS7a's full submission flow via the service (already covered end to end by
     * DeveloperVerificationApiTest), landing it at PENDING for WS7b's admin tests to decide on. */
    public DeveloperVerification pendingSubmission(Org org) throws IOException {
        service.patchStep(org.getId(), VerificationStep.ABOUT, Map.of(
                "legalName", "Legal Name Pvt Ltd", "displayName", "Display Name",
                "country", "IN", "city", "Bengaluru", "entityType", "PRIVATE_LIMITED"));
        service.patchStep(org.getId(), VerificationStep.TAX_IDS, Map.of("pan", "ABCDE1234F"));
        service.patchStep(org.getId(), VerificationStep.PHONE, Map.of("phone", "9876543210"));
        service.patchStep(org.getId(), VerificationStep.AGREEMENT,
                Map.of("agreementVersion", DeveloperAgreement.CURRENT_VERSION));
        service.uploadPanDocument(org.getId(),
                new MockMultipartFile("file", "pan.jpg", "image/jpeg", "fake-pan-bytes".getBytes()));
        return service.submit(org.getId());
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=DeveloperVerificationRepositoryTest`
Expected: PASS (2 tests)

- [ ] **Step 7: Run the existing WS7a suite to confirm the fixture change is non-breaking**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=DeveloperVerificationApiTest`
Expected: PASS (all pre-existing WS7a tests still pass — the fixture constructor gained a parameter but Spring autowires it automatically)

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationRepository.java \
        backend/src/main/java/com/agentstore/platform/verification/VerificationApiException.java \
        backend/src/test/java/com/agentstore/platform/verification/DeveloperVerificationFixtures.java \
        backend/src/test/java/com/agentstore/platform/verification/DeveloperVerificationRepositoryTest.java
git commit -m "feat(verification): add queue/id-lock repository methods and admin test fixtures"
```

---

### Task 3: `DeveloperVerificationReviewService` and its response DTOs

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/verification/VerificationCheckView.java`
- Create: `backend/src/main/java/com/agentstore/platform/verification/VerificationQueueItemResponse.java`
- Create: `backend/src/main/java/com/agentstore/platform/verification/VerificationDetailResponse.java`
- Create: `backend/src/main/java/com/agentstore/platform/verification/VerificationDecisionRequest.java`
- Create: `backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationReviewService.java`
- Test: `backend/src/test/java/com/agentstore/platform/verification/DeveloperVerificationReviewServiceTest.java`

**Interfaces:**
- Consumes: `DeveloperVerificationRepository.findByStatus`/`findByIdForUpdate` (Task 2), `VerificationCheckRepository.findByVerificationIdOrderByCheckedAtDesc` (Task 1), `KycProvider.check` (Task 1), `VerificationApiException.notPending`/`invalidField` (Task 2 / existing), `ObjectStorage.presignGet(String key, Duration ttl): String` (existing), `OrgRepository.findById` (existing), `Org.getDeveloperVerifiedAt/setDeveloperVerifiedAt` (existing).
- Produces: `VerificationQueueItemResponse(UUID id, UUID orgId, String orgName, String legalName, VerificationEntityType entityType, VerificationStatus status, Instant submittedAt, int resubmissionCount)`, `VerificationCheckView(CheckType type, boolean passed, String note, Instant checkedAt)`, `VerificationDetailResponse(UUID id, UUID orgId, String orgName, VerificationStatus status, VerificationStep currentStep, VerificationEntityType entityType, String legalName, String displayName, String country, String city, String panMasked, String gstinMasked, String phoneMasked, String panDocumentUrl, String agreementVersion, Instant agreementAcceptedAt, Instant submittedAt, int resubmissionCount, UUID reviewerUserId, String decisionNote, Instant decidedAt, List<VerificationCheckView> checks)`, `VerificationDecisionRequest(VerificationStatus outcome, String notes, List<VerificationDecisionRequest.CheckInput> checks)` with nested `record CheckInput(CheckType type, boolean passed, String note)`, `DeveloperVerificationReviewService.queue(VerificationStatus status): List<VerificationQueueItemResponse>`, `.detail(UUID id): VerificationDetailResponse`, `.decide(UUID id, UUID adminUserId, VerificationDecisionRequest request): VerificationDetailResponse`.

- [ ] **Step 1: Write the failing test**

```java
// backend/src/test/java/com/agentstore/platform/verification/DeveloperVerificationReviewServiceTest.java
package com.agentstore.platform.verification;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.identity.OrgRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeveloperVerificationReviewServiceTest extends AbstractStorageIntegrationTest {

    @Autowired DeveloperVerificationReviewService service;
    @Autowired DeveloperVerificationFixtures fx;
    @Autowired OrgRepository orgs;
    @Autowired VerificationCheckRepository checks;

    @Test
    void queueReturnsOnlySubmissionsInTheRequestedStatus() throws Exception {
        var pending = fx.developer();
        fx.pendingSubmission(pending);

        var pendingQueue = service.queue(VerificationStatus.PENDING);
        var verifiedQueue = service.queue(VerificationStatus.VERIFIED);

        assertThat(pendingQueue).extracting(VerificationQueueItemResponse::orgId).contains(pending.getId());
        assertThat(verifiedQueue).extracting(VerificationQueueItemResponse::orgId).doesNotContain(pending.getId());
    }

    @Test
    void detailMasksIdentifiersAndIncludesAWorkingDocumentUrl() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);

        var detail = service.detail(verification.getId());

        assertThat(detail.panMasked()).isEqualTo("234F");
        assertThat(detail.panDocumentUrl()).startsWith("http");
        assertThat(detail.checks()).isEmpty();
    }

    @Test
    void detailThrows404ForAnUnknownId() {
        assertThatThrownBy(() -> service.detail(UUID.randomUUID()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void verifiedDecisionSetsOrgDeveloperVerifiedAtAndPersistsSubmittedChecks() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);
        var adminId = UUID.randomUUID();

        var result = service.decide(verification.getId(), adminId, new VerificationDecisionRequest(
                VerificationStatus.VERIFIED, "looks good",
                List.of(new VerificationDecisionRequest.CheckInput(CheckType.PAN, true, "matches"))));

        assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(result.reviewerUserId()).isEqualTo(adminId);
        assertThat(orgs.findById(org.getId()).orElseThrow().getDeveloperVerifiedAt()).isNotNull();
        assertThat(checks.findByVerificationIdOrderByCheckedAtDesc(verification.getId())).hasSize(1);
    }

    @Test
    void verifiedOutcomeIsAcceptedEvenWithNoChecksSubmitted() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);

        var result = service.decide(verification.getId(), UUID.randomUUID(),
                new VerificationDecisionRequest(VerificationStatus.VERIFIED, "trusted", null));

        assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(orgs.findById(org.getId()).orElseThrow().getDeveloperVerifiedAt()).isNotNull();
    }

    @Test
    void fixRequestedAndRejectedOutcomesStillPersistAnySubmittedChecksAndLeaveOrgUnverified() throws Exception {
        var rejected = fx.developer();
        var rejectedVerification = fx.pendingSubmission(rejected);

        var result = service.decide(rejectedVerification.getId(), UUID.randomUUID(),
                new VerificationDecisionRequest(VerificationStatus.REJECTED, "fake PAN",
                        List.of(new VerificationDecisionRequest.CheckInput(CheckType.PAN, false, "doesn't match"))));

        assertThat(result.status()).isEqualTo(VerificationStatus.REJECTED);
        assertThat(checks.findByVerificationIdOrderByCheckedAtDesc(rejectedVerification.getId())).hasSize(1);
        assertThat(orgs.findById(rejected.getId()).orElseThrow().getDeveloperVerifiedAt()).isNull();
    }

    @Test
    void decidingANonPendingVerificationThrowsAConflict() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);
        service.decide(verification.getId(), UUID.randomUUID(),
                new VerificationDecisionRequest(VerificationStatus.VERIFIED, "first", null));

        assertThatThrownBy(() -> service.decide(verification.getId(), UUID.randomUUID(),
                new VerificationDecisionRequest(VerificationStatus.REJECTED, "too late", null)))
                .isInstanceOfSatisfying(VerificationApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("VERIFICATION_NOT_PENDING");
                });
    }

    @Test
    void anOutcomeThatIsNotADecisionOutcomeIsRejected() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);

        assertThatThrownBy(() -> service.decide(verification.getId(), UUID.randomUUID(),
                new VerificationDecisionRequest(VerificationStatus.DRAFT, "n/a", null)))
                .isInstanceOfSatisfying(VerificationApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("INVALID_FIELD");
                });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=DeveloperVerificationReviewServiceTest`
Expected: compile failure — `DeveloperVerificationReviewService`, `VerificationDecisionRequest`, `VerificationQueueItemResponse` don't exist yet.

- [ ] **Step 3: Write the DTOs**

```java
// backend/src/main/java/com/agentstore/platform/verification/VerificationCheckView.java
package com.agentstore.platform.verification;

import java.time.Instant;

record VerificationCheckView(CheckType type, boolean passed, String note, Instant checkedAt) {

    static VerificationCheckView from(VerificationCheck c) {
        return new VerificationCheckView(c.getCheckType(), c.isPassed(), c.getNote(), c.getCheckedAt());
    }
}
```

```java
// backend/src/main/java/com/agentstore/platform/verification/VerificationQueueItemResponse.java
package com.agentstore.platform.verification;

import java.time.Instant;
import java.util.UUID;

record VerificationQueueItemResponse(
        UUID id, UUID orgId, String orgName, String legalName, VerificationEntityType entityType,
        VerificationStatus status, Instant submittedAt, int resubmissionCount) {

    static VerificationQueueItemResponse from(DeveloperVerification v, String orgName) {
        return new VerificationQueueItemResponse(v.getId(), v.getOrgId(), orgName, v.getLegalName(),
                v.getEntityType(), v.getStatus(), v.getSubmittedAt(), v.getResubmissionCount());
    }
}
```

```java
// backend/src/main/java/com/agentstore/platform/verification/VerificationDetailResponse.java
package com.agentstore.platform.verification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record VerificationDetailResponse(
        UUID id, UUID orgId, String orgName, VerificationStatus status, VerificationStep currentStep,
        VerificationEntityType entityType, String legalName, String displayName, String country, String city,
        String panMasked, String gstinMasked, String phoneMasked, String panDocumentUrl,
        String agreementVersion, Instant agreementAcceptedAt, Instant submittedAt, int resubmissionCount,
        UUID reviewerUserId, String decisionNote, Instant decidedAt, List<VerificationCheckView> checks) {

    static VerificationDetailResponse from(DeveloperVerification v, String orgName, String panDocumentUrl,
                                            List<VerificationCheck> checks) {
        return new VerificationDetailResponse(v.getId(), v.getOrgId(), orgName, v.getStatus(), v.getCurrentStep(),
                v.getEntityType(), v.getLegalName(), v.getDisplayName(), v.getCountry(), v.getCity(),
                v.getPanMasked(), v.getGstinMasked(), v.getPhoneMasked(), panDocumentUrl,
                v.getAgreementVersion(), v.getAgreementAcceptedAt(), v.getSubmittedAt(), v.getResubmissionCount(),
                v.getReviewerUserId(), v.getDecisionNote(), v.getDecidedAt(),
                checks.stream().map(VerificationCheckView::from).toList());
    }
}
```

```java
// backend/src/main/java/com/agentstore/platform/verification/VerificationDecisionRequest.java
package com.agentstore.platform.verification;

import java.util.List;

public record VerificationDecisionRequest(VerificationStatus outcome, String notes, List<CheckInput> checks) {

    public record CheckInput(CheckType type, boolean passed, String note) {
    }
}
```

- [ ] **Step 4: Write the service**

```java
// backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationReviewService.java
package com.agentstore.platform.verification;

import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.storage.ObjectStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class DeveloperVerificationReviewService {

    private static final Set<VerificationStatus> DECISION_OUTCOMES =
            Set.of(VerificationStatus.VERIFIED, VerificationStatus.FIX_REQUESTED, VerificationStatus.REJECTED);

    private final DeveloperVerificationRepository verifications;
    private final VerificationCheckRepository checks;
    private final OrgRepository orgs;
    private final ObjectStorage storage;
    private final KycProvider kycProvider;

    public DeveloperVerificationReviewService(DeveloperVerificationRepository verifications,
                                               VerificationCheckRepository checks, OrgRepository orgs,
                                               ObjectStorage storage, KycProvider kycProvider) {
        this.verifications = verifications;
        this.checks = checks;
        this.orgs = orgs;
        this.storage = storage;
        this.kycProvider = kycProvider;
    }

    public List<VerificationQueueItemResponse> queue(VerificationStatus status) {
        return verifications.findByStatus(status).stream()
                .map(v -> VerificationQueueItemResponse.from(v, orgName(v.getOrgId())))
                .toList();
    }

    public VerificationDetailResponse detail(UUID id) {
        DeveloperVerification v = verifications.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Verification not found"));
        String documentUrl = v.getPanDocumentKey() == null ? null
                : storage.presignGet(v.getPanDocumentKey(), Duration.ofMinutes(15));
        return VerificationDetailResponse.from(v, orgName(v.getOrgId()), documentUrl,
                checks.findByVerificationIdOrderByCheckedAtDesc(id));
    }

    @Transactional
    public VerificationDetailResponse decide(UUID id, UUID adminUserId, VerificationDecisionRequest request) {
        if (!DECISION_OUTCOMES.contains(request.outcome())) {
            throw VerificationApiException.invalidField("outcome must be one of " + DECISION_OUTCOMES);
        }
        DeveloperVerification v = verifications.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Verification not found"));
        if (v.getStatus() != VerificationStatus.PENDING) {
            throw VerificationApiException.notPending();
        }
        List<VerificationDecisionRequest.CheckInput> inputs = request.checks() == null ? List.of() : request.checks();
        for (var input : inputs) {
            kycProvider.check(v.getId(), input.type(), input.passed(), input.note(), adminUserId);
        }
        v.setReviewerUserId(adminUserId);
        v.setDecisionNote(request.notes());
        v.setDecidedAt(Instant.now());
        v.setStatus(request.outcome());
        verifications.save(v);
        if (request.outcome() == VerificationStatus.VERIFIED) {
            Org org = orgs.findById(v.getOrgId()).orElseThrow();
            org.setDeveloperVerifiedAt(Instant.now());
            orgs.save(org);
        }
        return detail(id);
    }

    private String orgName(UUID orgId) {
        return orgs.findById(orgId).map(Org::getName).orElse(null);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=DeveloperVerificationReviewServiceTest`
Expected: PASS (8 tests)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/agentstore/platform/verification/VerificationCheckView.java \
        backend/src/main/java/com/agentstore/platform/verification/VerificationQueueItemResponse.java \
        backend/src/main/java/com/agentstore/platform/verification/VerificationDetailResponse.java \
        backend/src/main/java/com/agentstore/platform/verification/VerificationDecisionRequest.java \
        backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationReviewService.java \
        backend/src/test/java/com/agentstore/platform/verification/DeveloperVerificationReviewServiceTest.java
git commit -m "feat(verification): add DeveloperVerificationReviewService (queue/detail/decide)"
```

---

### Task 4: `AdminVerificationController` and the HTTP-level test suite

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/verification/AdminVerificationController.java`
- Modify: `backend/src/main/java/com/agentstore/platform/verification/VerificationApiErrors.java`
- Test: `backend/src/test/java/com/agentstore/platform/verification/AdminVerificationApiTest.java`

**Interfaces:**
- Consumes: `DeveloperVerificationReviewService.queue`/`.detail`/`.decide` (Task 3), `Caller.current(): JwtService.Claims` with `.userId()` (existing, `com.agentstore.platform.data.Caller`), `DeveloperVerificationFixtures.pendingSubmission`/`.developer`/`.bearer`/`.adminBearer` (Task 2).
- Produces: `GET /api/v1/admin/verifications?status=`, `GET /api/v1/admin/verifications/{id}`, `POST /api/v1/admin/verifications/{id}/decision` — all `hasRole('ADMIN')`.

- [ ] **Step 1: Write the failing test**

```java
// backend/src/test/java/com/agentstore/platform/verification/AdminVerificationApiTest.java
package com.agentstore.platform.verification;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.identity.OrgRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AdminVerificationApiTest extends AbstractStorageIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DeveloperVerificationFixtures fx;
    @Autowired DeveloperVerificationRepository repo;
    @Autowired OrgRepository orgs;

    String base() {
        return "http://localhost:" + port + "/api/v1/admin/verifications";
    }

    ResponseEntity<Map> call(HttpMethod method, String url, Object body, HttpHeaders headers) {
        return rest.exchange(url, method, new HttpEntity<>(body, headers), Map.class);
    }

    ResponseEntity<List> queue(HttpHeaders headers) {
        return rest.exchange(base(), HttpMethod.GET, new HttpEntity<>(headers), List.class);
    }

    ResponseEntity<Map> decide(UUID id, Map<String, Object> body, HttpHeaders headers) {
        return call(HttpMethod.POST, base() + "/" + id + "/decision", body, headers);
    }

    @Test
    void queueReturnsOnlyPendingSubmissionsByDefault() throws Exception {
        var pending = fx.developer();
        fx.pendingSubmission(pending);
        var untouched = fx.developer();

        var response = queue(fx.adminBearer());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var orgIds = response.getBody().stream().map(item -> ((Map) item).get("orgId")).toList();
        assertThat(orgIds).contains(pending.getId().toString());
        assertThat(orgIds).doesNotContain(untouched.getId().toString());
    }

    @Test
    void detail404sForAnUnknownId() {
        var response = call(HttpMethod.GET, base() + "/" + UUID.randomUUID(), null, fx.adminBearer());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void detailNeverExposesRawPanGstinPhoneAndIncludesAWorkingDocumentUrl() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);

        var response = call(HttpMethod.GET, base() + "/" + verification.getId(), null, fx.adminBearer());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = response.getBody();
        assertThat(body.get("panMasked")).isEqualTo("234F");
        assertThat(body.toString()).doesNotContain("ABCDE1234F");
        assertThat((String) body.get("panDocumentUrl")).startsWith("http");
    }

    @Test
    void verifiedDecisionSetsOrgDeveloperVerifiedAtAndPersistsChecks() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);

        var response = decide(verification.getId(), Map.of(
                "outcome", "VERIFIED",
                "notes", "all documents check out",
                "checks", List.of(Map.of("type", "PAN", "passed", true, "note", "matches"))
        ), fx.adminBearer());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("VERIFIED");
        assertThat(orgs.findById(org.getId()).orElseThrow().getDeveloperVerifiedAt()).isNotNull();
        assertThat((List) response.getBody().get("checks")).hasSize(1);
    }

    @Test
    void rejectedDecisionIsTerminalAndLeavesOrgUnverified() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);

        decide(verification.getId(), Map.of("outcome", "REJECTED", "notes", "fake PAN"), fx.adminBearer());

        assertThat(orgs.findById(org.getId()).orElseThrow().getDeveloperVerifiedAt()).isNull();
        var reSubmit = call(HttpMethod.POST,
                "http://localhost:" + port + "/api/v1/developer/verification/submit", null, fx.bearer(org));
        assertThat(reSubmit.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reSubmit.getBody().get("code")).isEqualTo("VERIFICATION_LOCKED");
    }

    @Test
    void fixRequestedReopensEditingAndResubmissionIncrementsTheCounter() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);

        decide(verification.getId(), Map.of(
                "outcome", "FIX_REQUESTED",
                "notes", "PAN document is blurry",
                "checks", List.of(Map.of("type", "PAN", "passed", false, "note", "blurry"))
        ), fx.adminBearer());

        var patch = call(HttpMethod.PATCH, "http://localhost:" + port + "/api/v1/developer/verification",
                Map.of("step", "TAX_IDS", "fields", Map.of("pan", "ZZZZZ9999Z")), fx.bearer(org));
        assertThat(patch.getStatusCode()).isEqualTo(HttpStatus.OK);

        var resubmit = call(HttpMethod.POST,
                "http://localhost:" + port + "/api/v1/developer/verification/submit", null, fx.bearer(org));
        assertThat(resubmit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resubmit.getBody().get("resubmissionCount")).isEqualTo(1);
    }

    @Test
    void decidingATwiceReturnsConflictAndLeavesTheFirstDecisionIntact() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);
        decide(verification.getId(), Map.of("outcome", "VERIFIED", "notes", "ok"), fx.adminBearer());

        var second = decide(verification.getId(), Map.of("outcome", "REJECTED", "notes", "too late"), fx.adminBearer());

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("code")).isEqualTo("VERIFICATION_NOT_PENDING");
        assertThat(repo.findById(verification.getId()).orElseThrow().getStatus()).isEqualTo(VerificationStatus.VERIFIED);
    }

    @Test
    void anInvalidOutcomeIsRejected() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);

        var response = decide(verification.getId(), Map.of("outcome", "PENDING", "notes", "n/a"), fx.adminBearer());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_FIELD");
    }

    @Test
    void concurrentDecisionsOnTheSamePendingVerificationExactlyOneSucceeds() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<Map>> first = pool.submit(() -> raceDecide(verification.getId(), ready, go));
            Future<ResponseEntity<Map>> second = pool.submit(() -> raceDecide(verification.getId(), ready, go));
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            List<HttpStatus> statuses = List.of(
                    (HttpStatus) first.get(10, TimeUnit.SECONDS).getStatusCode(),
                    (HttpStatus) second.get(10, TimeUnit.SECONDS).getStatusCode());
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        } finally {
            pool.shutdown();
        }
    }

    private ResponseEntity<Map> raceDecide(UUID id, CountDownLatch ready, CountDownLatch go) throws InterruptedException {
        ready.countDown();
        go.await(5, TimeUnit.SECONDS);
        return decide(id, Map.of("outcome", "VERIFIED", "notes", "racing"), fx.adminBearer());
    }

    @Test
    void aNonAdminCallerIsForbiddenOnEveryEndpoint() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);
        HttpHeaders developerHeaders = fx.bearer(org);

        assertThat(queue(developerHeaders).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, base() + "/" + verification.getId(), null, developerHeaders).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(decide(verification.getId(), Map.of("outcome", "VERIFIED"), developerHeaders).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=AdminVerificationApiTest`
Expected: compile failure / connection refused — `AdminVerificationController` doesn't exist yet, `/api/v1/admin/verifications` 404s.

- [ ] **Step 3: Write the controller**

```java
// backend/src/main/java/com/agentstore/platform/verification/AdminVerificationController.java
package com.agentstore.platform.verification;

import com.agentstore.platform.data.Caller;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/verifications")
@PreAuthorize("hasRole('ADMIN')")
public class AdminVerificationController {

    private final DeveloperVerificationReviewService service;

    public AdminVerificationController(DeveloperVerificationReviewService service) {
        this.service = service;
    }

    @GetMapping
    public List<VerificationQueueItemResponse> queue(
            @RequestParam(defaultValue = "PENDING") VerificationStatus status) {
        return service.queue(status);
    }

    @GetMapping("/{id}")
    public VerificationDetailResponse detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @PostMapping("/{id}/decision")
    public VerificationDetailResponse decide(@PathVariable UUID id, @RequestBody VerificationDecisionRequest request) {
        return service.decide(id, Caller.current().userId(), request);
    }
}
```

- [ ] **Step 4: Wire it into the shared error handler**

In `backend/src/main/java/com/agentstore/platform/verification/VerificationApiErrors.java`, change the class annotation:

```java
@RestControllerAdvice(assignableTypes = {DeveloperVerificationController.class, AdminVerificationController.class})
public class VerificationApiErrors {
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=AdminVerificationApiTest`
Expected: PASS (9 tests)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/agentstore/platform/verification/AdminVerificationController.java \
        backend/src/main/java/com/agentstore/platform/verification/VerificationApiErrors.java \
        backend/src/test/java/com/agentstore/platform/verification/AdminVerificationApiTest.java
git commit -m "feat(verification): add AdminVerificationController (queue/detail/decision)"
```

---

### Task 5: `ONTOLOGY.md` and final verification

**Files:**
- Modify: `ONTOLOGY.md`

**Interfaces:**
- Consumes: nothing (documentation only).
- Produces: nothing (documentation only).

- [ ] **Step 1: Extend the WS7a/WS7b ontology section**

In `ONTOLOGY.md`, find the section starting `### Developer verification submission (WS7a)`. Replace the section header and its first sentence:

Old:
```
### Developer verification submission (WS7a)
A developer org fills out a resumable four-step identity form and submits it for review; nothing here decides the outcome (7b).
```

New:
```
### Developer verification submission & admin decision (WS7a/WS7b)
A developer org fills out a resumable four-step identity form and submits it for review; WS7b (below) adds the admin decision that sets the outcome.
```

- [ ] **Step 2: Update the two stale "deferred to 7b" sentences**

Old (inside the `DeveloperVerification` bullet):
```
`reviewerUserId`, `decisionNote`, `decidedAt` exist but are unwritten until 7b. No bank/payout columns, no `phone_verified_at` — phone and PAN/GSTIN vendor checks are both deferred to 7b's `verification_check` (manual for phone; `ManualKycProvider` for PAN/GSTIN), which does not exist yet.
```

New:
```
`reviewerUserId`, `decisionNote`, `decidedAt` are written by WS7b's decision endpoint (below). No bank/payout columns, no `phone_verified_at` — phone and PAN/GSTIN checks are recorded as `verification_check` rows by WS7b's `ManualKycProvider` (below), never a timestamp on this row.
```

- [ ] **Step 3: Add the WS7b bullets after the `DeveloperVerificationController` bullet**

After the line ending `...org-scoped from the JWT via \`Caller.current()\`.` (the `DeveloperVerificationController` bullet), insert:

```
* **`VerificationCheck`** (`backend/src/main/java/com/agentstore/platform/verification/VerificationCheck.java`, table `verification_check`, migration `V37__verification_check.sql`): one append-only row per admin check attempt — `verificationId`, `checkType` (`CheckType`: `PAN | GSTIN | PHONE`), `provider` (`CheckProvider`: `MANUAL`, the only value), `passed`, `note`, `checkedByUserId`, `checkedAt`. Never overwritten, so a resubmission cycle keeps every prior attempt; the newest row per `(verificationId, checkType)` is the current verdict. `KycProvider` is the interface (`check(verificationId, type, passed, note, adminUserId)`); `ManualKycProvider` is its only implementation — a human's own verdict, no vendor call — chosen so a future automated provider can implement the same contract later.
* **`DeveloperVerificationReviewService`** / **`AdminVerificationController`** (`backend/src/main/java/com/agentstore/platform/verification/`): `GET /api/v1/admin/verifications` (queue, filtered by `status`, default `PENDING`), `GET .../{id}` (full detail: masked identifiers, a 15-minute pre-signed PAN document URL, and the check history), `POST .../{id}/decision` (`{outcome: VERIFIED|FIX_REQUESTED|REJECTED, notes, checks: [{type, passed, note}]}`) — one endpoint with an outcome discriminator, not separate approve/reject routes, because there are three outcomes. Row-locks the verification (`findByIdForUpdate`) and requires `status == PENDING`, else `409 VERIFICATION_NOT_PENDING`. On `VERIFIED`, sets `Org.developerVerifiedAt = now()` — the first and only writer of that column, finally live for `ByokKeyService` (WS3) and `SandboxQuotaProperties` (WS6a), both of which have read it since before anything set it. `REJECTED` is terminal; `FIX_REQUESTED` reopens developer editing through WS7a's own `requireEditable` guard, no new code needed. All endpoints `hasRole('ADMIN')`. No `Org.verificationStatus` column exists or is planned — `developer_verification.status` remains the sole source of truth.
```

- [ ] **Step 4: Remove the now-resolved "Inconsistencies & Unclear Areas" bullet**

In the `## Inconsistencies & Unclear Areas` section, delete this bullet entirely (it is resolved by this workstream):

```
- **`developer_verified_at` is never set by code:** `org.developer_verified_at` has no writer until WS7b; tests set it directly, so BYOK is unusable in a real deployment until then. WS7a (`DeveloperVerification`, above) builds the submission flow but deliberately does not touch this column — see its "Out of scope" list. WS6a reads the same column for the sandbox quota tier (unverified vs. verified limits), so every org is "unverified" — the tighter limits — until WS7b ships.
```

- [ ] **Step 5: Update the intro paragraph and the tracked-files header**

In the intro paragraph (starts `_Generated from commit \`e295ec6\`...`), find:
```
sandbox core (WS6a, branch `feat/ws6a-sandbox-core`), behaviour report/flow/readiness/manifest patches (WS6b), PII scan/trial profiles/REPO sync (WS6c). Audited against master at commit `7fb6b50` (2026-09-27, after the WS3 merge), then updated in place for WS6a and WS6b.
```
Replace with:
```
sandbox core (WS6a, branch `feat/ws6a-sandbox-core`), behaviour report/flow/readiness/manifest patches (WS6b), PII scan/trial profiles/REPO sync (WS6c), developer verification submission (WS7a), admin verification decision (WS7b). Audited against master at commit `7fb6b50` (2026-09-27, after the WS3 merge), then updated in place for WS6a, WS6b, WS6c, WS7a and WS7b.
```

In the HTML comment at the top of the file (`ontology-tracked-files: ...`), find the exact substring `cli/src/commands/profile.ts` (it is the last entry in that comma-separated list — unique in the file) and append after it (before the closing `-->`):

```
,backend/src/main/java/com/agentstore/platform/verification/DeveloperVerification.java,backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationController.java,backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationRepository.java,backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationRowCreator.java,backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationService.java,backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationView.java,backend/src/main/java/com/agentstore/platform/verification/VerificationApiErrors.java,backend/src/main/java/com/agentstore/platform/verification/VerificationApiException.java,backend/src/main/java/com/agentstore/platform/verification/VerificationEntityType.java,backend/src/main/java/com/agentstore/platform/verification/VerificationStatus.java,backend/src/main/java/com/agentstore/platform/verification/VerificationStep.java,backend/src/main/java/com/agentstore/platform/verification/DeveloperAgreement.java,backend/src/main/resources/db/migration/V36__developer_verification.sql,backend/src/main/java/com/agentstore/platform/verification/CheckType.java,backend/src/main/java/com/agentstore/platform/verification/CheckProvider.java,backend/src/main/java/com/agentstore/platform/verification/VerificationCheck.java,backend/src/main/java/com/agentstore/platform/verification/VerificationCheckRepository.java,backend/src/main/java/com/agentstore/platform/verification/KycProvider.java,backend/src/main/java/com/agentstore/platform/verification/ManualKycProvider.java,backend/src/main/java/com/agentstore/platform/verification/DeveloperVerificationReviewService.java,backend/src/main/java/com/agentstore/platform/verification/AdminVerificationController.java,backend/src/main/java/com/agentstore/platform/verification/VerificationQueueItemResponse.java,backend/src/main/java/com/agentstore/platform/verification/VerificationDetailResponse.java,backend/src/main/java/com/agentstore/platform/verification/VerificationDecisionRequest.java,backend/src/main/java/com/agentstore/platform/verification/VerificationCheckView.java,backend/src/main/resources/db/migration/V37__verification_check.sql
```

- [ ] **Step 6: Run the full backend suite**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test`
Expected: PASS, 0 failures (confirms this slice didn't break WS7a, WS3's BYOK gate, or WS6a's sandbox quota tier tests)

- [ ] **Step 7: Commit**

```bash
git add ONTOLOGY.md
git commit -m "docs: update ONTOLOGY.md for WS7b (admin verification decision)"
```
