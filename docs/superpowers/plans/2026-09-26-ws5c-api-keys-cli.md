# WS5c — Developer API keys and CLI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A developer creates an org-scoped API key in the portal, then goes from an empty directory to a built agent version with `agentstore login`, `init`, `validate` and `push --wait`.

**Architecture:** The backend gains a `developer_api_key` table, three JWT-only management endpoints, and an `ApiKeyAuthFilter` that turns a valid `ask_live_…` key into the same `JwtService.Claims` principal a JWT produces (role always `DEVELOPER`), so no existing controller changes. An `ApiKeyScope` allowlist, enforced inside that filter, confines a key to the developer endpoints. A new `cli/` package (TypeScript, Node ≥ 22, run locally) talks to those endpoints. The frontend gets an API-keys page, and the submission wizard stops taking a template id and stops submitting; the version status page gains the submit button instead.

**Tech Stack:** Spring Boot 3.3 / Java 21 / JPA / Flyway-style SQL migrations / Testcontainers Postgres; TypeScript ESM CLI with `ajv`, `fflate`, `ignore`, `yaml`, Vitest; Next.js 14 + Vitest + Testing Library.

**Spec:** `docs/superpowers/specs/2026-09-26-ws5c-api-keys-cli-design.md` (with §6 of `2026-09-25-ws5-sandbox-runner-build-pipeline-cli-design.md` as background). Read both before starting.

## Global Constraints

- **Commits carry no AI attribution.** The user's global rule overrides the harness default: never add `Co-Authored-By`, a "Claude" or "Anthropic" reference, or a "Generated with" line to a commit message. Every commit command below is written that way; keep it.
- **Branch.** Work on a new branch off `master` (Task 0). Never commit to `master`.
- **Key format:** `ask_live_` + base62 of 32 random bytes, left-padded with `0` to 43 characters (52 characters in total). `prefix` is the first 12 characters. Only the SHA-256 (lower-case hex, 64 characters) is stored.
- **Key role is always `DEVELOPER`,** never its creator's role. Authorities set by the filter: `ROLE_DEVELOPER` and `AUTH_API_KEY`.
- **Key allowlist (everything else is 403 for a key):** `POST /api/v1/agents`; `GET /api/v1/agents/mine`; `/api/v1/agents/*/versions` and `/api/v1/agents/*/versions/**` **except** `/api/v1/agents/*/versions/*/submit` (and anything under it); `/api/v1/agents/*/bundles/**`; `/api/v1/agents/*/builds/**`.
- **Creator must still be a member.** A key is rejected (401) when its creator no longer has a `DEVELOPER` `org_membership` in the key's org.
- **`last_used_at`** is written at most once a minute per key.
- **Migration number:** `V24` (V23 is the last one).
- **Limits (mirror the backend):** bundle 100 MB (`100 * 1024 * 1024` bytes), 20,000 entries, `manifest.json` 256 KiB (`256 * 1024` bytes).
- **Runtimes:** `python-3.12` accepts entrypoints ending `.py`; `node-22` accepts `.js`, `.mjs`, `.cjs`. Entrypoint regex `^[A-Za-z0-9_][A-Za-z0-9_./-]*$`, no `//`, no `..` segment.
- **CLI exit codes:** 0 success; 1 the project or build is bad (`validate` problems, `FAILED` build); 2 the tool could not do its job (usage, network, rejected upload, wait timeout).
- **CLI credentials:** `~/.config/agentstore/credentials`, JSON `{apiKey, apiUrl}`, file mode 0600 in a 0700 directory. `AGENTSTORE_API_KEY` and `AGENTSTORE_API_URL` override the file. Default API URL `http://localhost:8080`. The CLI never prints the key or a signed upload URL.
- **`push --wait`:** poll every 2 seconds, default timeout 900 seconds.
- **Local backend tests** need Colima and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` in the environment (see the dev-environment memory). Every `mvn` command below assumes it is exported.
- **Code style:** match the surrounding code. The backend uses records for DTOs, Lombok `@Getter/@Setter/@NoArgsConstructor` on entities, constructor injection, and `@PreAuthorize` on controllers. The frontend uses server components that call `backend.get/post`, `"use server"` action files, and client components tested with mocked actions.
- **Local-only CLI:** no npm publish, no standalone binaries.

## Review Focus

These are inputs the spec implies but no obvious happy-path test covers; each has a test in the named task.

1. `agentstore init` run in a non-empty directory, or where `agentstore.yaml` already exists, must refuse **before** calling the API (no orphan agent). *(Task 6)*
2. `.agentstoreignore` excludes the manifest's entrypoint: `validate` and `push` must name the file and the ignore file, not fail later with a server-side 422. *(Task 5)*
3. `push --wait` when the build has no `logUrl` yet, or the log gets shorter between polls (a re-queued build rewrites it): no crash, no duplicated output. *(Task 7)*
4. The API server is unreachable, or `apiUrl` has a trailing slash: one readable line, exit code 2, no stack trace, no double slash in request paths. *(Tasks 4 and 7)*
5. The signed upload URL is rejected (expired) or contains a signature: exit code 2 and the URL never appears in output. *(Task 7)*

## File Structure

**Backend (`backend/src/main/java/com/agentstore/platform/identity/`)**
- Create `DeveloperApiKey.java` (entity), `DeveloperApiKeyRepository.java`, `ApiKeys.java` (generate + hash helpers), `DeveloperApiKeyService.java`, `DeveloperApiKeyController.java`, `ApiKeyScope.java` (the allowlist), `ApiKeyAuthFilter.java`.
- Modify `SecurityConfig.java` (register the filter).
- Create `backend/src/main/resources/db/migration/V24__developer_api_key.sql`.
- Modify `catalog/CreateAgentVersionRequest.java`, `catalog/CatalogController.java` (drop `imageRef`).
- Tests under `backend/src/test/java/com/agentstore/platform/identity/` (`ApiKeysTest`, `DeveloperApiKeyControllerTest`, `ApiKeyScopeTest`, `ApiKeyAuthTest`); modify `catalog/CreateAgentVersionTest.java`, `EndToEndTierATest.java`, `EndToEndTierBTest.java`.

**CLI (`cli/`, new)** — one responsibility per file:
- `package.json`, `tsconfig.json`, `tsconfig.build.json`, `vitest.config.ts`, `.gitignore`, `README.md`, `scripts/sync-schema.mjs`
- `src/bin.ts` (shebang entry), `src/cli.ts` (argument dispatch, exit-code mapping), `src/context.ts` (I/O seam), `src/errors.ts`, `src/config.ts` (credentials), `src/api.ts` (HTTP client), `src/project.ts` (`agentstore.yaml`), `src/pack.ts` (ignore rules, zip, sha256), `src/validate.ts`, `src/scaffold.ts` (`init` templates), `src/schema/manifest-schema.json` (synced copy), `src/commands/{login,validate,init,push}.ts`
- `test/helpers.ts` (mock HTTP server, test context) and one `*.test.ts` per module.

**Frontend (`frontend/src/`)**
- Create `app/(developer)/dashboard/api-keys/{page.tsx,actions.ts,ApiKeysView.tsx,ApiKeysView.test.tsx}`; modify `app/(developer)/layout.tsx`, `lib/backend.ts` (`delete`), `lib/types.ts`.
- Modify `components/SubmissionWizard.tsx` (+ test), `components/submissionActions.ts`, `app/(developer)/dashboard/DashboardList.tsx` (+ test), `app/(developer)/dashboard/agents/[agentId]/versions/[versionId]/status/ReviewStatusView.tsx` (+ test); create `components/SubmitVersionButton.tsx` (+ test) and `components/PushInstructions.tsx` (+ test); modify `frontend/e2e/seed.ts`.

**Docs/cleanup:** `ONTOLOGY.md` (+ re-stamp), `runner/README.md`, delete `scripts/dev-push-bundle.sh`, create `cli/README.md`.

---

### Task 0: Branch

- [ ] **Step 1: Create the working branch off master**

```bash
cd /Users/amittiwari/AgentStore
git fetch -q origin && git checkout -q master && git pull -q --ff-only
git checkout -b feat/ws5c-api-keys-cli
```

If the design docs are not on `master` yet (branch `docs/ws5c-design`, commits `f7728d7` and `1ad096b`), bring them in first: `git merge --ff-only docs/ws5c-design`. Then this plan file is already present too.

---

### Task 1: API key storage and management endpoints

**Files:**
- Create: `backend/src/main/resources/db/migration/V24__developer_api_key.sql`
- Create: `backend/src/main/java/com/agentstore/platform/identity/{ApiKeys,DeveloperApiKey,DeveloperApiKeyRepository,DeveloperApiKeyService,DeveloperApiKeyController}.java`
- Test: `backend/src/test/java/com/agentstore/platform/identity/{ApiKeysTest,DeveloperApiKeyControllerTest}.java`

**Interfaces:**
- Consumes: `JwtService.Claims(userId, orgId, role)` as the principal (`SecurityContextHolder.getContext().getAuthentication().getPrincipal()`); `IdentityFixtures` in tests.
- Produces (used by Task 3):
  - `ApiKeys.PREFIX` = `"ask_live_"`, `ApiKeys.PREFIX_LENGTH` = `12`, `static String generate(SecureRandom)`, `static String hash(String key)` (package-private, package `com.agentstore.platform.identity`).
  - `DeveloperApiKey` getters `getId()`, `getOrgId()`, `getCreatedByUserId()`, `getName()`, `getPrefix()`, `getKeyHash()`, `getCreatedAt()`, `getLastUsedAt()`, `getRevokedAt()`, setter `setRevokedAt(Instant)`.
  - `DeveloperApiKeyRepository`: `Optional<DeveloperApiKey> findByKeyHash(String)`, `List<DeveloperApiKey> findByOrgIdOrderByCreatedAtDesc(UUID)`, `Optional<DeveloperApiKey> findByIdAndOrgId(UUID, UUID)`, `int touch(UUID id, Instant now, Instant threshold)`.
  - HTTP: `POST /api/v1/developer/api-keys {name}` → 201 `{id,name,prefix,key,createdAt}`; `GET` → `[{id,name,prefix,createdAt,lastUsedAt,revokedAt}]`; `DELETE /{id}` → 204 (idempotent; 404 for another org's key).

- [ ] **Step 1: Write the failing unit test for key generation and hashing**

`backend/src/test/java/com/agentstore/platform/identity/ApiKeysTest.java`:

```java
package com.agentstore.platform.identity;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeysTest {

    @Test
    void generatedKeysHaveTheDocumentedShape() {
        String key = ApiKeys.generate(new SecureRandom());
        assertThat(key).startsWith("ask_live_").hasSize(52);
        assertThat(key.substring(ApiKeys.PREFIX.length())).matches("[0-9A-Za-z]{43}");
    }

    @Test
    void generatedKeysDiffer() {
        var random = new SecureRandom();
        assertThat(ApiKeys.generate(random)).isNotEqualTo(ApiKeys.generate(random));
    }

    @Test
    void aLowValueRandomStillPadsToFullLength() {
        var zeros = new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
        };
        assertThat(ApiKeys.generate(zeros)).isEqualTo("ask_live_" + "0".repeat(43));
    }

    @Test
    void hashIsLowerCaseSha256Hex() {
        assertThat(ApiKeys.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && mvn -q test -Dtest=ApiKeysTest`
Expected: FAIL to compile — `cannot find symbol: class ApiKeys`.

- [ ] **Step 3: Implement `ApiKeys`**

`backend/src/main/java/com/agentstore/platform/identity/ApiKeys.java`:

```java
package com.agentstore.platform.identity;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Key format and hashing. The keys are high-entropy, so a plain SHA-256 is enough; no slow hash is needed. */
final class ApiKeys {

    static final String PREFIX = "ask_live_";
    /** What the list endpoint shows so a person can recognise a key without the key itself. */
    static final int PREFIX_LENGTH = 12;

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    /** 62^43 exceeds 2^256, so 43 digits hold any 32-byte value. */
    private static final int BODY_LENGTH = 43;

    private ApiKeys() {
    }

    static String generate(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        BigInteger value = new BigInteger(1, bytes);
        BigInteger base = BigInteger.valueOf(62);
        StringBuilder body = new StringBuilder();
        while (value.signum() > 0) {
            BigInteger[] quotientAndRemainder = value.divideAndRemainder(base);
            body.append(ALPHABET.charAt(quotientAndRemainder[1].intValue()));
            value = quotientAndRemainder[0];
        }
        while (body.length() < BODY_LENGTH) {
            body.append('0');
        }
        return PREFIX + body.reverse();
    }

    static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd backend && mvn -q test -Dtest=ApiKeysTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Write the failing controller integration test**

`backend/src/test/java/com/agentstore/platform/identity/DeveloperApiKeyControllerTest.java`:

```java
package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeveloperApiKeyControllerTest extends AbstractIntegrationTest {

    static final String PATH = "/api/v1/developer/api-keys";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired IdentityFixtures identity;
    @Autowired OrgRepository orgs;
    @Autowired DeveloperApiKeyRepository keys;
    @Autowired JwtService jwtService;

    record Dev(Org org, String token) {}

    Dev developer() {
        Org org = orgs.save(new Org(null, "Dev " + UUID.randomUUID(), OrgType.DEVELOPER));
        OrgMembership m = identity.member(org, "dev-" + UUID.randomUUID() + "@example.com", UserRole.DEVELOPER, "Dev");
        return new Dev(org, identity.tokenFor(m));
    }

    <T> ResponseEntity<T> call(HttpMethod method, String path, String token, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, headers), type);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> create(Dev dev, String name) {
        var response = call(HttpMethod.POST, PATH, dev.token(), Map.of("name", name), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    @Test
    void createShowsTheKeyOnceAndStoresOnlyItsHash() {
        Dev dev = developer();
        Map<String, Object> created = create(dev, "laptop");

        String key = (String) created.get("key");
        assertThat(key).startsWith("ask_live_").hasSize(52);
        assertThat(created.get("prefix")).isEqualTo(key.substring(0, 12));

        DeveloperApiKey row = keys.findByKeyHash(ApiKeys.hash(key)).orElseThrow();
        assertThat(row.getOrgId()).isEqualTo(dev.org().getId());
        assertThat(row.getKeyHash()).hasSize(64).isNotEqualTo(key);

        var listed = call(HttpMethod.GET, PATH, dev.token(), null, List.class);
        assertThat(listed.getBody()).hasSize(1);
        Map<?, ?> item = (Map<?, ?>) listed.getBody().get(0);
        assertThat(item.containsKey("key")).isFalse();
        assertThat(item.containsKey("keyHash")).isFalse();
        assertThat(item.get("name")).isEqualTo("laptop");
        assertThat(item.get("revokedAt")).isNull();
        assertThat(listed.getBody().toString()).doesNotContain(key);
    }

    @Test
    void listShowsOnlyTheCallersOrg() {
        Dev mine = developer();
        Dev theirs = developer();
        create(mine, "mine");
        create(theirs, "theirs");

        var listed = call(HttpMethod.GET, PATH, mine.token(), null, List.class);
        assertThat(listed.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) listed.getBody().get(0)).get("name")).isEqualTo("mine");
    }

    @Test
    void revokeMarksTheKeyAndIsIdempotent() {
        Dev dev = developer();
        String id = (String) create(dev, "ci").get("id");

        assertThat(call(HttpMethod.DELETE, PATH + "/" + id, dev.token(), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        var firstRevokedAt = keys.findById(UUID.fromString(id)).orElseThrow().getRevokedAt();
        assertThat(firstRevokedAt).isNotNull();

        assertThat(call(HttpMethod.DELETE, PATH + "/" + id, dev.token(), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(keys.findById(UUID.fromString(id)).orElseThrow().getRevokedAt()).isEqualTo(firstRevokedAt);
    }

    @Test
    void revokingAnotherOrgsKeyIsNotFound() {
        Dev owner = developer();
        Dev intruder = developer();
        String id = (String) create(owner, "ci").get("id");

        assertThat(call(HttpMethod.DELETE, PATH + "/" + id, intruder.token(), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(keys.findById(UUID.fromString(id)).orElseThrow().getRevokedAt()).isNull();
    }

    @Test
    void aBlankNameIsRejected() {
        Dev dev = developer();
        assertThat(call(HttpMethod.POST, PATH, dev.token(), Map.of("name", "  "), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aNameOverOneHundredCharactersIsRejected() {
        Dev dev = developer();
        assertThat(call(HttpMethod.POST, PATH, dev.token(), Map.of("name", "x".repeat(101)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void onlyDevelopersCanManageKeys() {
        Dev dev = developer();
        String buyer = identity.tokenFor(identity.buyerAdmin("buyer-" + UUID.randomUUID() + "@example.com"));
        String admin = jwtService.issue(UUID.randomUUID(), dev.org().getId(), UserRole.ADMIN);

        assertThat(call(HttpMethod.POST, PATH, buyer, Map.of("name", "x"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, PATH, admin, Map.of("name", "x"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, PATH, null, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `cd backend && mvn -q test -Dtest=DeveloperApiKeyControllerTest`
Expected: FAIL to compile — `cannot find symbol: class DeveloperApiKey` / `DeveloperApiKeyRepository`.

- [ ] **Step 7: Write the migration**

`backend/src/main/resources/db/migration/V24__developer_api_key.sql`:

```sql
-- WS5c (spec §2). key_hash is the SHA-256 of the whole key; the plaintext is shown once and never stored.
CREATE TABLE developer_api_key (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL REFERENCES org(id),
    created_by_user_id UUID NOT NULL REFERENCES app_user(id),
    name VARCHAR(100) NOT NULL,
    prefix VARCHAR(12) NOT NULL,
    key_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX developer_api_key_hash_idx ON developer_api_key (key_hash);
CREATE INDEX developer_api_key_org_idx ON developer_api_key (org_id, created_at);
```

- [ ] **Step 8: Write the entity and repository**

`backend/src/main/java/com/agentstore/platform/identity/DeveloperApiKey.java`:

```java
package com.agentstore.platform.identity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One API key. Only the hash of the key is kept; {@code prefix} is what the portal lists. */
@Entity
@Table(name = "developer_api_key")
@Getter
@Setter
@NoArgsConstructor
public class DeveloperApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID orgId;
    private UUID createdByUserId;
    private String name;
    private String prefix;
    private String keyHash;
    private Instant createdAt;
    private Instant lastUsedAt;
    private Instant revokedAt;

    public DeveloperApiKey(UUID orgId, UUID createdByUserId, String name, String prefix, String keyHash) {
        this.orgId = orgId;
        this.createdByUserId = createdByUserId;
        this.name = name;
        this.prefix = prefix;
        this.keyHash = keyHash;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`backend/src/main/java/com/agentstore/platform/identity/DeveloperApiKeyRepository.java`:

```java
package com.agentstore.platform.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeveloperApiKeyRepository extends JpaRepository<DeveloperApiKey, UUID> {

    Optional<DeveloperApiKey> findByKeyHash(String keyHash);

    List<DeveloperApiKey> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    Optional<DeveloperApiKey> findByIdAndOrgId(UUID id, UUID orgId);

    /** Records a use at most once per throttle window: the row is only written when the last use is older than {@code threshold}. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update DeveloperApiKey k set k.lastUsedAt = :now "
            + "where k.id = :id and (k.lastUsedAt is null or k.lastUsedAt < :threshold)")
    int touch(@Param("id") UUID id, @Param("now") Instant now, @Param("threshold") Instant threshold);
}
```

- [ ] **Step 9: Write the service**

`backend/src/main/java/com/agentstore/platform/identity/DeveloperApiKeyService.java`:

```java
package com.agentstore.platform.identity;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DeveloperApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeveloperApiKeyRepository keys;

    public DeveloperApiKeyService(DeveloperApiKeyRepository keys) {
        this.keys = keys;
    }

    /** The only place the plaintext key exists. It must never be logged, so toString hides it. */
    public record Created(UUID id, String name, String prefix, String key, Instant createdAt) {
        @Override
        public String toString() {
            return "Created[id=" + id + ", name=" + name + ", prefix=" + prefix + "]";
        }
    }

    public record Summary(UUID id, String name, String prefix, Instant createdAt, Instant lastUsedAt, Instant revokedAt) {
        static Summary from(DeveloperApiKey k) {
            return new Summary(k.getId(), k.getName(), k.getPrefix(), k.getCreatedAt(), k.getLastUsedAt(), k.getRevokedAt());
        }
    }

    @Transactional
    public Created create(UUID orgId, UUID userId, String name) {
        String key = ApiKeys.generate(RANDOM);
        DeveloperApiKey saved = keys.save(new DeveloperApiKey(orgId, userId, name.trim(),
                key.substring(0, ApiKeys.PREFIX_LENGTH), ApiKeys.hash(key)));
        return new Created(saved.getId(), saved.getName(), saved.getPrefix(), key, saved.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<Summary> list(UUID orgId) {
        return keys.findByOrgIdOrderByCreatedAtDesc(orgId).stream().map(Summary::from).toList();
    }

    @Transactional
    public void revoke(UUID orgId, UUID id) {
        DeveloperApiKey key = keys.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API key not found"));
        if (key.getRevokedAt() == null) {
            key.setRevokedAt(Instant.now());
        }
    }
}
```

- [ ] **Step 10: Write the controller**

`backend/src/main/java/com/agentstore/platform/identity/DeveloperApiKeyController.java`:

```java
package com.agentstore.platform.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Key management is JWT-only: ApiKeyScope keeps a key itself away from these endpoints. */
@RestController
@RequestMapping("/api/v1/developer/api-keys")
public class DeveloperApiKeyController {

    private final DeveloperApiKeyService service;

    public DeveloperApiKeyController(DeveloperApiKeyService service) {
        this.service = service;
    }

    public record CreateRequest(@NotBlank @Size(max = 100) String name) {}

    @PostMapping
    @PreAuthorize("hasRole('DEVELOPER')")
    @ResponseStatus(HttpStatus.CREATED)
    public DeveloperApiKeyService.Created create(@Valid @RequestBody CreateRequest request) {
        var claims = claims();
        return service.create(claims.orgId(), claims.userId(), request.name());
    }

    @GetMapping
    @PreAuthorize("hasRole('DEVELOPER')")
    public List<DeveloperApiKeyService.Summary> list() {
        return service.list(claims().orgId());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('DEVELOPER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id) {
        service.revoke(claims().orgId(), id);
    }

    private static JwtService.Claims claims() {
        return (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
```

- [ ] **Step 11: Run the tests to verify they pass**

Run: `cd backend && mvn -q test -Dtest='ApiKeysTest,DeveloperApiKeyControllerTest'`
Expected: PASS (4 + 7 tests). If Hibernate schema validation reports a column mismatch, compare the entity field names to the migration (Spring's naming strategy maps `keyHash` to `key_hash`).

- [ ] **Step 12: Commit**

```bash
git add backend/src/main/resources/db/migration/V24__developer_api_key.sql \
        backend/src/main/java/com/agentstore/platform/identity/{ApiKeys,DeveloperApiKey,DeveloperApiKeyRepository,DeveloperApiKeyService,DeveloperApiKeyController}.java \
        backend/src/test/java/com/agentstore/platform/identity/{ApiKeysTest,DeveloperApiKeyControllerTest}.java
git commit -m "feat(identity): developer API keys with create, list and revoke endpoints"
```

---

### Task 2: Drop `imageRef` from version creation

The version endpoint keeps working but no longer accepts a hand-supplied image: `finalize` already clears it, and a successful build is the only legitimate source. This runs before the key filter so the key tests can call the version endpoint without an image.

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/catalog/CreateAgentVersionRequest.java`
- Modify: `backend/src/main/java/com/agentstore/platform/catalog/CatalogController.java:60-66`
- Modify: `backend/src/test/java/com/agentstore/platform/catalog/CreateAgentVersionTest.java`, `backend/src/test/java/com/agentstore/platform/EndToEndTierATest.java`, `backend/src/test/java/com/agentstore/platform/EndToEndTierBTest.java`
- Modify: `frontend/e2e/seed.ts`

**Interfaces:**
- Produces (used by Tasks 3 and 9): `new CreateAgentVersionRequest(String versionNumber, String manifestJson, String changelog)`; `POST /api/v1/agents/{id}/versions` creates a DRAFT with `imageRef = null`.

- [ ] **Step 1: Change the test first**

In `CreateAgentVersionTest.java`, rename `createsDraftVersionWithValidManifest` to `createsAnImagelessDraftVersionWithValidManifest`, change all three constructor calls, and change the final assertion:

```java
        var request = new HttpEntity<>(new CreateAgentVersionRequest("1.0.0", manifest, "first cut"),
                authHeaders(developerOrg.getId()));
        ...
        var saved = agentVersionRepository.findById(response.getBody().id()).orElseThrow();
        assertThat(saved.getImageRef()).isNull();
```

The other two calls become `new CreateAgentVersionRequest("1.0.0", manifest, "cl")`.

Add one more test at the bottom of the class, pinning that a stray `imageRef` in the JSON is ignored rather than stored:

```java
    @Test
    void ignoresAnImageRefInTheRequestBody() throws Exception {
        Org developerOrg = orgRepository.save(new Org(null, "DevCo3", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developerOrg, "Legacy Client", "x", "y"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("versionNumber", "1.0.0");
        body.put("manifestJson", manifest);
        body.put("imageRef", "hand-built-template");
        body.put("changelog", "cl");

        var response = rest.postForEntity(url("/api/v1/agents/" + agent.getId() + "/versions"),
                new HttpEntity<>(body, authHeaders(developerOrg.getId())), AgentVersionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(agentVersionRepository.findById(response.getBody().id()).orElseThrow().getImageRef()).isNull();
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && mvn -q test -Dtest=CreateAgentVersionTest`
Expected: FAIL to compile (the record still has four components).

- [ ] **Step 3: Change the request record and the controller**

`CreateAgentVersionRequest.java`:

```java
package com.agentstore.platform.catalog;

import jakarta.validation.constraints.NotBlank;

/** No image: a version's image comes only from a successful build of a pushed bundle (WS5b/WS5c). */
public record CreateAgentVersionRequest(@NotBlank String versionNumber, @NotBlank String manifestJson, String changelog) {
}
```

`CatalogController.java`, in `createVersion`:

```java
        AgentVersion version = new AgentVersion(null, agent, request.versionNumber(), request.manifestJson(),
                null, VersionStatus.DRAFT, null, request.changelog());
```

- [ ] **Step 4: Fix the two end-to-end tests**

They submit a version, and submit requires an image. In `EndToEndTierATest.java` and `EndToEndTierBTest.java`:

1. Add next to the other `@Autowired` fields: `@Autowired AgentVersionRepository agentVersionRepository;` (the catalog package is already imported through the request classes; add `import com.agentstore.platform.catalog.AgentVersionRepository;` if the file imports individual classes).
2. Change the request to three arguments and set the image directly, before the `/submit` call.

Tier A:

```java
        var version = rest.postForEntity(url("/api/v1/agents/" + agent.id() + "/versions"),
                new HttpEntity<>(new CreateAgentVersionRequest("1.0.0", manifest, "first cut"),
                        bearer(devLogin.token())),
                AgentVersionResponse.class).getBody();
        var stored = agentVersionRepository.findById(version.id()).orElseThrow();
        stored.setImageRef("bank-recon-template-v1");
        agentVersionRepository.save(stored);
```

Tier B is identical with `"cl"` and `"collections-template-v1"`.

- [ ] **Step 5: Fix the frontend e2e seed**

`frontend/e2e/seed.ts`: the seed submits a version created with `imageRef`. Move the database connection above version creation and set the image in SQL. Replace the block from `const version = (await fetch(` through the submit `fetch(...)` with:

```ts
  const pg = new Client({ connectionString: DATABASE_URL });
  await pg.connect();

  const version = (await fetch(`${BACKEND_URL}/api/v1/agents/${agent.id}/versions`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${dev.token}` },
    body: JSON.stringify({ versionNumber: "1.0.0", manifestJson: JSON.stringify(manifest), changelog: "seed" }),
  }).then((r) => r.json())) as { id: string };

  // A version's image only comes from a build now; the seed has no bundle, so it sets the image directly.
  await pg.query("update agent_version set image_ref = $1 where id = $2", ["e2e-template", version.id]);

  await fetch(`${BACKEND_URL}/api/v1/agents/${agent.id}/versions/${version.id}/submit`, {
    method: "POST",
    headers: { Authorization: `Bearer ${dev.token}` },
  });
```

and delete the now-duplicate `const pg = new Client(...)` / `await pg.connect();` lines that followed the submit call.

- [ ] **Step 6: Run the affected backend tests**

Run: `cd backend && mvn -q test -Dtest='CreateAgentVersionTest,EndToEndTierATest,EndToEndTierBTest,CatalogControllerTest'`
Expected: PASS. Then `grep -rn "imageRef" backend/src/main/java/com/agentstore/platform/catalog/CatalogController.java` should show nothing.

- [ ] **Step 7: Commit**

```bash
git add backend frontend/e2e/seed.ts
git commit -m "refactor(catalog): version creation no longer accepts a hand-supplied imageRef"
```

---

### Task 3: `ApiKeyAuthFilter` and the key allowlist

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/identity/{ApiKeyScope,ApiKeyAuthFilter}.java`
- Modify: `backend/src/main/java/com/agentstore/platform/identity/SecurityConfig.java`
- Test: `backend/src/test/java/com/agentstore/platform/identity/{ApiKeyScopeTest,ApiKeyAuthTest}.java`

**Interfaces:**
- Consumes: `ApiKeys.PREFIX`, `ApiKeys.hash`, `DeveloperApiKeyRepository.findByKeyHash/touch` (Task 1); `OrgMembershipRepository.findById(new OrgMembershipId(userId, orgId))`; `JwtService.Claims`.
- Produces: authentication of `Authorization: Bearer ask_live_…` as `Claims(createdByUserId, orgId, DEVELOPER)` with authorities `ROLE_DEVELOPER` and `AUTH_API_KEY`; 403 for a valid key on a path outside the allowlist; 401 (unauthenticated fall-through) for unknown, revoked, or orphaned keys.

- [ ] **Step 1: Write the failing allowlist unit test**

`backend/src/test/java/com/agentstore/platform/identity/ApiKeyScopeTest.java`:

```java
package com.agentstore.platform.identity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyScopeTest {

    @ParameterizedTest
    @CsvSource({
            "POST, /api/v1/agents",
            "GET, /api/v1/agents/mine",
            "POST, /api/v1/agents/a1/versions",
            "GET, /api/v1/agents/a1/versions/v1",
            "POST, /api/v1/agents/a1/bundles/uploads",
            "POST, /api/v1/agents/a1/bundles/b1/finalize",
            "GET, /api/v1/agents/a1/builds/b1",
    })
    void allowsTheDeveloperEndpoints(String method, String path) {
        assertThat(ApiKeyScope.allows(method, path)).as(method + " " + path).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/v1/agents/a1/versions/v1/submit",
            "POST, /api/v1/agents/a1/versions/v1/submit/extra",
            "GET, /api/v1/agents",
            "DELETE, /api/v1/agents",
            "POST, /api/v1/agents/mine",
            "GET, /api/v1/developer/api-keys",
            "POST, /api/v1/developer/api-keys",
            "DELETE, /api/v1/developer/api-keys/k1",
            "GET, /api/v1/orgs/o1/wallet",
            "GET, /api/v1/orgs/o1/members",
            "GET, /api/v1/me",
            "GET, /api/v1/review/queue",
            "POST, /api/v1/runs",
            "GET, /api/v1/catalog",
            "POST, /api/v1/auth/login",
            "GET, /api/v1/agents/a1",
            "GET, /internal/agent-callback/runs/r1/gate-status",
            "GET, /actuator/health",
    })
    void deniesEverythingElse(String method, String path) {
        assertThat(ApiKeyScope.allows(method, path)).as(method + " " + path).isFalse();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && mvn -q test -Dtest=ApiKeyScopeTest`
Expected: FAIL to compile — `cannot find symbol: class ApiKeyScope`. (If `junit-jupiter-params` is missing, the compile error names `ParameterizedTest`; it ships with `spring-boot-starter-test`, so it should be present.)

- [ ] **Step 3: Implement `ApiKeyScope`**

`backend/src/main/java/com/agentstore/platform/identity/ApiKeyScope.java`:

```java
package com.agentstore.platform.identity;

import org.springframework.util.AntPathMatcher;

import java.util.List;

/**
 * What an API key may call. Deny by default: a new endpoint is unreachable by keys until it is added here, which is
 * a deliberate edit to this one file. Submitting for review is a human decision (WS8), so it is denied explicitly
 * even though it sits under the versions path.
 */
final class ApiKeyScope {

    private record Rule(String method, String pattern) {}

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private static final List<String> DENIED = List.of(
            "/api/v1/agents/*/versions/*/submit",
            "/api/v1/agents/*/versions/*/submit/**");

    private static final List<Rule> ALLOWED = List.of(
            new Rule("POST", "/api/v1/agents"),
            new Rule("GET", "/api/v1/agents/mine"),
            new Rule(null, "/api/v1/agents/*/versions"),
            new Rule(null, "/api/v1/agents/*/versions/**"),
            new Rule(null, "/api/v1/agents/*/bundles/**"),
            new Rule(null, "/api/v1/agents/*/builds/**"));

    private ApiKeyScope() {
    }

    static boolean allows(String method, String path) {
        for (String denied : DENIED) {
            if (MATCHER.match(denied, path)) {
                return false;
            }
        }
        return ALLOWED.stream().anyMatch(rule ->
                (rule.method() == null || rule.method().equalsIgnoreCase(method)) && MATCHER.match(rule.pattern(), path));
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && mvn -q test -Dtest=ApiKeyScopeTest`
Expected: PASS (25 cases).

- [ ] **Step 5: Write the failing integration test**

`backend/src/test/java/com/agentstore/platform/identity/ApiKeyAuthTest.java`:

```java
package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.catalog.AgentVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthTest extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired IdentityFixtures identity;
    @Autowired OrgRepository orgs;
    @Autowired AgentRepository agents;
    @Autowired AgentVersionRepository versions;
    @Autowired DeveloperApiKeyRepository keys;
    @Autowired OrgMembershipRepository memberships;

    record Dev(Org org, OrgMembership membership, String jwt, String key) {}

    <T> ResponseEntity<T> call(HttpMethod method, String path, String bearer, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) headers.setBearerAuth(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, headers), type);
    }

    @SuppressWarnings("unchecked")
    Dev developer() {
        Org org = orgs.save(new Org(null, "Dev " + UUID.randomUUID(), OrgType.DEVELOPER));
        OrgMembership m = identity.member(org, "dev-" + UUID.randomUUID() + "@example.com", UserRole.DEVELOPER, "Dev");
        String jwt = identity.tokenFor(m);
        var created = call(HttpMethod.POST, "/api/v1/developer/api-keys", jwt, Map.of("name", "ci"), Map.class);
        return new Dev(org, m, jwt, (String) created.getBody().get("key"));
    }

    HttpStatusCode status(HttpMethod method, String path, String bearer, Object body) {
        return call(method, path, bearer, body, String.class).getStatusCode();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aKeyListsOnlyItsOwnOrgsAgents() {
        Dev mine = developer();
        Dev theirs = developer();
        agents.save(new Agent(null, mine.org(), "Mine", "finance", "d"));
        agents.save(new Agent(null, theirs.org(), "Theirs", "finance", "d"));

        var response = call(HttpMethod.GET, "/api/v1/agents/mine", mine.key(), null, List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) response.getBody().get(0)).get("agentName")).isEqualTo("Mine");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aKeyCreatesAnAgentAndAnImagelessDraftVersionInItsOwnOrg() throws Exception {
        Dev dev = developer();
        var agent = call(HttpMethod.POST, "/api/v1/agents", dev.key(),
                Map.of("name", "Recon", "category", "finance", "description", "d"), Map.class);
        assertThat(agent.getStatusCode()).isEqualTo(HttpStatus.OK);
        String agentId = (String) agent.getBody().get("id");
        assertThat(agents.findById(UUID.fromString(agentId)).orElseThrow().getDeveloperOrg().getId())
                .isEqualTo(dev.org().getId());

        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        var version = call(HttpMethod.POST, "/api/v1/agents/" + agentId + "/versions", dev.key(),
                Map.of("versionNumber", "1.0.0", "manifestJson", manifest, "changelog", "c"), Map.class);

        assertThat(version.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(versions.findById(UUID.fromString((String) version.getBody().get("id"))).orElseThrow().getImageRef())
                .isNull();
    }

    @Test
    void aKeyCannotTouchAnotherOrgsAgent() throws Exception {
        Dev mine = developer();
        Dev theirs = developer();
        Agent theirAgent = agents.save(new Agent(null, theirs.org(), "Theirs", "finance", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));

        assertThat(status(HttpMethod.POST, "/api/v1/agents/" + theirAgent.getId() + "/versions", mine.key(),
                Map.of("versionNumber", "1.0.0", "manifestJson", manifest, "changelog", "c")))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aKeyIsForbiddenEverywhereOutsideTheAllowlistWhileAJwtIsNot() {
        Dev dev = developer();
        UUID orgId = dev.org().getId();

        // The same endpoints work with the developer's JWT, so the 403 is the key scope and not a role check.
        assertThat(status(HttpMethod.GET, "/api/v1/orgs/" + orgId + "/members", dev.jwt(), null)).isEqualTo(HttpStatus.OK);
        assertThat(status(HttpMethod.GET, "/api/v1/developer/api-keys", dev.jwt(), null)).isEqualTo(HttpStatus.OK);
        assertThat(status(HttpMethod.GET, "/api/v1/me", dev.jwt(), null)).isEqualTo(HttpStatus.OK);

        assertThat(status(HttpMethod.GET, "/api/v1/orgs/" + orgId + "/members", dev.key(), null)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(HttpMethod.GET, "/api/v1/orgs/" + orgId + "/wallet", dev.key(), null)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(HttpMethod.GET, "/api/v1/me", dev.key(), null)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(HttpMethod.GET, "/api/v1/review/queue", dev.key(), null)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aKeyCannotManageKeys() {
        Dev dev = developer();
        assertThat(status(HttpMethod.GET, "/api/v1/developer/api-keys", dev.key(), null)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(HttpMethod.POST, "/api/v1/developer/api-keys", dev.key(), Map.of("name", "sneaky")))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aKeyCannotSubmitForReview() {
        Dev dev = developer();
        Agent agent = agents.save(new Agent(null, dev.org(), "Recon", "finance", "d"));

        assertThat(status(HttpMethod.POST,
                "/api/v1/agents/" + agent.getId() + "/versions/" + UUID.randomUUID() + "/submit", dev.key(), null))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unknownAndRevokedKeysAreUnauthorized() {
        Dev dev = developer();
        String unknown = "ask_live_" + "0".repeat(43);
        assertThat(status(HttpMethod.GET, "/api/v1/agents/mine", unknown, null)).isEqualTo(HttpStatus.UNAUTHORIZED);

        var row = keys.findByKeyHash(ApiKeys.hash(dev.key())).orElseThrow();
        row.setRevokedAt(Instant.now());
        keys.save(row);
        assertThat(status(HttpMethod.GET, "/api/v1/agents/mine", dev.key(), null)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aKeyStopsWorkingWhenItsCreatorLeavesTheOrg() {
        Dev dev = developer();
        assertThat(status(HttpMethod.GET, "/api/v1/agents/mine", dev.key(), null)).isEqualTo(HttpStatus.OK);

        memberships.deleteById(dev.membership().getId());

        assertThat(status(HttpMethod.GET, "/api/v1/agents/mine", dev.key(), null)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void lastUsedIsRecordedAndThrottled() {
        Dev dev = developer();
        String hash = ApiKeys.hash(dev.key());
        assertThat(keys.findByKeyHash(hash).orElseThrow().getLastUsedAt()).isNull();

        status(HttpMethod.GET, "/api/v1/agents/mine", dev.key(), null);
        Instant first = keys.findByKeyHash(hash).orElseThrow().getLastUsedAt();
        assertThat(first).isNotNull();

        status(HttpMethod.GET, "/api/v1/agents/mine", dev.key(), null);
        assertThat(keys.findByKeyHash(hash).orElseThrow().getLastUsedAt()).isEqualTo(first);
    }

    @Test
    void aJwtStillWorksAlongsideTheKeyFilter() {
        Dev dev = developer();
        assertThat(status(HttpMethod.GET, "/api/v1/agents/mine", dev.jwt(), null)).isEqualTo(HttpStatus.OK);
    }
}
```

- [ ] **Step 6: Run to verify it fails**

Run: `cd backend && mvn -q test -Dtest=ApiKeyAuthTest`
Expected: FAIL — keys get 401 on `/api/v1/agents/mine` (nothing authenticates them yet), so the first test fails on status.

- [ ] **Step 7: Implement `ApiKeyAuthFilter`**

`backend/src/main/java/com/agentstore/platform/identity/ApiKeyAuthFilter.java`:

```java
package com.agentstore.platform.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates {@code Authorization: Bearer ask_live_…} as the key's creator inside the key's org, with the role
 * fixed at DEVELOPER, so no controller needs to know about keys. A valid key on a path outside {@link ApiKeyScope}
 * is answered with 403 right here, which also covers the permitAll paths that {@code authorizeHttpRequests} never
 * checks. Anything else about the key being wrong leaves the request unauthenticated (401).
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    static final String AUTHORITY = "AUTH_API_KEY";
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final DeveloperApiKeyRepository keys;
    private final OrgMembershipRepository memberships;

    public ApiKeyAuthFilter(DeveloperApiKeyRepository keys, OrgMembershipRepository memberships) {
        this.keys = keys;
        this.memberships = memberships;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer " + ApiKeys.PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        Optional<DeveloperApiKey> key = keys.findByKeyHash(ApiKeys.hash(header.substring("Bearer ".length())))
                .filter(k -> k.getRevokedAt() == null)
                .filter(this::creatorStillDevelopsForTheOrg);
        if (key.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (!ApiKeyScope.allows(request.getMethod(), path)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "API keys cannot be used on this endpoint");
            return;
        }
        DeveloperApiKey k = key.get();
        var claims = new JwtService.Claims(k.getCreatedByUserId(), k.getOrgId(), UserRole.DEVELOPER);
        var auth = new UsernamePasswordAuthenticationToken(claims, null,
                List.of(new SimpleGrantedAuthority("ROLE_DEVELOPER"), new SimpleGrantedAuthority(AUTHORITY)));
        SecurityContextHolder.getContext().setAuthentication(auth);
        Instant now = Instant.now();
        keys.touch(k.getId(), now, now.minus(TOUCH_INTERVAL));
        chain.doFilter(request, response);
    }

    private boolean creatorStillDevelopsForTheOrg(DeveloperApiKey key) {
        return memberships.findById(new OrgMembershipId(key.getCreatedByUserId(), key.getOrgId()))
                .filter(m -> m.getRole() == UserRole.DEVELOPER)
                .isPresent();
    }
}
```

- [ ] **Step 8: Register the filter in `SecurityConfig`**

`SecurityConfig.java`: inject it and add it next to the JWT filter.

```java
    private final JwtAuthFilter jwtAuthFilter;
    private final ApiKeyAuthFilter apiKeyAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, ApiKeyAuthFilter apiKeyAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.apiKeyAuthFilter = apiKeyAuthFilter;
    }
```

and change the end of the chain to:

```java
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
```

(The two filters ignore each other's tokens: the JWT filter's parse of an `ask_live_…` string fails harmlessly and the key filter returns early for anything that is not a key.)

- [ ] **Step 9: Run the tests to verify they pass**

Run: `cd backend && mvn -q test -Dtest='ApiKeyScopeTest,ApiKeyAuthTest,DeveloperApiKeyControllerTest,SecurityConfigTest'`
Expected: PASS. If `aKeyIsForbiddenEverywhere…` fails on `/api/v1/me` with the JWT, check the `MeController` mapping (`GET /api/v1/me`); if `/api/v1/orgs/{id}/wallet` with a key returns 404 rather than 403, the filter is not rejecting before dispatch — recheck Step 7's scope check runs before `chain.doFilter`.

- [ ] **Step 10: Run the whole backend suite**

Run: `cd backend && mvn -q test`
Expected: PASS. Any failure here is a regression from the filter or from Task 2; fix it before committing.

- [ ] **Step 11: Commit**

```bash
git add backend/src
git commit -m "feat(identity): authenticate API keys and confine them to developer endpoints"
```

---

### Task 4: CLI skeleton, credentials, HTTP client and `login`

**Files:**
- Create: `cli/package.json`, `cli/tsconfig.json`, `cli/tsconfig.build.json`, `cli/vitest.config.ts`, `cli/.gitignore`, `cli/scripts/sync-schema.mjs`
- Create: `cli/src/{bin,cli,context,errors,config,api}.ts`, `cli/src/commands/login.ts`
- Test: `cli/test/{helpers,config,api,login,cli}.test.ts` (`helpers.ts` is not a test file)

**Interfaces:**
- Consumes: `GET /api/v1/agents/mine` (allowed for keys, Task 3).
- Produces (used by Tasks 5–7):
  - `CliError(message, exitCode: 1 | 2 = 2)`.
  - `Context { cwd, env, home, out(line), err(line), ask(question, fallback?), readSecret(question), sleep(ms), now(), fetch }`.
  - `Credentials { apiKey, apiUrl }`; `DEFAULT_API_URL`; `credentialsPath(home)`; `loadCredentials(ctx): Credentials | null`; `requireCredentials(ctx): Credentials`; `saveCredentials(ctx, creds): void`.
  - `class ApiClient(creds, fetchFn)` with `get<T>(path)`, `post<T>(path, body?)`, `putBytes(url, bytes)`, `getText(url): Promise<string | null>`.
  - `main(argv: string[], ctx: Context): Promise<number>` in `src/cli.ts`.
  - Test helpers: `tempDir()`, `makeContext(opts)`, `startServer(routes)`, `writeFiles(dir, files)`, `AGENT_ID`.

- [ ] **Step 1: Create the package and install dependencies**

```bash
cd /Users/amittiwari/AgentStore
mkdir -p cli/src/commands cli/src/schema cli/test cli/scripts
cd cli
cat > package.json <<'EOF'
{
  "name": "agentstore-cli",
  "version": "0.1.0",
  "private": true,
  "description": "Command-line client for the AgentStore developer API",
  "type": "module",
  "bin": { "agentstore": "dist/bin.js" },
  "engines": { "node": ">=22" },
  "scripts": {
    "sync-schema": "node scripts/sync-schema.mjs",
    "build": "npm run sync-schema && tsc -p tsconfig.build.json && chmod +x dist/bin.js",
    "typecheck": "tsc --noEmit",
    "test": "vitest run"
  }
}
EOF
npm install ajv fflate ignore yaml
npm install -D typescript vitest @types/node
```

Create the config files:

`cli/tsconfig.json` (typechecks sources and tests, emits nothing):

```json
{
  "compilerOptions": {
    "target": "ES2023",
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "resolveJsonModule": true,
    "types": ["node"],
    "noEmit": true
  },
  "include": ["src", "test"]
}
```

`cli/tsconfig.build.json`:

```json
{
  "extends": "./tsconfig.json",
  "compilerOptions": { "noEmit": false, "rootDir": "src", "outDir": "dist" },
  "include": ["src"]
}
```

`cli/vitest.config.ts`:

```ts
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: { environment: "node", include: ["test/**/*.test.ts"], testTimeout: 15000 },
});
```

`cli/.gitignore`:

```
node_modules/
dist/
```

`cli/scripts/sync-schema.mjs` (the backend's schema is the source of truth; the CLI bundles a copy):

```js
import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const from = join(here, "..", "..", "backend", "src", "main", "resources", "manifest", "manifest-schema.json");
const to = join(here, "..", "src", "schema", "manifest-schema.json");
mkdirSync(dirname(to), { recursive: true });
copyFileSync(from, to);
console.log(`copied ${from} -> ${to}`);
```

Run `npm run sync-schema` once now so Task 5 has the file.

- [ ] **Step 2: Write the test helpers**

`cli/test/helpers.ts`:

```ts
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { createServer, type IncomingMessage } from "node:http";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { afterEach } from "vitest";
import type { Context } from "../src/context.js";

export const AGENT_ID = "11111111-2222-4333-8444-555555555555";

const cleanups: Array<() => Promise<void> | void> = [];

afterEach(async () => {
  while (cleanups.length > 0) {
    await cleanups.pop()!();
  }
});

export function tempDir(): string {
  const dir = mkdtempSync(join(tmpdir(), "agentstore-cli-"));
  cleanups.push(() => rmSync(dir, { recursive: true, force: true }));
  return dir;
}

export function writeFiles(dir: string, files: Record<string, string>): void {
  for (const [path, content] of Object.entries(files)) {
    const full = join(dir, path);
    mkdirSync(dirname(full), { recursive: true });
    writeFileSync(full, content);
  }
}

export interface Recorded {
  method: string;
  path: string;
  headers: IncomingMessage["headers"];
  body: Buffer;
}

export interface Reply {
  status?: number;
  json?: unknown;
  text?: string;
}

export interface Route {
  method: string;
  path: string | RegExp;
  reply: Reply | ((request: Recorded) => Reply);
}

/** A real HTTP server on a random port. Unmatched requests get 404. Every request is recorded. */
export async function startServer(routes: Route[]): Promise<{ url: string; requests: Recorded[] }> {
  const requests: Recorded[] = [];
  const server = createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => {
      const recorded: Recorded = {
        method: req.method ?? "",
        path: req.url ?? "",
        headers: req.headers,
        body: Buffer.concat(chunks),
      };
      requests.push(recorded);
      const route = routes.find((r) =>
        r.method === recorded.method && (typeof r.path === "string" ? r.path === recorded.path : r.path.test(recorded.path)));
      if (!route) {
        res.writeHead(404).end();
        return;
      }
      const reply = typeof route.reply === "function" ? route.reply(recorded) : route.reply;
      res.writeHead(reply.status ?? 200, { "Content-Type": reply.json !== undefined ? "application/json" : "text/plain" });
      res.end(reply.json !== undefined ? JSON.stringify(reply.json) : (reply.text ?? ""));
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  cleanups.push(() => new Promise<void>((resolve) => server.close(() => resolve())));
  return { url: `http://127.0.0.1:${(server.address() as AddressInfo).port}`, requests };
}

export interface TestContext extends Context {
  outLines: string[];
  errLines: string[];
  sleeps: number[];
  answers: string[];
  secrets: string[];
}

/** A Context with a fake clock (sleep advances it), scripted answers, and an empty environment. */
export function makeContext(opts: {
  cwd?: string;
  home?: string;
  env?: Record<string, string>;
  answers?: string[];
  secrets?: string[];
} = {}): TestContext {
  let clock = 0;
  const ctx: TestContext = {
    cwd: opts.cwd ?? tempDir(),
    home: opts.home ?? tempDir(),
    env: opts.env ?? {},
    out: (line) => { ctx.outLines.push(line); },
    err: (line) => { ctx.errLines.push(line); },
    ask: async (_question, fallback) => ctx.answers.shift() || fallback || "",   // like the real prompt: empty means the default
    readSecret: async () => ctx.secrets.shift() ?? "",
    sleep: async (ms) => { clock += ms; ctx.sleeps.push(ms); },
    now: () => clock,
    fetch: globalThis.fetch,
    outLines: [],
    errLines: [],
    sleeps: [],
    answers: opts.answers ?? [],
    secrets: opts.secrets ?? [],
  };
  return ctx;
}
```

- [ ] **Step 3: Write the failing tests**

`cli/test/config.test.ts`:

```ts
import { chmodSync, mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { describe, expect, it } from "vitest";
import { CliError } from "../src/errors.js";
import { credentialsPath, DEFAULT_API_URL, loadCredentials, requireCredentials, saveCredentials } from "../src/config.js";
import { makeContext } from "./helpers.js";

describe("credentials", () => {
  it("saves JSON in a private file inside a private directory", () => {
    const ctx = makeContext();
    saveCredentials(ctx, { apiKey: "ask_live_abc", apiUrl: "http://localhost:8080/" });

    const file = credentialsPath(ctx.home);
    expect(statSync(file).mode & 0o777).toBe(0o600);
    expect(statSync(dirname(file)).mode & 0o777).toBe(0o700);
    expect(JSON.parse(readFileSync(file, "utf8"))).toEqual({ apiKey: "ask_live_abc", apiUrl: "http://localhost:8080" });
  });

  it("tightens the mode when it overwrites a file that was too open", () => {
    const ctx = makeContext();
    const file = credentialsPath(ctx.home);
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, "{}");
    chmodSync(file, 0o644);

    saveCredentials(ctx, { apiKey: "ask_live_abc", apiUrl: DEFAULT_API_URL });

    expect(statSync(file).mode & 0o777).toBe(0o600);
  });

  it("returns null when there is no file and no environment", () => {
    expect(loadCredentials(makeContext())).toBeNull();
  });

  it("loads the stored key and defaults the URL", () => {
    const ctx = makeContext();
    saveCredentials(ctx, { apiKey: "ask_live_abc", apiUrl: "http://example.test" });
    expect(loadCredentials(ctx)).toEqual({ apiKey: "ask_live_abc", apiUrl: "http://example.test" });

    const file = credentialsPath(ctx.home);
    writeFileSync(file, JSON.stringify({ apiKey: "ask_live_only_key" }));
    expect(loadCredentials(ctx)).toEqual({ apiKey: "ask_live_only_key", apiUrl: DEFAULT_API_URL });
  });

  it("lets the environment override the file", () => {
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_env", AGENTSTORE_API_URL: "http://env.test/" } });
    saveCredentials(ctx, { apiKey: "ask_live_file", apiUrl: "http://file.test" });
    expect(loadCredentials(ctx)).toEqual({ apiKey: "ask_live_env", apiUrl: "http://env.test" });
  });

  it("works from the environment alone", () => {
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_env" } });
    expect(loadCredentials(ctx)).toEqual({ apiKey: "ask_live_env", apiUrl: DEFAULT_API_URL });
  });

  it("warns when the file is readable by other users", () => {
    const ctx = makeContext();
    saveCredentials(ctx, { apiKey: "ask_live_abc", apiUrl: DEFAULT_API_URL });
    chmodSync(credentialsPath(ctx.home), 0o644);

    loadCredentials(ctx);

    expect(ctx.errLines.join("\n")).toContain("chmod 600");
  });

  it("rejects a corrupt file with a readable error", () => {
    const ctx = makeContext();
    const file = credentialsPath(ctx.home);
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, "not json", { mode: 0o600 });
    expect(() => loadCredentials(ctx)).toThrow(CliError);
    expect(() => loadCredentials(ctx)).toThrow(/agentstore login/);
  });

  it("requireCredentials points at login when there is nothing", () => {
    expect(() => requireCredentials(makeContext())).toThrow(/agentstore login/);
  });
});
```

`cli/test/api.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { ApiClient } from "../src/api.js";
import { CliError } from "../src/errors.js";
import { startServer } from "./helpers.js";

const client = (apiUrl: string) => new ApiClient({ apiKey: "ask_live_secret", apiUrl }, globalThis.fetch);

describe("ApiClient", () => {
  it("sends the key as a bearer token and parses JSON", async () => {
    const server = await startServer([{ method: "GET", path: "/api/v1/agents/mine", reply: { json: [{ agentId: "a1" }] } }]);
    expect(await client(server.url).get("/api/v1/agents/mine")).toEqual([{ agentId: "a1" }]);
    expect(server.requests[0].headers.authorization).toBe("Bearer ask_live_secret");
  });

  it("posts a JSON body", async () => {
    const server = await startServer([{ method: "POST", path: "/x", reply: { json: {} } }]);
    await client(server.url).post("/x", { a: 1 });
    expect(JSON.parse(server.requests[0].body.toString())).toEqual({ a: 1 });
    expect(server.requests[0].headers["content-type"]).toContain("application/json");
  });

  it("turns a 401 into a hint to log in again", async () => {
    const server = await startServer([{ method: "GET", path: "/x", reply: { status: 401 } }]);
    await expect(client(server.url).get("/x")).rejects.toThrow(/rejected or revoked.*agentstore login/s);
  });

  it("shows the backend's message verbatim for other failures", async () => {
    const server = await startServer([
      { method: "POST", path: "/x", reply: { status: 422, json: { code: "INVALID_BUNDLE", message: "Entrypoint a.py is not in the bundle" } } },
    ]);
    const error = await client(server.url).post("/x").catch((e) => e);
    expect(error).toBeInstanceOf(CliError);
    expect(error.message).toContain("422");
    expect(error.message).toContain("Entrypoint a.py is not in the bundle");
    expect(error.exitCode).toBe(2);
  });

  it("joins an array of messages and tolerates a non-JSON body", async () => {
    const server = await startServer([
      { method: "GET", path: "/list", reply: { status: 400, json: ["a is bad", "b is bad"] } },
      { method: "GET", path: "/text", reply: { status: 500, text: "boom" } },
      { method: "GET", path: "/empty", reply: { status: 403 } },
    ]);
    await expect(client(server.url).get("/list")).rejects.toThrow("a is bad; b is bad");
    await expect(client(server.url).get("/text")).rejects.toThrow("boom");
    await expect(client(server.url).get("/empty")).rejects.toThrow(/403/);
  });

  it("reports an unreachable server in one line that names the URL", async () => {
    const error = await client("http://127.0.0.1:1").get("/x").catch((e) => e);
    expect(error).toBeInstanceOf(CliError);
    expect(error.message).toContain("Could not reach http://127.0.0.1:1");
    expect(error.message).not.toContain("\n");
    expect(error.exitCode).toBe(2);
  });

  it("uploads to a signed URL without the API key and never echoes the URL", async () => {
    const server = await startServer([
      { method: "PUT", path: /^\/ok/, reply: { status: 200 } },
      { method: "PUT", path: /^\/denied/, reply: { status: 403 } },
    ]);
    await client(server.url).putBytes(`${server.url}/ok?X-Amz-Signature=topsecret`, new Uint8Array([1, 2, 3]));
    expect(server.requests[0].headers.authorization).toBeUndefined();
    expect([...server.requests[0].body]).toEqual([1, 2, 3]);

    const error = await client(server.url).putBytes(`${server.url}/denied?X-Amz-Signature=topsecret`, new Uint8Array([1])).catch((e) => e);
    expect(error.message).toContain("403");
    expect(error.message).not.toContain("topsecret");
  });

  it("reads text from a signed URL, or null when it is not there yet", async () => {
    const server = await startServer([{ method: "GET", path: "/log", reply: { text: "line one\n" } }]);
    expect(await client(server.url).getText(`${server.url}/log`)).toBe("line one\n");
    expect(await client(server.url).getText(`${server.url}/missing`)).toBeNull();
  });
});
```

`cli/test/login.test.ts`:

```ts
import { readFileSync, existsSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { credentialsPath } from "../src/config.js";
import { login } from "../src/commands/login.js";
import { makeContext, startServer } from "./helpers.js";

describe("login", () => {
  it("verifies the key against the API, then saves it", async () => {
    const server = await startServer([{ method: "GET", path: "/api/v1/agents/mine", reply: { json: [] } }]);
    const ctx = makeContext({ secrets: ["ask_live_good"] });

    expect(await login(ctx, { apiUrl: `${server.url}/` })).toBe(0);

    expect(server.requests[0].headers.authorization).toBe("Bearer ask_live_good");
    expect(JSON.parse(readFileSync(credentialsPath(ctx.home), "utf8")))
      .toEqual({ apiKey: "ask_live_good", apiUrl: server.url });
    expect(ctx.outLines.join("\n")).toContain("Logged in");
    expect(ctx.outLines.join("\n")).not.toContain("ask_live_good");
  });

  it("does not save a key the API rejects", async () => {
    const server = await startServer([{ method: "GET", path: "/api/v1/agents/mine", reply: { status: 401 } }]);
    const ctx = makeContext({ secrets: ["ask_live_bad"] });

    await expect(login(ctx, { apiUrl: server.url })).rejects.toThrow(/rejected or revoked/);

    expect(existsSync(credentialsPath(ctx.home))).toBe(false);
  });

  it("refuses text that is not an API key without calling the API", async () => {
    const server = await startServer([]);
    const ctx = makeContext({ secrets: ["hunter2"] });

    await expect(login(ctx, { apiUrl: server.url })).rejects.toThrow(/ask_live_/);

    expect(server.requests).toHaveLength(0);
  });

  it("rejects an invalid --api-url before asking for the key", async () => {
    const ctx = makeContext({ secrets: ["ask_live_x"] });
    await expect(login(ctx, { apiUrl: "not a url" })).rejects.toThrow(/not a valid URL/);
    expect(ctx.secrets).toEqual(["ask_live_x"]);
  });

  it("tells the person where to create a key", async () => {
    const server = await startServer([{ method: "GET", path: "/api/v1/agents/mine", reply: { json: [] } }]);
    const ctx = makeContext({ secrets: ["ask_live_good"], env: { AGENTSTORE_PORTAL_URL: "https://portal.test" } });
    await login(ctx, { apiUrl: server.url });
    expect(ctx.outLines.join("\n")).toContain("https://portal.test/dashboard/api-keys");
  });
});
```

`cli/test/cli.test.ts` (the dispatcher; later tasks add cases):

```ts
import { describe, expect, it } from "vitest";
import { main } from "../src/cli.js";
import { makeContext } from "./helpers.js";

describe("main", () => {
  it("prints usage and exits 2 when no command is given", async () => {
    const ctx = makeContext();
    expect(await main([], ctx)).toBe(2);
    expect(ctx.errLines.join("\n")).toContain("Usage: agentstore");
  });

  it("prints usage and exits 0 for help", async () => {
    const ctx = makeContext();
    expect(await main(["--help"], ctx)).toBe(0);
    expect(ctx.outLines.join("\n")).toContain("push");
  });

  it("rejects an unknown command with exit 2", async () => {
    const ctx = makeContext();
    expect(await main(["frobnicate"], ctx)).toBe(2);
    expect(ctx.errLines.join("\n")).toContain('Unknown command "frobnicate"');
  });

  it("rejects an unknown option with exit 2 and the usage text", async () => {
    const ctx = makeContext();
    expect(await main(["login", "--bogus"], ctx)).toBe(2);
    expect(ctx.errLines.join("\n")).toContain("Usage: agentstore");
  });

  it("maps a CliError to its message and exit code", async () => {
    const ctx = makeContext({ secrets: ["not-a-key"] });
    expect(await main(["login"], ctx)).toBe(2);
    expect(ctx.errLines.join("\n")).toContain("ask_live_");
  });
});
```

- [ ] **Step 4: Run to verify they fail**

Run: `cd cli && npx vitest run`
Expected: FAIL — `Failed to resolve import "../src/errors.js"` (and the others).

- [ ] **Step 5: Implement the shared modules**

`cli/src/errors.ts`:

```ts
/**
 * A failure the person running the CLI should see as one readable line. Exit code 1 means the project or the build
 * is bad; 2 means the tool could not do its job (usage, network, a rejected upload, a timeout).
 */
export class CliError extends Error {
  constructor(message: string, readonly exitCode: 1 | 2 = 2) {
    super(message);
    this.name = "CliError";
  }
}
```

`cli/src/context.ts` (the I/O seam; tests supply their own):

```ts
import { homedir } from "node:os";
import { createInterface } from "node:readline/promises";
import { CliError } from "./errors.js";

export interface Context {
  cwd: string;
  env: NodeJS.ProcessEnv;
  home: string;
  out(line: string): void;
  err(line: string): void;
  ask(question: string, fallback?: string): Promise<string>;
  readSecret(question: string): Promise<string>;
  sleep(ms: number): Promise<void>;
  now(): number;
  fetch: typeof fetch;
}

export function createContext(): Context {
  return {
    cwd: process.cwd(),
    env: process.env,
    home: homedir(),
    out: (line) => { process.stdout.write(line + "\n"); },
    err: (line) => { process.stderr.write(line + "\n"); },
    ask,
    readSecret,
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
    now: () => Date.now(),
    fetch: globalThis.fetch,
  };
}

async function ask(question: string, fallback?: string): Promise<string> {
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  try {
    const answer = (await rl.question(fallback ? `${question} [${fallback}] ` : `${question} `)).trim();
    return answer || fallback || "";
  } finally {
    rl.close();
  }
}

/** Reads a line without echoing it. When stdin is not a terminal (a pipe), it reads one plain line. */
async function readSecret(question: string): Promise<string> {
  process.stdout.write(question + " ");
  const stdin = process.stdin;
  if (!stdin.isTTY) {
    const rl = createInterface({ input: stdin });
    try {
      for await (const line of rl) {
        return line.trim();
      }
      return "";
    } finally {
      rl.close();
    }
  }
  return new Promise((resolve, reject) => {
    let value = "";
    const finish = () => {
      stdin.off("data", onData);
      stdin.setRawMode(false);
      stdin.pause();
      process.stdout.write("\n");
    };
    const onData = (chunk: string) => {
      for (const ch of chunk) {
        if (ch === "\r" || ch === "\n") {
          finish();
          resolve(value.trim());
          return;
        }
        if (ch === "\u0003") {
          finish();
          reject(new CliError("Cancelled."));
          return;
        }
        value = ch === "\u007f" || ch === "\b" ? value.slice(0, -1) : value + ch;
      }
    };
    stdin.setRawMode(true);
    stdin.setEncoding("utf8");
    stdin.resume();
    stdin.on("data", onData);
  });
}
```

`cli/src/config.ts`:

```ts
import { chmodSync, existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import type { Context } from "./context.js";
import { CliError } from "./errors.js";

export interface Credentials {
  apiKey: string;
  apiUrl: string;
}

export const DEFAULT_API_URL = "http://localhost:8080";

export function credentialsPath(home: string): string {
  return join(home, ".config", "agentstore", "credentials");
}

export function normalizeUrl(url: string): string {
  return url.replace(/\/+$/, "");
}

/** The environment overrides the file, so CI can supply a key without a login. */
export function loadCredentials(ctx: Context): Credentials | null {
  const file = credentialsPath(ctx.home);
  let stored: Partial<Credentials> = {};
  if (existsSync(file)) {
    if ((statSync(file).mode & 0o077) !== 0) {
      ctx.err(`Warning: ${file} is readable by other users; run: chmod 600 ${file}`);
    }
    try {
      stored = JSON.parse(readFileSync(file, "utf8"));
    } catch {
      throw new CliError(`${file} is not valid JSON; run \`agentstore login\` again.`);
    }
  }
  const apiKey = ctx.env.AGENTSTORE_API_KEY || stored.apiKey;
  if (!apiKey) {
    return null;
  }
  return { apiKey, apiUrl: normalizeUrl(ctx.env.AGENTSTORE_API_URL || stored.apiUrl || DEFAULT_API_URL) };
}

export function requireCredentials(ctx: Context): Credentials {
  const credentials = loadCredentials(ctx);
  if (!credentials) {
    throw new CliError("Not logged in. Run `agentstore login`, or set AGENTSTORE_API_KEY.");
  }
  return credentials;
}

export function saveCredentials(ctx: Context, credentials: Credentials): void {
  const file = credentialsPath(ctx.home);
  mkdirSync(dirname(file), { recursive: true, mode: 0o700 });
  chmodSync(dirname(file), 0o700);
  writeFileSync(file, JSON.stringify({ apiKey: credentials.apiKey, apiUrl: normalizeUrl(credentials.apiUrl) }, null, 2) + "\n",
    { mode: 0o600 });
  chmodSync(file, 0o600);   // the mode above only applies when the file is created
}
```

`cli/src/api.ts`:

```ts
import type { Credentials } from "./config.js";
import { CliError } from "./errors.js";

export class ApiClient {
  constructor(private readonly credentials: Credentials, private readonly fetchFn: typeof fetch) {}

  get<T>(path: string): Promise<T> {
    return this.json<T>("GET", path);
  }

  post<T>(path: string, body?: unknown): Promise<T> {
    return this.json<T>("POST", path, body);
  }

  /** PUT to a pre-signed URL. The URL carries a signature, so it is never sent the API key or printed. */
  async putBytes(url: string, bytes: Uint8Array): Promise<void> {
    const res = await this.send(url, { method: "PUT", body: bytes }, "the upload URL");
    if (!res.ok) {
      throw new CliError(`The upload was rejected (HTTP ${res.status}); run \`agentstore push\` again.`);
    }
  }

  /** The text at a pre-signed URL, or null when it is not there (yet). */
  async getText(url: string): Promise<string | null> {
    const res = await this.send(url, { method: "GET" }, "the log URL");
    return res.ok ? await res.text() : null;
  }

  private async json<T>(method: string, path: string, body?: unknown): Promise<T> {
    const res = await this.send(`${this.credentials.apiUrl}${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${this.credentials.apiKey}`,
        "Content-Type": "application/json",
        Accept: "application/json",
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!res.ok) {
      throw await this.failure(res);
    }
    return (await res.json()) as T;
  }

  private async send(url: string, init: RequestInit, what: string = this.credentials.apiUrl): Promise<Response> {
    try {
      return await this.fetchFn(url, init);
    } catch (e) {
      throw new CliError(`Could not reach ${what}: ${(e as Error).message}`);
    }
  }

  private async failure(res: Response): Promise<CliError> {
    if (res.status === 401) {
      return new CliError("API key rejected or revoked. Run `agentstore login` with a valid key.");
    }
    const text = (await res.text().catch(() => "")).trim();
    let message = text.slice(0, 500);
    try {
      const parsed: unknown = JSON.parse(text);
      if (Array.isArray(parsed)) {
        message = parsed.join("; ");
      } else if (parsed && typeof parsed === "object" && typeof (parsed as { message?: unknown }).message === "string") {
        message = (parsed as { message: string }).message;
      }
    } catch {
      // not JSON: keep the raw text
    }
    return new CliError(`Request failed (HTTP ${res.status})${message ? `: ${message}` : ""}`);
  }
}
```

- [ ] **Step 6: Implement `login` and the dispatcher**

`cli/src/commands/login.ts`:

```ts
import { ApiClient } from "../api.js";
import { credentialsPath, DEFAULT_API_URL, normalizeUrl, saveCredentials } from "../config.js";
import type { Context } from "../context.js";
import { CliError } from "../errors.js";

export async function login(ctx: Context, opts: { apiUrl?: string }): Promise<number> {
  const apiUrl = normalizeUrl(opts.apiUrl ?? ctx.env.AGENTSTORE_API_URL ?? DEFAULT_API_URL);
  try {
    new URL(apiUrl);
  } catch {
    throw new CliError(`--api-url "${apiUrl}" is not a valid URL.`);
  }
  const portal = normalizeUrl(ctx.env.AGENTSTORE_PORTAL_URL ?? "http://localhost:3000");
  ctx.out(`Create an API key in the portal: ${portal}/dashboard/api-keys`);

  const apiKey = await ctx.readSecret("Paste your API key:");
  if (!apiKey.startsWith("ask_live_")) {
    throw new CliError("That does not look like an AgentStore API key (they start with ask_live_).");
  }

  // GET /agents/mine is on the key allowlist, so a bad or revoked key fails here instead of at the first push.
  const credentials = { apiKey, apiUrl };
  await new ApiClient(credentials, ctx.fetch).get("/api/v1/agents/mine");

  saveCredentials(ctx, credentials);
  ctx.out(`Logged in. Credentials saved to ${credentialsPath(ctx.home)}.`);
  return 0;
}
```

`cli/src/cli.ts`:

```ts
import { parseArgs } from "node:util";
import { login } from "./commands/login.js";
import type { Context } from "./context.js";
import { CliError } from "./errors.js";

const USAGE = `Usage: agentstore <command> [options]

Commands:
  login [--api-url URL]                 Save an API key (verified against the API first)
  init [--python|--node] [--name N] [--category C] [--description D]
                                        Create an agent and scaffold a project in an empty directory
  validate                              Check the project the way the backend will
  push [--wait] [--timeout SECONDS]     Upload the project and start a build
`;

type Values = Record<string, string | boolean | undefined>;

function parse(args: string[], options: Record<string, { type: "string" | "boolean" }>): Values {
  try {
    return parseArgs({ args, options, strict: true, allowPositionals: false }).values as Values;
  } catch (e) {
    throw new CliError(`${(e as Error).message}\n\n${USAGE}`);
  }
}

export const str = (values: Values, key: string): string | undefined =>
  typeof values[key] === "string" ? (values[key] as string) : undefined;

export const flag = (values: Values, key: string): boolean => values[key] === true;

export async function main(argv: string[], ctx: Context): Promise<number> {
  const [command, ...args] = argv;
  try {
    switch (command) {
      case "login": {
        const values = parse(args, { "api-url": { type: "string" } });
        return await login(ctx, { apiUrl: str(values, "api-url") });
      }
      case "help":
      case "--help":
      case "-h":
        ctx.out(USAGE);
        return 0;
      case undefined:
        ctx.err(USAGE);
        return 2;
      default:
        ctx.err(`Unknown command "${command}"\n\n${USAGE}`);
        return 2;
    }
  } catch (e) {
    if (e instanceof CliError) {
      ctx.err(e.message);
      return e.exitCode;
    }
    throw e;
  }
}
```

`cli/src/bin.ts`:

```ts
#!/usr/bin/env node
import { main } from "./cli.js";
import { createContext } from "./context.js";

main(process.argv.slice(2), createContext()).then(
  (code) => {
    process.exitCode = code;
  },
  (error) => {
    console.error(error instanceof Error ? error.stack : error);
    process.exitCode = 2;
  },
);
```

- [ ] **Step 7: Run the tests and the typecheck**

Run: `cd cli && npx vitest run && npm run typecheck`
Expected: PASS for `config`, `api`, `login`, `cli` tests, and `tsc` reports no errors. If `tsc` complains about `parseArgs` option typing, keep the `as Values` cast in `parse`; if it complains that `RequestInit`/`Response` are unknown, confirm `"types": ["node"]` is set and `@types/node` is version 22 or newer.

- [ ] **Step 8: Build once and smoke the binary**

Run: `cd cli && npm run build && node dist/bin.js --help && node dist/bin.js; echo "exit=$?"`
Expected: the usage text prints for `--help`, then usage on stderr and `exit=2` for the bare command.

- [ ] **Step 9: Commit**

```bash
git add cli
git commit -m "feat(cli): package skeleton, credentials, HTTP client and login"
```

---

### Task 5: Packing and `validate`

**Files:**
- Create: `cli/src/{pack,validate}.ts`, `cli/src/commands/validate.ts`; `cli/src/schema/manifest-schema.json` (created by `npm run sync-schema` in Task 4)
- Modify: `cli/src/cli.ts` (add the `validate` command)
- Test: `cli/test/{pack,validate}.test.ts`, extend `cli/test/cli.test.ts`

**Interfaces:**
- Consumes: `Context`, `CliError` (Task 4).
- Produces (used by Tasks 6–7):
  - `IGNORE_FILE = ".agentstoreignore"`; `listFiles(dir): string[]` (relative POSIX paths, sorted, ignore rules applied); `pack(dir, files?): PackedBundle` where `PackedBundle = { bytes: Uint8Array; sha256: string; entries: string[] }`.
  - `LIMITS = { maxBundleBytes, maxEntries, maxManifestBytes }` (exported from `pack.ts`).
  - `validateProject(dir, limits = LIMITS): { problems: string[]; bundle: PackedBundle | null }` — `bundle` is non-null only when `problems` is empty.
  - `validateCommand(ctx): Promise<number>` in `src/commands/validate.ts`.

- [ ] **Step 1: Write the failing pack tests**

`cli/test/pack.test.ts`:

```ts
import { createHash } from "node:crypto";
import { symlinkSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { unzipSync } from "fflate";
import { describe, expect, it } from "vitest";
import { listFiles, pack } from "../src/pack.js";
import { tempDir, writeFiles } from "./helpers.js";

describe("pack", () => {
  it("puts manifest.json at the root of the zip", () => {
    const dir = tempDir();
    writeFiles(dir, { "manifest.json": "{}", "agent/main.py": "print('hi')" });

    const names = Object.keys(unzipSync(pack(dir).bytes)).sort();

    expect(names).toEqual(["agent/main.py", "manifest.json"]);
  });

  it("applies .agentstoreignore, including directories", () => {
    const dir = tempDir();
    writeFiles(dir, {
      "manifest.json": "{}",
      "agent/main.py": "x",
      "agent/debug.log": "x",
      "secrets/key.txt": "x",
      ".agentstoreignore": "*.log\nsecrets/\n",
    });

    expect(listFiles(dir)).toEqual([".agentstoreignore", "agent/main.py", "manifest.json"]);
  });

  it("always leaves out .git and the root agentstore.yaml, but not a nested one", () => {
    const dir = tempDir();
    writeFiles(dir, {
      "manifest.json": "{}",
      "agentstore.yaml": "agentId: x",
      "sub/agentstore.yaml": "kept: true",
      ".git/config": "x",
      "sub/.git/config": "x",
    });

    expect(listFiles(dir)).toEqual(["manifest.json", "sub/agentstore.yaml"]);
  });

  it("does not follow symlinks", () => {
    const dir = tempDir();
    const outside = tempDir();
    writeFileSync(join(outside, "secret.txt"), "top secret");
    writeFiles(dir, { "manifest.json": "{}" });
    symlinkSync(join(outside, "secret.txt"), join(dir, "link.txt"));
    symlinkSync(outside, join(dir, "linked-dir"));

    expect(listFiles(dir)).toEqual(["manifest.json"]);
  });

  it("is deterministic and reports the sha256 of the zip bytes", () => {
    const dir = tempDir();
    writeFiles(dir, { "manifest.json": "{}", "agent/main.py": "x" });

    const first = pack(dir);
    const second = pack(dir);

    expect(first.sha256).toBe(second.sha256);
    expect(first.sha256).toBe(createHash("sha256").update(first.bytes).digest("hex"));
    expect(first.entries).toEqual(["agent/main.py", "manifest.json"]);
  });
});
```

- [ ] **Step 2: Write the failing validate tests**

`cli/test/validate.test.ts`:

```ts
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { validateProject } from "../src/validate.js";
import { tempDir, writeFiles } from "./helpers.js";

const manifest = (overrides: Record<string, unknown> = {}) => JSON.stringify({
  name: "Recon",
  version: "1.0.0",
  runtime: "python-3.12",
  entrypoint: "agent/main.py",
  scopes: [{ type: "READ", resource: "bank_statement", description: "Read rows" }],
  network: { allowlist: [] },
  costEnvelope: { currency: "INR", maxPerRun: 500, pricingUnit: "METERED", unitPrice: 2, unitLabel: "txn" },
  approvalGates: [],
  dataClasses: ["BANK_STATEMENT"],
  ...overrides,
});

function project(files: Record<string, string> = {}, m: Record<string, unknown> = {}): string {
  const dir = tempDir();
  writeFiles(dir, { "manifest.json": manifest(m), "agent/main.py": "print('hi')\n", ...files });
  return dir;
}

describe("validateProject", () => {
  it("accepts a valid python project and returns the packed bundle", () => {
    const result = validateProject(project());
    expect(result.problems).toEqual([]);
    expect(result.bundle?.entries).toEqual(["agent/main.py", "manifest.json"]);
  });

  it("accepts a valid node project", () => {
    const dir = project({ "agent/main.mjs": "export {}" }, { runtime: "node-22", entrypoint: "agent/main.mjs" });
    expect(validateProject(dir).problems).toEqual([]);
  });

  it("says so when there is no manifest.json", () => {
    const dir = tempDir();
    expect(validateProject(dir).problems).toEqual(["manifest.json is missing from the project root"]);
  });

  it("reports invalid JSON without throwing", () => {
    const dir = tempDir();
    writeFiles(dir, { "manifest.json": "{ nope" });
    const { problems, bundle } = validateProject(dir);
    expect(problems[0]).toMatch(/manifest\.json is not valid JSON/);
    expect(bundle).toBeNull();
  });

  it("reports schema violations with their location", () => {
    const dir = project({}, { costEnvelope: { currency: "INR" } });
    const problems = validateProject(dir).problems.join("\n");
    expect(problems).toContain("/costEnvelope");
    expect(problems).toContain("maxPerRun");
  });

  it("requires runtime and entrypoint", () => {
    const dir = tempDir();
    writeFiles(dir, { "manifest.json": manifest({ runtime: undefined, entrypoint: undefined }) });
    expect(validateProject(dir).problems).toEqual(expect.arrayContaining([
      "runtime is required (python-3.12 or node-22)",
      "entrypoint is required",
    ]));
  });

  it("rejects an unknown runtime", () => {
    expect(validateProject(project({}, { runtime: "ruby-3" })).problems.join("\n"))
      .toContain("runtime must be python-3.12 or node-22");
  });

  it.each([
    ["/etc/passwd"], ["../escape.py"], ["a//b.py"], ["agent/../x.py"], ["-lead.py"],
  ])("rejects the entrypoint %s", (entrypoint) => {
    const problems = validateProject(project({}, { entrypoint })).problems.join("\n");
    expect(problems).toContain("entrypoint must be a relative path inside the bundle");
  });

  it("rejects an entrypoint whose extension does not match the runtime", () => {
    const dir = project({ "agent/main.js": "x" }, { entrypoint: "agent/main.js" });
    expect(validateProject(dir).problems).toContain("entrypoint agent/main.js does not match runtime python-3.12");
  });

  it("names the file when the entrypoint does not exist", () => {
    const dir = project({}, { entrypoint: "agent/missing.py" });
    expect(validateProject(dir).problems.join("\n"))
      .toContain("entrypoint agent/missing.py is not in the bundle (missing, or excluded by .agentstoreignore)");
  });

  it("catches an entrypoint that .agentstoreignore excludes", () => {
    const dir = project({ ".agentstoreignore": "agent/\n" });
    const { problems, bundle } = validateProject(dir);
    expect(problems.join("\n")).toContain("entrypoint agent/main.py is not in the bundle");
    expect(problems.join("\n")).toContain(".agentstoreignore");
    expect(bundle).toBeNull();
  });

  it("requires package-lock.json next to package.json for node projects", () => {
    const dir = project({ "agent/main.mjs": "x", "package.json": "{}" }, { runtime: "node-22", entrypoint: "agent/main.mjs" });
    expect(validateProject(dir).problems).toContain("package-lock.json is required next to package.json");

    writeFiles(dir, { "package-lock.json": "{}" });
    expect(validateProject(dir).problems).toEqual([]);
  });

  it("enforces the packed limits", () => {
    const dir = project();
    const limits = { maxBundleBytes: 10, maxEntries: 1, maxManifestBytes: 10 };
    const problems = validateProject(dir, limits).problems.join("\n");
    expect(problems).toContain("manifest.json is");
    expect(problems).toContain("more than 1 files");
  });

  it("enforces the packed size limit", () => {
    const dir = project();
    const limits = { maxBundleBytes: 10, maxEntries: 20_000, maxManifestBytes: 256 * 1024 };
    expect(validateProject(dir, limits).problems.join("\n")).toMatch(/bundle is \d+ bytes; the limit is 10/);
  });

  it("keeps the bundled schema identical to the backend's", () => {
    const here = import.meta.dirname;
    const backend = readFileSync(join(here, "../../backend/src/main/resources/manifest/manifest-schema.json"), "utf8");
    const bundled = readFileSync(join(here, "../src/schema/manifest-schema.json"), "utf8");
    expect(JSON.parse(bundled)).toEqual(JSON.parse(backend));
  });
});
```

- [ ] **Step 3: Run to verify they fail**

Run: `cd cli && npx vitest run test/pack.test.ts test/validate.test.ts`
Expected: FAIL — cannot resolve `../src/pack.js`.

- [ ] **Step 4: Implement `pack.ts`**

`cli/src/pack.ts`:

```ts
import { createHash } from "node:crypto";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { zipSync } from "fflate";
import ignore from "ignore";

export const IGNORE_FILE = ".agentstoreignore";

/** The same limits the backend enforces (BundleService, BundleInspector), so a too-big project fails before the upload. */
export const LIMITS = {
  maxBundleBytes: 100 * 1024 * 1024,
  maxEntries: 20_000,
  maxManifestBytes: 256 * 1024,
};

export type Limits = typeof LIMITS;

export interface PackedBundle {
  bytes: Uint8Array;
  sha256: string;
  entries: string[];
}

const ALWAYS_IGNORED = [".git/", "/agentstore.yaml"];

/** Relative POSIX paths of the files that go into the bundle, sorted. Symlinks are skipped, never followed. */
export function listFiles(dir: string): string[] {
  const rules = ignore().add(ALWAYS_IGNORED);
  const ignoreFile = join(dir, IGNORE_FILE);
  if (existsSync(ignoreFile)) {
    rules.add(readFileSync(ignoreFile, "utf8"));
  }
  const found: string[] = [];
  const walk = (relative: string): void => {
    for (const entry of readdirSync(join(dir, relative), { withFileTypes: true })) {
      const path = relative ? `${relative}/${entry.name}` : entry.name;
      if (entry.isSymbolicLink()) {
        continue;
      }
      if (entry.isDirectory()) {
        if (!rules.ignores(`${path}/`)) {
          walk(path);
        }
      } else if (entry.isFile() && !rules.ignores(path)) {
        found.push(path);
      }
    }
  };
  walk("");
  return found.sort();
}

/** A zip with a fixed timestamp, so the same project always packs to the same bytes and the same sha256. */
export function pack(dir: string, files: string[] = listFiles(dir)): PackedBundle {
  const data: Record<string, Uint8Array> = {};
  for (const path of files) {
    data[path] = readFileSync(join(dir, path));
  }
  const bytes = zipSync(data, { level: 6, mtime: new Date("2000-01-01T00:00:00Z") });
  return { bytes, sha256: createHash("sha256").update(bytes).digest("hex"), entries: files };
}
```

- [ ] **Step 5: Implement `validate.ts`**

`cli/src/validate.ts`:

```ts
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { Ajv } from "ajv";   // the default import is not constructable under NodeNext (verified with tsc)
import { IGNORE_FILE, type Limits, LIMITS, listFiles, pack, type PackedBundle } from "./pack.js";
import schema from "./schema/manifest-schema.json" with { type: "json" };

/** Mirrors AgentRuntime in the backend: which entrypoint extensions each runtime accepts. */
const RUNTIMES: Record<string, string[]> = {
  "python-3.12": [".py"],
  "node-22": [".js", ".mjs", ".cjs"],
};

/** Mirrors BundleManifestRules.ENTRYPOINT. */
const ENTRYPOINT = /^[A-Za-z0-9_][A-Za-z0-9_./-]*$/;

const checkSchema = new Ajv({ allErrors: true, strict: false }).compile(schema);

export interface Validation {
  problems: string[];
  /** The packed project, only when there are no problems. */
  bundle: PackedBundle | null;
}

/** Fast feedback only: the backend's check at finalize is final. */
export function validateProject(dir: string, limits: Limits = LIMITS): Validation {
  const manifestPath = join(dir, "manifest.json");
  if (!existsSync(manifestPath)) {
    return { problems: ["manifest.json is missing from the project root"], bundle: null };
  }
  const raw = readFileSync(manifestPath);
  const problems: string[] = [];
  if (raw.length > limits.maxManifestBytes) {
    problems.push(`manifest.json is ${raw.length} bytes; the limit is ${limits.maxManifestBytes} (256 KiB)`);
  }
  let manifest: Record<string, unknown>;
  try {
    manifest = JSON.parse(raw.toString("utf8"));
  } catch (e) {
    return { problems: [...problems, `manifest.json is not valid JSON: ${(e as Error).message}`], bundle: null };
  }

  if (!checkSchema(manifest)) {
    for (const error of checkSchema.errors ?? []) {
      problems.push(`manifest.json ${error.instancePath || "/"} ${error.message}`);
    }
  }

  const files = listFiles(dir);
  problems.push(...bundleRules(manifest, files));

  let bundle: PackedBundle | null = null;
  if (files.length > limits.maxEntries) {
    problems.push(`the project has more than ${limits.maxEntries} files (${files.length}); exclude some in ${IGNORE_FILE}`);
  } else {
    bundle = pack(dir, files);
    if (bundle.bytes.length > limits.maxBundleBytes) {
      problems.push(`the packed bundle is ${bundle.bytes.length} bytes; the limit is ${limits.maxBundleBytes}. Exclude large files in ${IGNORE_FILE}`);
    }
  }
  return { problems, bundle: problems.length === 0 ? bundle : null };
}

/** Mirrors BundleManifestRules, the entrypoint-in-bundle check, and the build recipe's lockfile requirement. */
function bundleRules(manifest: Record<string, unknown>, files: string[]): string[] {
  const problems: string[] = [];
  const { runtime, entrypoint } = manifest;
  const extensions = typeof runtime === "string" ? RUNTIMES[runtime] : undefined;

  if (runtime === undefined) {
    problems.push("runtime is required (python-3.12 or node-22)");
  } else if (!extensions) {
    problems.push("runtime must be python-3.12 or node-22");
  }

  if (typeof entrypoint !== "string" || entrypoint.trim() === "") {
    problems.push("entrypoint is required");
  } else if (!ENTRYPOINT.test(entrypoint) || entrypoint.includes("//") || entrypoint.split("/").includes("..")) {
    problems.push("entrypoint must be a relative path inside the bundle (letters, digits, '_', '-', '.', '/')");
  } else {
    if (extensions && !extensions.some((extension) => entrypoint.endsWith(extension))) {
      problems.push(`entrypoint ${entrypoint} does not match runtime ${String(runtime)}`);
    }
    if (!files.includes(entrypoint)) {
      problems.push(`entrypoint ${entrypoint} is not in the bundle (missing, or excluded by ${IGNORE_FILE})`);
    }
  }

  if (runtime === "node-22" && files.includes("package.json") && !files.includes("package-lock.json")) {
    problems.push("package-lock.json is required next to package.json");
  }
  return problems;
}
```

- [ ] **Step 6: Run to verify the tests pass, then typecheck**

Run: `cd cli && npx vitest run test/pack.test.ts test/validate.test.ts && npm run typecheck`
Expected: PASS, with `tsc` clean. (`ajv` must be imported by name, as above; `ignore`, `fflate` and the JSON import attribute were checked to typecheck and run under NodeNext exactly as written here.)

- [ ] **Step 7: Add the `validate` command and its tests**

`cli/src/commands/validate.ts`:

```ts
import type { Context } from "../context.js";
import { validateProject } from "../validate.js";

export async function validateCommand(ctx: Context): Promise<number> {
  const { problems, bundle } = validateProject(ctx.cwd);
  if (!bundle) {
    ctx.err(`Found ${problems.length} problem${problems.length === 1 ? "" : "s"}:`);
    for (const problem of problems) {
      ctx.err(`  - ${problem}`);
    }
    return 1;
  }
  ctx.out(`OK: ${bundle.entries.length} files, ${(bundle.bytes.length / 1024).toFixed(1)} KiB packed.`);
  return 0;
}
```

In `cli/src/cli.ts`, add the import and the case (before `case "help"`):

```ts
import { validateCommand } from "./commands/validate.js";
...
      case "validate":
        parse(args, {});
        return await validateCommand(ctx);
```

Append to `cli/test/cli.test.ts` (add `import { tempDir, writeFiles }`-style helpers to the existing import line as needed):

```ts
import { validateCommand } from "../src/commands/validate.js";

describe("validate command", () => {
  it("exits 1 and lists every problem when the project is bad", async () => {
    const ctx = makeContext();
    expect(await main(["validate"], ctx)).toBe(1);
    expect(ctx.errLines.join("\n")).toContain("manifest.json is missing");
  });

  it("exits 0 for a valid project", async () => {
    const ctx = makeContext();
    writeFiles(ctx.cwd, {
      "manifest.json": JSON.stringify({
        name: "R", version: "1.0.0", runtime: "python-3.12", entrypoint: "agent/main.py", scopes: [],
        network: { allowlist: [] },
        costEnvelope: { currency: "INR", maxPerRun: 1, pricingUnit: "METERED", unitPrice: 1, unitLabel: "u" },
        approvalGates: [], dataClasses: [],
      }),
      "agent/main.py": "x",
    });
    expect(await validateCommand(ctx)).toBe(0);
    expect(ctx.outLines.join("\n")).toMatch(/^OK: 2 files/);
  });

  it("rejects stray arguments with exit 2", async () => {
    const ctx = makeContext();
    expect(await main(["validate", "--wat"], ctx)).toBe(2);
  });
});
```

Change the helpers import at the top of that file to `import { makeContext, writeFiles } from "./helpers.js";`.

- [ ] **Step 8: Run everything and commit**

Run: `cd cli && npx vitest run && npm run typecheck`
Expected: PASS.

```bash
git add cli
git commit -m "feat(cli): pack projects and validate them the way the backend will"
```

---

### Task 6: `init`

**Files:**
- Create: `cli/src/{project,scaffold}.ts`, `cli/src/commands/init.ts`
- Modify: `cli/src/cli.ts` (add the `init` command)
- Test: `cli/test/{project,init}.test.ts`, extend `cli/test/cli.test.ts`

**Interfaces:**
- Consumes: `ApiClient.post`, `requireCredentials`, `CliError`, `Context.ask` (Task 4); `validateProject` (Task 5, used in a test); `POST /api/v1/agents {name, category, description}` → `{id, name, category, description}` (allowed for keys, Task 3).
- Produces (used by Task 7): `PROJECT_FILE = "agentstore.yaml"`; `readProject(dir): { agentId: string; name?: string }` (throws `CliError` when the file is missing or has no valid `agentId`); `scaffoldFiles(runtime, name, agentId): Record<string, string>`; `init(ctx, opts, writeScaffold?): Promise<number>`.

- [ ] **Step 1: Write the failing `project` tests**

`cli/test/project.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { CliError } from "../src/errors.js";
import { readProject } from "../src/project.js";
import { AGENT_ID, tempDir, writeFiles } from "./helpers.js";

describe("readProject", () => {
  it("reads the agent id and name", () => {
    const dir = tempDir();
    writeFiles(dir, { "agentstore.yaml": `agentId: ${AGENT_ID}\nname: My Agent\n` });
    expect(readProject(dir)).toEqual({ agentId: AGENT_ID, name: "My Agent" });
  });

  it("points at init when there is no agentstore.yaml", () => {
    expect(() => readProject(tempDir())).toThrow(/agentstore init/);
  });

  it("rejects a file without an agent id", () => {
    const dir = tempDir();
    writeFiles(dir, { "agentstore.yaml": "name: x\n" });
    expect(() => readProject(dir)).toThrow(/no agentId/);
  });

  it("rejects an agent id that is not a UUID, since it goes into request paths", () => {
    const dir = tempDir();
    writeFiles(dir, { "agentstore.yaml": "agentId: ../../admin\n" });
    const error = (() => { try { readProject(dir); } catch (e) { return e; } })();
    expect(error).toBeInstanceOf(CliError);
    expect((error as CliError).message).toMatch(/not a valid agent id/);
  });
});
```

- [ ] **Step 2: Write the failing `init` tests**

`cli/test/init.test.ts`:

```ts
import { existsSync, mkdirSync, readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { init } from "../src/commands/init.js";
import { readProject } from "../src/project.js";
import { validateProject } from "../src/validate.js";
import { AGENT_ID, makeContext, startServer, tempDir, writeFiles } from "./helpers.js";

async function setup(opts: { cwd?: string; answers?: string[]; reply?: object } = {}) {
  const server = await startServer([
    { method: "POST", path: "/api/v1/agents", reply: { json: opts.reply ?? { id: AGENT_ID, name: "Recon", category: "finance", description: "d" } } },
  ]);
  const ctx = makeContext({
    cwd: opts.cwd,
    answers: opts.answers,
    env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: server.url },
  });
  return { server, ctx };
}

describe("init", () => {
  it("creates the agent, then scaffolds a python project holding its id", async () => {
    const { server, ctx } = await setup();

    expect(await init(ctx, { runtime: "python", name: "Recon", category: "finance", description: "d" })).toBe(0);

    expect(JSON.parse(server.requests[0].body.toString())).toEqual({ name: "Recon", category: "finance", description: "d" });
    expect(server.requests[0].headers.authorization).toBe("Bearer ask_live_test");
    expect(readProject(ctx.cwd)).toEqual({ agentId: AGENT_ID, name: "Recon" });
    for (const path of ["manifest.json", "agent/main.py", "requirements.txt", "fixtures/.gitkeep", "evals/.gitkeep", "schemas/.gitkeep", ".agentstoreignore"]) {
      expect(existsSync(join(ctx.cwd, path)), path).toBe(true);
    }
    const manifest = JSON.parse(readFileSync(join(ctx.cwd, "manifest.json"), "utf8"));
    expect(manifest).toMatchObject({ name: "Recon", runtime: "python-3.12", entrypoint: "agent/main.py" });
    expect(ctx.outLines.join("\n")).toContain(AGENT_ID);
  });

  it("scaffolds a node project for --node, and --typescript is an alias", async () => {
    for (const runtime of ["node", "typescript"] as const) {
      const { ctx } = await setup();
      await init(ctx, { runtime, name: "Recon", category: "finance", description: "d" });
      const manifest = JSON.parse(readFileSync(join(ctx.cwd, "manifest.json"), "utf8"));
      expect(manifest).toMatchObject({ runtime: "node-22", entrypoint: "agent/main.mjs" });
      expect(existsSync(join(ctx.cwd, "agent/main.mjs"))).toBe(true);
      expect(existsSync(join(ctx.cwd, "package.json"))).toBe(false);
    }
  });

  it.each(["python", "node"] as const)("scaffolds a %s project that passes validate", async (runtime) => {
    const { ctx } = await setup();
    await init(ctx, { runtime, name: "Recon", category: "finance", description: "d" });
    expect(validateProject(ctx.cwd).problems).toEqual([]);
  });

  it("asks for what the flags left out, defaulting the name to the directory", async () => {
    const cwd = join(tempDir(), "my-agent");
    mkdirSync(cwd);
    const { server, ctx } = await setup({ cwd, answers: ["", "", ""] });

    await init(ctx, { runtime: "python" });

    expect(JSON.parse(server.requests[0].body.toString())).toEqual({ name: "my-agent", category: "general", description: "" });
  });

  it("refuses a non-empty directory before calling the API", async () => {
    const { server, ctx } = await setup();
    writeFiles(ctx.cwd, { "notes.txt": "mine" });

    await expect(init(ctx, { runtime: "python", name: "Recon", category: "c", description: "" })).rejects.toThrow(/not empty/);

    expect(server.requests).toHaveLength(0);
    expect(readdirSync(ctx.cwd)).toEqual(["notes.txt"]);
  });

  it("refuses to run over an existing agentstore.yaml before calling the API", async () => {
    const { server, ctx } = await setup();
    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });

    await expect(init(ctx, { runtime: "python", name: "Recon", category: "c", description: "" })).rejects.toThrow(/not empty/);

    expect(server.requests).toHaveLength(0);
  });

  it("allows a directory that only holds .git", async () => {
    const { ctx } = await setup();
    writeFiles(ctx.cwd, { ".git/HEAD": "ref: refs/heads/main" });
    expect(await init(ctx, { runtime: "python", name: "Recon", category: "c", description: "" })).toBe(0);
  });

  it("writes nothing when the API refuses to create the agent", async () => {
    const server = await startServer([{ method: "POST", path: "/api/v1/agents", reply: { status: 401 } }]);
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_bad", AGENTSTORE_API_URL: server.url } });

    await expect(init(ctx, { runtime: "python", name: "R", category: "c", description: "" })).rejects.toThrow(/rejected or revoked/);

    expect(readdirSync(ctx.cwd)).toEqual([]);
  });

  it("prints the agent id when the scaffold cannot be written, so init is not re-run", async () => {
    const { ctx } = await setup();

    const error = await init(ctx, { runtime: "python", name: "Recon", category: "c", description: "" },
      () => { throw new Error("disk full"); }).catch((e) => e);

    expect(error.message).toContain(AGENT_ID);
    expect(error.message).toContain("disk full");
    expect(error.message).toContain(`agentId: ${AGENT_ID}`);
  });

  it("requires a login first", async () => {
    const ctx = makeContext();
    await expect(init(ctx, { runtime: "python", name: "R", category: "c", description: "" })).rejects.toThrow(/agentstore login/);
  });
});
```

- [ ] **Step 3: Run to verify they fail**

Run: `cd cli && npx vitest run test/project.test.ts test/init.test.ts`
Expected: FAIL — cannot resolve `../src/project.js`.

- [ ] **Step 4: Implement `project.ts` and `scaffold.ts`**

`cli/src/project.ts`:

```ts
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { parse } from "yaml";
import { CliError } from "./errors.js";

export const PROJECT_FILE = "agentstore.yaml";

export interface ProjectConfig {
  agentId: string;
  name?: string;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** The agent id ends up in request paths, so anything that is not a UUID is refused here. */
export function readProject(dir: string): ProjectConfig {
  const file = join(dir, PROJECT_FILE);
  if (!existsSync(file)) {
    throw new CliError(`No ${PROJECT_FILE} in ${dir}. Run \`agentstore init\` in an empty directory first.`);
  }
  const parsed: unknown = parse(readFileSync(file, "utf8"));
  const agentId = parsed && typeof parsed === "object" ? (parsed as { agentId?: unknown }).agentId : undefined;
  if (typeof agentId !== "string" || agentId === "") {
    throw new CliError(`${PROJECT_FILE} has no agentId.`);
  }
  if (!UUID.test(agentId)) {
    throw new CliError(`${PROJECT_FILE} has "${agentId}", which is not a valid agent id.`);
  }
  const name = (parsed as { name?: unknown }).name;
  return { agentId, name: typeof name === "string" ? name : undefined };
}
```

`cli/src/scaffold.ts`:

```ts
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { stringify } from "yaml";

export type ScaffoldRuntime = "python" | "node";

const IGNORE = `node_modules/
__pycache__/
.venv/
*.pyc
.env
`;

// The build only imports (python) or syntax-checks (node) the entrypoint, so all the work sits under main().
const PYTHON_MAIN = `"""AgentStore runs this file with python. The build only imports it, so keep the work under main()."""
import json
import os
import urllib.request


def report(kind, detail=None):
    request = urllib.request.Request(
        os.environ["AGENT_CALLBACK_URL"] + "/events",
        data=json.dumps({"kind": kind, "detail": detail or {}}).encode(),
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + os.environ["AGENT_CALLBACK_TOKEN"],
        },
        method="POST",
    )
    urllib.request.urlopen(request, timeout=10).read()


def main():
    report("COMPLETE", {"message": "hello from my agent"})


if __name__ == "__main__":
    main()
`;

const NODE_MAIN = `// AgentStore runs this file with node. The build only syntax-checks it.
async function report(kind, detail = {}) {
  const res = await fetch(process.env.AGENT_CALLBACK_URL + "/events", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer " + process.env.AGENT_CALLBACK_TOKEN,
    },
    body: JSON.stringify({ kind, detail }),
  });
  if (!res.ok) throw new Error("callback failed: " + res.status);
}

await report("COMPLETE", { message: "hello from my agent" });
`;

/**
 * The minimal project: a manifest that passes the backend's schema and bundle rules, a working agent that reports
 * COMPLETE over the callback protocol, and the empty directories the developer platform expects. The Node scaffold
 * is plain ESM JavaScript: the build has no TypeScript step and the backend accepts only .js, .mjs and .cjs
 * entrypoints. It has no package.json, because a package.json needs a package-lock.json next to it.
 */
export function scaffoldFiles(runtime: ScaffoldRuntime, name: string, agentId: string): Record<string, string> {
  const python = runtime === "python";
  const manifest = {
    name,
    version: "0.1.0",
    runtime: python ? "python-3.12" : "node-22",
    entrypoint: python ? "agent/main.py" : "agent/main.mjs",
    scopes: [],
    network: { allowlist: [] },
    costEnvelope: { currency: "INR", maxPerRun: 100, pricingUnit: "METERED", unitPrice: 1, unitLabel: "unit" },
    approvalGates: [],
    dataClasses: [],
  };
  return {
    "agentstore.yaml": stringify({ agentId, name }),
    "manifest.json": JSON.stringify(manifest, null, 2) + "\n",
    ...(python ? { "agent/main.py": PYTHON_MAIN, "requirements.txt": "" } : { "agent/main.mjs": NODE_MAIN }),
    "fixtures/.gitkeep": "",
    "evals/.gitkeep": "",
    "schemas/.gitkeep": "",
    ".agentstoreignore": IGNORE,
  };
}

export function writeScaffold(dir: string, files: Record<string, string>): void {
  for (const [path, content] of Object.entries(files)) {
    const full = join(dir, path);
    mkdirSync(dirname(full), { recursive: true });
    writeFileSync(full, content);
  }
}
```

- [ ] **Step 5: Implement `init`**

`cli/src/commands/init.ts`:

```ts
import { accessSync, constants, readdirSync } from "node:fs";
import { basename } from "node:path";
import { ApiClient } from "../api.js";
import { requireCredentials } from "../config.js";
import type { Context } from "../context.js";
import { CliError } from "../errors.js";
import { PROJECT_FILE } from "../project.js";
import { scaffoldFiles, type ScaffoldRuntime, writeScaffold } from "../scaffold.js";

export interface InitOptions {
  /** "typescript" is an alias of "node": the scaffold is Node 22 JavaScript. */
  runtime: ScaffoldRuntime | "typescript";
  name?: string;
  category?: string;
  description?: string;
}

const HARMLESS = new Set([".git", ".DS_Store"]);

interface AgentResponse {
  id: string;
}

export async function init(
  ctx: Context,
  opts: InitOptions,
  write: (dir: string, files: Record<string, string>) => void = writeScaffold,
): Promise<number> {
  const dir = ctx.cwd;

  // Everything that can be checked without the network is checked first, so a refusal never leaves an orphan agent.
  const leftovers = readdirSync(dir).filter((entry) => !HARMLESS.has(entry));
  if (leftovers.length > 0) {
    throw new CliError(`This directory is not empty${leftovers.includes(PROJECT_FILE) ? ` (it already has ${PROJECT_FILE})` : ""}. Run \`agentstore init\` in a new, empty directory.`);
  }
  try {
    accessSync(dir, constants.W_OK);
  } catch {
    throw new CliError(`${dir} is not writable.`);
  }
  const credentials = requireCredentials(ctx);

  const name = opts.name ?? (await ctx.ask("Agent name:", basename(dir)));
  const category = opts.category ?? (await ctx.ask("Category:", "general"));
  const description = opts.description ?? (await ctx.ask("One-line description:", ""));
  const runtime: ScaffoldRuntime = opts.runtime === "python" ? "python" : "node";
  if (opts.runtime === "typescript") {
    ctx.out("Note: --typescript scaffolds Node 22 JavaScript (.mjs); the build has no TypeScript step yet.");
  }

  const agent = await new ApiClient(credentials, ctx.fetch).post<AgentResponse>("/api/v1/agents", { name, category, description });

  try {
    write(dir, scaffoldFiles(runtime, name, agent.id));
  } catch (e) {
    throw new CliError(
      `Agent ${agent.id} was created, but writing the project failed: ${(e as Error).message}\n`
      + `Do not run init again. Put this line in ${PROJECT_FILE} to use the agent: agentId: ${agent.id}`);
  }
  ctx.out(`Created agent ${agent.id} in ${dir}.`);
  ctx.out(`Next: edit agent/, then run \`agentstore validate\` and \`agentstore push --wait\`.`);
  return 0;
}
```

- [ ] **Step 6: Wire `init` into the dispatcher**

In `cli/src/cli.ts` add the import and the case (before `case "validate"`):

```ts
import { init } from "./commands/init.js";
...
      case "init": {
        const values = parse(args, {
          python: { type: "boolean" }, node: { type: "boolean" }, typescript: { type: "boolean" },
          name: { type: "string" }, category: { type: "string" }, description: { type: "string" },
        });
        const chosen = [flag(values, "python"), flag(values, "node"), flag(values, "typescript")].filter(Boolean).length;
        if (chosen > 1) {
          throw new CliError(`Choose one of --python, --node or --typescript.\n\n${USAGE}`);
        }
        return await init(ctx, {
          runtime: flag(values, "node") ? "node" : flag(values, "typescript") ? "typescript" : "python",
          name: str(values, "name"),
          category: str(values, "category"),
          description: str(values, "description"),
        });
      }
```

Append to `cli/test/cli.test.ts`:

```ts
describe("init command", () => {
  it("rejects two runtime flags with exit 2", async () => {
    const ctx = makeContext();
    expect(await main(["init", "--python", "--node"], ctx)).toBe(2);
    expect(ctx.errLines.join("\n")).toContain("Choose one of");
  });
});
```

- [ ] **Step 7: Run everything and commit**

Run: `cd cli && npx vitest run && npm run typecheck`
Expected: PASS. (The scaffolds pass `validateProject`, which proves `init` and `validate` agree.)

```bash
git add cli
git commit -m "feat(cli): init creates the agent and scaffolds a project that passes validate"
```

---

### Task 7: `push` and `--wait`

**Files:**
- Create: `cli/src/commands/push.ts`
- Modify: `cli/src/cli.ts` (add the `push` command)
- Test: `cli/test/push.test.ts`, extend `cli/test/cli.test.ts`

**Interfaces:**
- Consumes: `readProject` (Task 6), `validateProject` (Task 5), `ApiClient`, `requireCredentials`, `Context.sleep/now` (Task 4). Backend, all allowed for keys: `POST /api/v1/agents/{id}/bundles/uploads {sha256, sizeBytes}` → `{bundleId, uploadUrl, expiresAt}`; `PUT uploadUrl`; `POST …/bundles/{bundleId}/finalize {sha256}` → `{bundleId, versionId, versionNumber, buildId, buildStatus}`; `GET …/builds/{buildId}` → `{status: QUEUED|BUILDING|SUCCEEDED|FAILED, failureCode, failureReason, logUrl, …}`.
- Produces: `push(ctx, { wait: boolean; timeoutSeconds: number }): Promise<number>`; `POLL_INTERVAL_MS = 2000`; `DEFAULT_TIMEOUT_SECONDS = 900`.

- [ ] **Step 1: Write the failing tests**

`cli/test/push.test.ts`:

```ts
import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { push } from "../src/commands/push.js";
import { AGENT_ID, makeContext, type Recorded, type Reply, startServer, writeFiles } from "./helpers.js";

const BUNDLE_ID = "bbbbbbbb-2222-4333-8444-555555555555";
const BUILD_ID = "cccccccc-2222-4333-8444-555555555555";
const SECRET = "X-Amz-Signature=topsecret";
const BASE = `/api/v1/agents/${AGENT_ID}`;

const manifest = JSON.stringify({
  name: "R", version: "1.0.0", runtime: "python-3.12", entrypoint: "agent/main.py", scopes: [],
  network: { allowlist: [] },
  costEnvelope: { currency: "INR", maxPerRun: 1, pricingUnit: "METERED", unitPrice: 1, unitLabel: "u" },
  approvalGates: [], dataClasses: [],
});

interface Options {
  /** What each successive GET /builds/{id} returns (the last one repeats). */
  builds?: Array<{ status: string; logUrl?: boolean; failureCode?: string; failureReason?: string }>;
  /** What each successive GET /log returns (the last one repeats). */
  logs?: string[];
  putStatus?: number;
  finalize?: Reply;
  apiUrlSuffix?: string;
  files?: Record<string, string>;
}

async function harness(options: Options = {}) {
  let base = "";
  let buildCall = 0;
  let logCall = 0;
  const builds = options.builds ?? [{ status: "SUCCEEDED", logUrl: true }];
  const logs = options.logs ?? [""];
  const server = await startServer([
    { method: "POST", path: `${BASE}/bundles/uploads`, reply: () => ({ status: 201, json: { bundleId: BUNDLE_ID, uploadUrl: `${base}/upload/${BUNDLE_ID}?${SECRET}`, expiresAt: "2030-01-01T00:00:00Z" } }) },
    { method: "PUT", path: /^\/upload\//, reply: () => ({ status: options.putStatus ?? 200 }) },
    { method: "POST", path: `${BASE}/bundles/${BUNDLE_ID}/finalize`, reply: options.finalize ?? { json: { bundleId: BUNDLE_ID, versionId: "v1", versionNumber: "1.0.0", buildId: BUILD_ID, buildStatus: "QUEUED" } } },
    { method: "GET", path: `${BASE}/builds/${BUILD_ID}`, reply: (): Reply => {
      const step = builds[Math.min(buildCall++, builds.length - 1)];
      return { json: { id: BUILD_ID, status: step.status, failureCode: step.failureCode ?? null, failureReason: step.failureReason ?? null, logUrl: step.logUrl ? `${base}/log?${SECRET}` : null } };
    } },
    { method: "GET", path: /^\/log/, reply: (): Reply => ({ text: logs[Math.min(logCall++, logs.length - 1)] }) },
  ]);
  base = server.url;
  const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: server.url + (options.apiUrlSuffix ?? "") } });
  writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n`, "manifest.json": manifest, "agent/main.py": "print('hi')\n", ...options.files });
  return { server, ctx };
}

const paths = (requests: Recorded[]) => requests.map((r) => `${r.method} ${r.path.split("?")[0]}`);

describe("push", () => {
  it("validates, uploads, finalizes and stops without --wait", async () => {
    const { server, ctx } = await harness();

    expect(await push(ctx, { wait: false, timeoutSeconds: 900 })).toBe(0);

    expect(paths(server.requests)).toEqual([
      `POST ${BASE}/bundles/uploads`,
      `PUT /upload/${BUNDLE_ID}`,
      `POST ${BASE}/bundles/${BUNDLE_ID}/finalize`,
    ]);
    const declared = JSON.parse(server.requests[0].body.toString());
    const uploaded = server.requests[1].body;
    expect(declared.sizeBytes).toBe(uploaded.length);
    expect(declared.sha256).toBe(createHash("sha256").update(uploaded).digest("hex"));
    expect(JSON.parse(server.requests[2].body.toString())).toEqual({ sha256: declared.sha256 });
    expect(ctx.outLines.join("\n")).toContain("Pushed version 1.0.0");
    expect(ctx.outLines.join("\n")).toContain(BUILD_ID);
  });

  it("sends the key to the API but not to the upload URL", async () => {
    const { server, ctx } = await harness();
    await push(ctx, { wait: false, timeoutSeconds: 900 });
    expect(server.requests[0].headers.authorization).toBe("Bearer ask_live_test");
    expect(server.requests[1].headers.authorization).toBeUndefined();
  });

  it("with --wait, tails the log and exits 0 when the build succeeds", async () => {
    const { server, ctx } = await harness({
      builds: [{ status: "QUEUED" }, { status: "BUILDING", logUrl: true }, { status: "BUILDING", logUrl: true }, { status: "SUCCEEDED", logUrl: true }],
      // one entry per poll that has a log URL: the second and third BUILDING polls, then the terminal one
      logs: ["step 1\n", "step 1\nstep 2\n", "step 1\nstep 2\ndone\n"],
    });

    expect(await push(ctx, { wait: true, timeoutSeconds: 900 })).toBe(0);

    expect(ctx.outLines.filter((l) => l === "step 1")).toHaveLength(1);
    expect(ctx.outLines.filter((l) => l === "step 2")).toHaveLength(1);
    expect(ctx.outLines).toContain("done");
    expect(ctx.outLines.join("\n")).toContain("Build succeeded");
    expect(ctx.sleeps).toEqual([2000, 2000, 2000]);
    expect(server.requests.filter((r) => r.path.startsWith(`${BASE}/builds/`))).toHaveLength(4);
  });

  it("with --wait, exits 1 and prints the failure code and reason when the build fails", async () => {
    const { ctx } = await harness({
      builds: [{ status: "FAILED", logUrl: true, failureCode: "SECRET_FOUND", failureReason: "config.py:3" }],
      logs: ["scanning\n"],
    });

    expect(await push(ctx, { wait: true, timeoutSeconds: 900 })).toBe(1);

    expect(ctx.errLines.join("\n")).toContain("SECRET_FOUND");
    expect(ctx.errLines.join("\n")).toContain("config.py:3");
    expect(ctx.outLines).toContain("scanning");
  });

  it("keeps waiting while the log is not available yet and does not crash", async () => {
    const { ctx } = await harness({ builds: [{ status: "QUEUED" }, { status: "QUEUED" }, { status: "SUCCEEDED" }] });
    expect(await push(ctx, { wait: true, timeoutSeconds: 900 })).toBe(0);
    expect(ctx.errLines).toEqual([]);
  });

  it("holds back a half-written last line until it is complete", async () => {
    const { ctx } = await harness({
      builds: [{ status: "BUILDING", logUrl: true }, { status: "SUCCEEDED", logUrl: true }],
      logs: ["one\ntw", "one\ntwo\n"],
    });
    await push(ctx, { wait: true, timeoutSeconds: 900 });
    expect(ctx.outLines.filter((l) => l === "one" || l === "two" || l === "tw")).toEqual(["one", "two"]);
  });

  it("prints a rewritten log from the top when it gets shorter (a re-queued build)", async () => {
    const { ctx } = await harness({
      builds: [{ status: "BUILDING", logUrl: true }, { status: "BUILDING", logUrl: true }, { status: "SUCCEEDED", logUrl: true }],
      logs: ["a\nb\nc\n", "x\n", "x\ny\n"],
    });
    await push(ctx, { wait: true, timeoutSeconds: 900 });
    expect(ctx.outLines).toEqual(expect.arrayContaining(["a", "b", "c", "x", "y"]));
    expect(ctx.outLines.filter((l) => l === "y")).toHaveLength(1);
  });

  it("times out with exit 2 while the build is still running", async () => {
    const { ctx } = await harness({ builds: [{ status: "BUILDING" }] });

    const error = await push(ctx, { wait: true, timeoutSeconds: 10 }).catch((e) => e);

    expect(error.message).toMatch(/Timed out after 10s.*BUILDING/);
    expect(error.exitCode).toBe(2);
    expect(ctx.sleeps).toEqual([2000, 2000, 2000, 2000, 2000]);
  });

  it("does not print the signed URL or the key, in success or failure", async () => {
    const ok = await harness({ builds: [{ status: "SUCCEEDED", logUrl: true }], logs: ["hello\n"] });
    await push(ok.ctx, { wait: true, timeoutSeconds: 900 });
    const bad = await harness({ putStatus: 403 });
    const error = await push(bad.ctx, { wait: false, timeoutSeconds: 900 }).catch((e) => e);

    const everything = [...ok.ctx.outLines, ...ok.ctx.errLines, ...bad.ctx.outLines, ...bad.ctx.errLines, error.message].join("\n");
    expect(everything).not.toContain("topsecret");
    expect(everything).not.toContain("ask_live_test");
  });

  it("exits 2 with a readable line when the signed upload is rejected", async () => {
    const { server, ctx } = await harness({ putStatus: 403 });

    const error = await push(ctx, { wait: false, timeoutSeconds: 900 }).catch((e) => e);

    expect(error.message).toMatch(/upload was rejected \(HTTP 403\)/);
    expect(error.exitCode).toBe(2);
    expect(paths(server.requests)).not.toContain(`POST ${BASE}/bundles/${BUNDLE_ID}/finalize`);
  });

  it("shows the backend's reason when finalize rejects the bundle", async () => {
    const { ctx } = await harness({ finalize: { status: 422, json: { code: "INVALID_BUNDLE", message: "Entrypoint agent/main.py is not in the bundle" } } });
    const error = await push(ctx, { wait: false, timeoutSeconds: 900 }).catch((e) => e);
    expect(error.message).toContain("Entrypoint agent/main.py is not in the bundle");
  });

  it("stops with exit 1 before any request when the project does not validate", async () => {
    const { server, ctx } = await harness({ files: { ".agentstoreignore": "agent/\n" } });

    expect(await push(ctx, { wait: false, timeoutSeconds: 900 })).toBe(1);

    expect(server.requests).toHaveLength(0);
    expect(ctx.errLines.join("\n")).toContain("entrypoint agent/main.py is not in the bundle");
    expect(ctx.errLines.join("\n")).toContain(".agentstoreignore");
  });

  it("needs an agentstore.yaml and a login", async () => {
    const ctx = makeContext();
    await expect(push(ctx, { wait: false, timeoutSeconds: 900 })).rejects.toThrow(/agentstore init/);

    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n` });
    await expect(push(ctx, { wait: false, timeoutSeconds: 900 })).rejects.toThrow(/agentstore login/);
  });

  it("tolerates a trailing slash on the API URL", async () => {
    const { server, ctx } = await harness({ apiUrlSuffix: "/" });
    expect(await push(ctx, { wait: false, timeoutSeconds: 900 })).toBe(0);
    expect(server.requests.every((r) => !r.path.includes("//"))).toBe(true);
  });

  it("reports an unreachable API server in one line with exit 2", async () => {
    const ctx = makeContext({ env: { AGENTSTORE_API_KEY: "ask_live_test", AGENTSTORE_API_URL: "http://127.0.0.1:1" } });
    writeFiles(ctx.cwd, { "agentstore.yaml": `agentId: ${AGENT_ID}\n`, "manifest.json": manifest, "agent/main.py": "x" });

    const error = await push(ctx, { wait: false, timeoutSeconds: 900 }).catch((e) => e);

    expect(error.message).toContain("Could not reach http://127.0.0.1:1");
    expect(error.message).not.toContain("\n");
    expect(error.exitCode).toBe(2);
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd cli && npx vitest run test/push.test.ts`
Expected: FAIL — cannot resolve `../src/commands/push.js`.

- [ ] **Step 3: Implement `push`**

`cli/src/commands/push.ts`:

```ts
import { ApiClient } from "../api.js";
import { requireCredentials } from "../config.js";
import type { Context } from "../context.js";
import { CliError } from "../errors.js";
import { readProject } from "../project.js";
import { validateProject } from "../validate.js";

export const POLL_INTERVAL_MS = 2000;
export const DEFAULT_TIMEOUT_SECONDS = 900;

export interface PushOptions {
  wait: boolean;
  timeoutSeconds: number;
}

interface UploadResponse {
  bundleId: string;
  uploadUrl: string;
}

interface FinalizeResponse {
  versionNumber: string;
  buildId: string;
  buildStatus: string;
}

interface BuildResponse {
  status: "QUEUED" | "BUILDING" | "SUCCEEDED" | "FAILED";
  failureCode: string | null;
  failureReason: string | null;
  logUrl: string | null;
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

  if (!opts.wait) {
    return 0;
  }
  return await waitForBuild(ctx, api, `${base}/builds/${finalized.buildId}`, opts.timeoutSeconds);
}

/**
 * Polls the build and prints the log as it grows. The log is a pre-signed object, so each poll re-fetches it and
 * prints only what was not printed before. A half-written last line is held back until it is complete, and a log
 * that gets shorter (a re-queued build rewrites it) is printed again from the top.
 */
async function waitForBuild(ctx: Context, api: ApiClient, buildPath: string, timeoutSeconds: number): Promise<number> {
  const deadline = ctx.now() + timeoutSeconds * 1000;
  let printed = 0;
  for (;;) {
    const build = await api.get<BuildResponse>(buildPath);
    const finished = build.status === "SUCCEEDED" || build.status === "FAILED";

    const log = build.logUrl ? await api.getText(build.logUrl) : null;
    if (log !== null) {
      if (log.length < printed) {
        printed = 0;
      }
      const upTo = finished ? log.length : log.lastIndexOf("\n") + 1;
      if (upTo > printed) {
        for (const line of log.slice(printed, upTo).replace(/\n$/, "").split("\n")) {
          ctx.out(line);
        }
        printed = upTo;
      }
    }

    if (build.status === "SUCCEEDED") {
      ctx.out("Build succeeded.");
      return 0;
    }
    if (build.status === "FAILED") {
      ctx.err(`Build failed: ${build.failureCode ?? "UNKNOWN"}${build.failureReason ? ` - ${build.failureReason}` : ""}`);
      return 1;
    }
    if (ctx.now() >= deadline) {
      throw new CliError(`Timed out after ${timeoutSeconds}s waiting for the build; it is still ${build.status}. Check the version page in the portal.`);
    }
    await ctx.sleep(POLL_INTERVAL_MS);
  }
}
```

- [ ] **Step 4: Wire `push` into the dispatcher**

In `cli/src/cli.ts` add the import and the case (before `case "help"`):

```ts
import { DEFAULT_TIMEOUT_SECONDS, push } from "./commands/push.js";
...
      case "push": {
        const values = parse(args, { wait: { type: "boolean" }, timeout: { type: "string" } });
        const raw = str(values, "timeout");
        const timeoutSeconds = raw === undefined ? DEFAULT_TIMEOUT_SECONDS : Number(raw);
        if (!Number.isInteger(timeoutSeconds) || timeoutSeconds <= 0) {
          throw new CliError(`--timeout must be a whole number of seconds, got "${raw}".\n\n${USAGE}`);
        }
        return await push(ctx, { wait: flag(values, "wait"), timeoutSeconds });
      }
```

Append to `cli/test/cli.test.ts`:

```ts
describe("push command", () => {
  it.each(["0", "-5", "abc", "1.5"])("rejects --timeout %s with exit 2", async (timeout) => {
    const ctx = makeContext();
    expect(await main(["push", "--timeout", timeout], ctx)).toBe(2);
    expect(ctx.errLines.join("\n")).toContain("--timeout must be a whole number of seconds");
  });

  it("reports a missing agentstore.yaml with exit 2", async () => {
    const ctx = makeContext();
    expect(await main(["push"], ctx)).toBe(2);
    expect(ctx.errLines.join("\n")).toContain("agentstore init");
  });
});
```

- [ ] **Step 5: Run everything, build, and commit**

Run: `cd cli && npx vitest run && npm run typecheck && npm run build`
Expected: all PASS and the build emits `dist/bin.js`.

```bash
git add cli
git commit -m "feat(cli): push uploads a bundle and --wait follows the build to an exit code"
```

---

### Task 8: API keys page

**Files:**
- Create: `frontend/src/app/(developer)/dashboard/api-keys/{page.tsx,actions.ts,ApiKeysView.tsx,ApiKeysView.test.tsx}`
- Modify: `frontend/src/lib/backend.ts` (add `delete`), `frontend/src/lib/types.ts`, `frontend/src/app/(developer)/layout.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/developer/api-keys` → `ApiKeySummary[]`; `POST` → `CreatedApiKey`; `DELETE /{id}` → 204 (Task 1).
- Produces: `createApiKeyAction(name): Promise<{ ok: true; key: string } | { ok: false; error: string }>`; `revokeApiKeyAction(id): Promise<{ ok: true } | { ok: false; error: string }>`; `<ApiKeysView keys={ApiKeySummary[]} />`.

- [ ] **Step 1: Add the types and the `delete` helper**

Append to `frontend/src/lib/types.ts`:

```ts
export interface ApiKeySummary {
  id: string;
  name: string;
  prefix: string;
  createdAt: string;
  lastUsedAt: string | null;
  revokedAt: string | null;
}
export interface CreatedApiKey {
  id: string;
  name: string;
  prefix: string;
  key: string;
  createdAt: string;
}
```

In `frontend/src/lib/backend.ts`, add to the `backend` object:

```ts
  delete: <T,>(path: string) => request<T>(path, { method: "DELETE" }),
```

- [ ] **Step 2: Write the failing component test**

`frontend/src/app/(developer)/dashboard/api-keys/ApiKeysView.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ApiKeysView } from "./ApiKeysView";
import { createApiKeyAction, revokeApiKeyAction } from "./actions";
import type { ApiKeySummary } from "@/lib/types";

vi.mock("./actions", () => ({
  createApiKeyAction: vi.fn(),
  revokeApiKeyAction: vi.fn(),
}));

const keys: ApiKeySummary[] = [
  { id: "k1", name: "laptop", prefix: "ask_live_abc", createdAt: "2026-09-01T10:00:00Z", lastUsedAt: null, revokedAt: null },
  { id: "k2", name: "old ci", prefix: "ask_live_xyz", createdAt: "2026-08-01T10:00:00Z", lastUsedAt: "2026-08-15T10:00:00Z", revokedAt: "2026-08-20T10:00:00Z" },
];

describe("ApiKeysView", () => {
  it("shows a message when there are no keys yet", () => {
    render(<ApiKeysView keys={[]} />);
    expect(screen.getByText(/haven't created any API keys yet/i)).toBeInTheDocument();
  });

  it("lists each key by name and prefix, never the key itself", () => {
    render(<ApiKeysView keys={keys} />);
    expect(screen.getByText("laptop")).toBeInTheDocument();
    expect(screen.getByText("ask_live_abc…")).toBeInTheDocument();
    expect(screen.getByText(/Last used never/)).toBeInTheDocument();
  });

  it("offers Revoke only for active keys and shows revoked ones as revoked", () => {
    render(<ApiKeysView keys={keys} />);
    expect(screen.getByRole("button", { name: "Revoke laptop" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Revoke old ci" })).not.toBeInTheDocument();
    expect(screen.getByText("revoked")).toBeInTheDocument();
  });

  it("revokes a key by id", async () => {
    vi.mocked(revokeApiKeyAction).mockResolvedValueOnce({ ok: true });
    render(<ApiKeysView keys={keys} />);
    await userEvent.click(screen.getByRole("button", { name: "Revoke laptop" }));
    expect(revokeApiKeyAction).toHaveBeenCalledWith("k1");
  });

  it("shows why a revoke failed", async () => {
    vi.mocked(revokeApiKeyAction).mockResolvedValueOnce({ ok: false, error: "Could not revoke the key" });
    render(<ApiKeysView keys={keys} />);
    await userEvent.click(screen.getByRole("button", { name: "Revoke laptop" }));
    expect(screen.getByText("Could not revoke the key")).toBeInTheDocument();
  });

  it("cannot create a key without a name", () => {
    render(<ApiKeysView keys={[]} />);
    expect(screen.getByRole("button", { name: "Create key" })).toBeDisabled();
  });

  it("shows a new key once, with a warning, and hides it when done", async () => {
    vi.mocked(createApiKeyAction).mockResolvedValueOnce({ ok: true, key: "ask_live_ONLYSHOWNONCE" });
    render(<ApiKeysView keys={[]} />);

    await userEvent.type(screen.getByLabelText("Key name"), "  build server ");
    await userEvent.click(screen.getByRole("button", { name: "Create key" }));

    expect(createApiKeyAction).toHaveBeenCalledWith("build server");
    expect(screen.getByText("ask_live_ONLYSHOWNONCE")).toBeInTheDocument();
    expect(screen.getByText(/won't be able to see it again/i)).toBeInTheDocument();
    expect(screen.getByLabelText("Key name")).toHaveValue("");

    await userEvent.click(screen.getByRole("button", { name: "Done" }));
    expect(screen.queryByText("ask_live_ONLYSHOWNONCE")).not.toBeInTheDocument();
  });

  it("copies the new key to the clipboard", async () => {
    const user = userEvent.setup();
    vi.mocked(createApiKeyAction).mockResolvedValueOnce({ ok: true, key: "ask_live_COPYME" });
    render(<ApiKeysView keys={[]} />);
    await user.type(screen.getByLabelText("Key name"), "ci");
    await user.click(screen.getByRole("button", { name: "Create key" }));

    await user.click(screen.getByRole("button", { name: "Copy" }));

    expect(await navigator.clipboard.readText()).toBe("ask_live_COPYME");
    expect(screen.getByRole("button", { name: "Copied" })).toBeInTheDocument();
  });

  it("shows an error and no key when creating fails", async () => {
    vi.mocked(createApiKeyAction).mockResolvedValueOnce({ ok: false, error: "Could not create the key" });
    render(<ApiKeysView keys={[]} />);
    await userEvent.type(screen.getByLabelText("Key name"), "ci");
    await userEvent.click(screen.getByRole("button", { name: "Create key" }));
    expect(screen.getByText("Could not create the key")).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "New API key" })).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd frontend && npx vitest run "src/app/(developer)/dashboard/api-keys"`
Expected: FAIL — cannot resolve `./ApiKeysView` / `./actions`.

- [ ] **Step 4: Implement the actions, the view and the page**

`frontend/src/app/(developer)/dashboard/api-keys/actions.ts`:

```ts
"use server";

import { revalidatePath } from "next/cache";
import { backend, BackendError } from "@/lib/backend";
import type { CreatedApiKey } from "@/lib/types";

export async function createApiKeyAction(name: string) {
  try {
    const created = await backend.post<CreatedApiKey>("/api/v1/developer/api-keys", { name });
    revalidatePath("/dashboard/api-keys");
    return { ok: true as const, key: created.key };
  } catch (e) {
    const rejected = e instanceof BackendError && e.status === 400;
    return { ok: false as const, error: rejected ? "Give the key a name of up to 100 characters" : "Could not create the key" };
  }
}

export async function revokeApiKeyAction(id: string) {
  try {
    await backend.delete<void>(`/api/v1/developer/api-keys/${id}`);
    revalidatePath("/dashboard/api-keys");
    return { ok: true as const };
  } catch {
    return { ok: false as const, error: "Could not revoke the key" };
  }
}
```

`frontend/src/app/(developer)/dashboard/api-keys/ApiKeysView.tsx`:

```tsx
"use client";

import { useState } from "react";
import { StatusPill } from "@/components/StatusPill";
import type { ApiKeySummary } from "@/lib/types";
import { createApiKeyAction, revokeApiKeyAction } from "./actions";

const inputClass = "border border-line rounded-lg px-3 py-2";

function when(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString() : "never";
}

export function ApiKeysView({ keys }: { keys: ApiKeySummary[] }) {
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // The new key lives only in this state: it is not in the URL, in storage, or in the server-rendered page.
  const [newKey, setNewKey] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  async function create(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    const result = await createApiKeyAction(name.trim());
    setBusy(false);
    if (!result.ok) {
      setError(result.error);
      return;
    }
    setNewKey(result.key);
    setCopied(false);
    setName("");
  }

  async function revoke(id: string) {
    setError(null);
    const result = await revokeApiKeyAction(id);
    if (!result.ok) setError(result.error);
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(newKey ?? "");
      setCopied(true);
    } catch {
      setError("Could not copy automatically; select the key and copy it by hand");
    }
  }

  return (
    <div>
      <h1 className="font-serif text-2xl mb-2">API keys</h1>
      <p className="text-ink2 mb-6">
        Keys let the AgentStore CLI push code for your organisation. A key can create agents, versions and builds, and nothing else.
      </p>

      {newKey && (
        <section aria-label="New API key" className="mb-6 rounded-lg border border-line bg-paper2 p-4">
          <p className="font-medium mb-2">Copy your key now. You won't be able to see it again.</p>
          <code className="block break-all font-mono text-sm mb-3">{newKey}</code>
          <div className="flex gap-3">
            <button type="button" onClick={copy} className="bg-green text-white rounded-lg px-4 py-2 font-medium text-sm">
              {copied ? "Copied" : "Copy"}
            </button>
            <button type="button" onClick={() => setNewKey(null)} className="text-ink2 text-sm">
              Done
            </button>
          </div>
        </section>
      )}

      <form onSubmit={create} className="flex gap-3 mb-6">
        <input
          aria-label="Key name"
          placeholder="Key name, e.g. laptop"
          value={name}
          maxLength={100}
          onChange={(e) => setName(e.target.value)}
          className={`${inputClass} flex-1`}
        />
        <button
          type="submit"
          disabled={busy || name.trim() === ""}
          className="bg-green text-white rounded-lg px-4 py-2 font-medium text-sm disabled:opacity-50"
        >
          Create key
        </button>
      </form>
      {error && <p className="text-red mb-4">{error}</p>}

      {keys.length === 0 ? (
        <p className="text-muted py-8">You haven't created any API keys yet.</p>
      ) : (
        <div className="border-t border-line">
          {keys.map((key) => (
            <div key={key.id} className="grid grid-cols-[1.4fr_1fr_1fr_auto] gap-4 items-center py-3 border-b border-line">
              <div>
                <div className="font-semibold">{key.name}</div>
                <div className="font-mono text-sm text-ink2">{key.prefix}…</div>
              </div>
              <span className="text-sm text-ink2">Created {when(key.createdAt)}</span>
              <span className="text-sm text-ink2">Last used {when(key.lastUsedAt)}</span>
              {key.revokedAt ? (
                <StatusPill status="REVOKED" />
              ) : (
                <button type="button" aria-label={`Revoke ${key.name}`} onClick={() => revoke(key.id)} className="text-red text-sm">
                  Revoke
                </button>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
```

`frontend/src/app/(developer)/dashboard/api-keys/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { ApiKeySummary } from "@/lib/types";
import { ApiKeysView } from "./ApiKeysView";

export default async function ApiKeysPage() {
  const keys = await backend.get<ApiKeySummary[]>("/api/v1/developer/api-keys");
  return <ApiKeysView keys={keys} />;
}
```

In `frontend/src/app/(developer)/layout.tsx`, add a link after "New submission":

```tsx
          <Link href="/dashboard/api-keys" className="px-3 py-2 rounded-md text-ink2 hover:bg-paper2">API keys</Link>
```

- [ ] **Step 5: Run the tests and the typecheck**

Run: `cd frontend && npx vitest run "src/app/(developer)/dashboard/api-keys" && npx tsc --noEmit`
Expected: PASS and no type errors. If the clipboard test fails because `navigator.clipboard` is undefined, `userEvent.setup()` was not used for that test; it must be, since only `setup()` installs the clipboard stub.

- [ ] **Step 6: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): API keys page to create, list and revoke developer keys"
```

---

### Task 9: Wizard drops the template id; the status page gets Push instructions and Submit

**Files:**
- Modify: `frontend/src/components/SubmissionWizard.tsx`, `frontend/src/components/SubmissionWizard.test.tsx`, `frontend/src/components/submissionActions.ts`
- Create: `frontend/src/components/{PushInstructions,SubmitVersionButton}.tsx` and their `*.test.tsx`
- Modify: `frontend/src/app/(developer)/dashboard/agents/[agentId]/versions/[versionId]/status/ReviewStatusView.tsx` (+ `ReviewStatusView.test.tsx`), `frontend/src/app/(developer)/dashboard/DashboardList.tsx` (+ `DashboardList.test.tsx`)

**Interfaces:**
- Consumes: `submitVersionAction(agentId, versionId)` (unchanged); `AgentVersionDetailResponse { id, agentId, versionNumber, status, build?: BuildSummary | null }`; `POST /agents/{id}/versions` without `imageRef` (Task 2).
- Produces: `createVersionAction(agentId, { versionNumber, manifestJson, changelog })`; `<PushInstructions agentId versionNumber />`; `<SubmitVersionButton agentId versionId />`.

- [ ] **Step 1: Write the failing tests**

Replace `frontend/src/components/SubmissionWizard.test.tsx` with:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { SubmissionWizard } from "./SubmissionWizard";

vi.mock("./submissionActions", () => ({
  createAgentAction: vi.fn().mockResolvedValue({ ok: true, agentId: "a1" }),
  createVersionAction: vi.fn().mockResolvedValue({ ok: true, versionId: "v1" }),
  submitVersionAction: vi.fn().mockResolvedValue({ ok: true }),
}));
const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));

async function toLastStep() {
  await userEvent.click(screen.getByRole("button", { name: "Continue" }));
  await userEvent.click(screen.getByRole("button", { name: "Continue" }));
  await userEvent.click(screen.getByRole("button", { name: "Continue" }));
}

describe("SubmissionWizard", () => {
  it("adds a scope row and carries it through to the preview step", async () => {
    render(<SubmissionWizard />);
    await userEvent.type(screen.getByPlaceholderText("Agent name"), "Test Agent");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));

    await userEvent.click(screen.getByRole("button", { name: /Add a scope/i }));
    await userEvent.type(screen.getByPlaceholderText(/resource, e\.g\./i), "bank_statement");

    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));

    expect(screen.getByText("bank_statement")).toBeInTheDocument();
  });

  it("no longer asks for a template id", () => {
    render(<SubmissionWizard />);
    expect(screen.queryByPlaceholderText(/template/i)).not.toBeInTheDocument();
  });

  it("says that creating the draft does not submit it", async () => {
    render(<SubmissionWizard />);
    await toLastStep();
    expect(screen.getByText(/does not submit it/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Submit for review" })).not.toBeInTheDocument();
  });

  it("creates the agent, then an image-less draft version, then goes to the version page without submitting", async () => {
    const { createAgentAction, createVersionAction, submitVersionAction } = await import("./submissionActions");
    render(<SubmissionWizard />);
    await userEvent.type(screen.getByPlaceholderText("Agent name"), "Test Agent");
    await toLastStep();
    await userEvent.click(screen.getByRole("button", { name: "Create draft" }));

    expect(createAgentAction).toHaveBeenCalled();
    expect(createVersionAction).toHaveBeenCalledWith("a1", expect.objectContaining({ versionNumber: "1.0.0" }));
    expect(vi.mocked(createVersionAction).mock.calls.at(-1)![1]).not.toHaveProperty("imageRef");
    expect(submitVersionAction).not.toHaveBeenCalled();
    expect(push).toHaveBeenCalledWith("/dashboard/agents/a1/versions/v1/status");
  });

  it("skips agent creation when an existing agent is provided", async () => {
    const { createAgentAction } = await import("./submissionActions");
    vi.mocked(createAgentAction).mockClear();
    render(<SubmissionWizard existingAgent={{ id: "existing-1", name: "Existing Agent", category: "GST & TDS" }} />);
    expect(screen.queryByPlaceholderText("Agent name")).not.toBeInTheDocument();
    await toLastStep();
    await userEvent.click(screen.getByRole("button", { name: "Create draft" }));
    expect(createAgentAction).not.toHaveBeenCalled();
  });

  it("does not create a second agent when retrying after a version-creation failure", async () => {
    const { createAgentAction, createVersionAction } = await import("./submissionActions");
    vi.mocked(createAgentAction).mockClear();
    vi.mocked(createVersionAction).mockClear();
    vi.mocked(createVersionAction).mockResolvedValueOnce({ ok: false, error: "Could not save version" });

    render(<SubmissionWizard />);
    await userEvent.type(screen.getByPlaceholderText("Agent name"), "Test Agent");
    await toLastStep();

    await userEvent.click(screen.getByRole("button", { name: "Create draft" }));
    expect(screen.getByText("Could not save version")).toBeInTheDocument();
    expect(createAgentAction).toHaveBeenCalledTimes(1);

    await userEvent.click(screen.getByRole("button", { name: "Create draft" }));
    expect(createAgentAction).toHaveBeenCalledTimes(1);
    expect(createVersionAction).toHaveBeenCalledTimes(2);
    expect(createVersionAction).toHaveBeenLastCalledWith("a1", expect.objectContaining({ versionNumber: "1.0.0" }));
  });
});
```

`frontend/src/components/PushInstructions.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PushInstructions } from "./PushInstructions";

describe("PushInstructions", () => {
  it("shows the command, the agent id and the version the bundle must declare", () => {
    render(<PushInstructions agentId="agent-123" versionNumber="1.0.0" />);
    const panel = screen.getByRole("region", { name: "Push your code" });
    expect(panel).toHaveTextContent("agentstore push --wait");
    expect(panel).toHaveTextContent("agent-123");
    expect(panel).toHaveTextContent("1.0.0");
  });

  it("warns that the bundle's manifest replaces the drafted one", () => {
    render(<PushInstructions agentId="a" versionNumber="1.0.0" />);
    expect(screen.getByText(/replaces the one you typed/i)).toBeInTheDocument();
  });
});
```

`frontend/src/components/SubmitVersionButton.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { SubmitVersionButton } from "./SubmitVersionButton";
import { submitVersionAction } from "./submissionActions";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));
vi.mock("./submissionActions", () => ({ submitVersionAction: vi.fn() }));

describe("SubmitVersionButton", () => {
  it("submits the version and refreshes the page", async () => {
    vi.mocked(submitVersionAction).mockResolvedValueOnce({ ok: true });
    render(<SubmitVersionButton agentId="a1" versionId="v1" />);

    await userEvent.click(screen.getByRole("button", { name: "Submit for review" }));

    expect(submitVersionAction).toHaveBeenCalledWith("a1", "v1");
    expect(refresh).toHaveBeenCalled();
  });

  it("shows the reason and does not refresh when the submission is refused", async () => {
    refresh.mockClear();
    vi.mocked(submitVersionAction).mockResolvedValueOnce({ ok: false, error: "Could not submit for review" });
    render(<SubmitVersionButton agentId="a1" versionId="v1" />);

    await userEvent.click(screen.getByRole("button", { name: "Submit for review" }));

    expect(screen.getByText("Could not submit for review")).toBeInTheDocument();
    expect(refresh).not.toHaveBeenCalled();
  });
});
```

Replace `frontend/src/app/(developer)/dashboard/agents/[agentId]/versions/[versionId]/status/ReviewStatusView.test.tsx` with:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ReviewStatusView } from "./ReviewStatusView";
import type { AgentVersionDetailResponse, BuildSummary } from "@/lib/types";

vi.mock("@/components/submissionActions", () => ({ submitVersionAction: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));

const baseVersion: AgentVersionDetailResponse = {
  id: "v1",
  agentId: "a1",
  agentName: "Collections",
  versionNumber: "5.0",
  status: "IN_REVIEW",
  manifest: {
    name: "Collections",
    version: "5.0",
    scopes: [],
    networkAllowlist: [],
    costEnvelope: { currency: "INR", maxPerRun: 0, pricingUnit: "OUTCOME", unitPrice: 0, unitLabel: "" },
    approvalGates: [],
    dataClasses: [],
  },
};

const build = (status: BuildSummary["status"]): BuildSummary =>
  ({ buildId: "b1", status, failureCode: null, failureReason: null, logUrl: null });

const draft = (b?: BuildSummary): AgentVersionDetailResponse => ({ ...baseVersion, status: "DRAFT", build: b });

describe("ReviewStatusView", () => {
  it("explains an in-review version is waiting on a human reviewer", () => {
    render(<ReviewStatusView version={baseVersion} />);
    expect(screen.getByText(/Waiting on a human reviewer/)).toBeInTheDocument();
  });

  it("explains a rejected version", () => {
    render(<ReviewStatusView version={{ ...baseVersion, status: "REJECTED" }} />);
    expect(screen.getByText(/A reviewer rejected this version/)).toBeInTheDocument();
  });

  it("shows the build panel when the version has a build", () => {
    render(<ReviewStatusView version={draft(build("QUEUED"))} />);
    expect(screen.getByRole("region", { name: "Build" })).toBeInTheDocument();
    expect(screen.getByText(/Waiting for the build runner/)).toBeInTheDocument();
  });

  it("shows no build panel for versions without a build", () => {
    render(<ReviewStatusView version={baseVersion} />);
    expect(screen.queryByRole("region", { name: "Build" })).not.toBeInTheDocument();
  });

  it("tells a draft with no build how to push its code", () => {
    render(<ReviewStatusView version={draft()} />);
    const panel = screen.getByRole("region", { name: "Push your code" });
    expect(panel).toHaveTextContent("agentstore push --wait");
    expect(panel).toHaveTextContent("a1");
    expect(panel).toHaveTextContent("5.0");
    expect(screen.queryByRole("button", { name: "Submit for review" })).not.toBeInTheDocument();
  });

  it("offers Submit for review once the draft's build has succeeded", () => {
    render(<ReviewStatusView version={draft(build("SUCCEEDED"))} />);
    expect(screen.getByRole("button", { name: "Submit for review" })).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Push your code" })).not.toBeInTheDocument();
  });

  it.each(["QUEUED", "BUILDING", "FAILED"] as const)("does not offer Submit while the build is %s", (status) => {
    render(<ReviewStatusView version={draft(build(status))} />);
    expect(screen.queryByRole("button", { name: "Submit for review" })).not.toBeInTheDocument();
  });

  it("offers neither push instructions nor Submit for a version already in review", () => {
    render(<ReviewStatusView version={{ ...baseVersion, build: build("SUCCEEDED") }} />);
    expect(screen.queryByRole("button", { name: "Submit for review" })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Push your code" })).not.toBeInTheDocument();
  });
});
```

Append to `frontend/src/app/(developer)/dashboard/DashboardList.test.tsx`, inside the `describe` block:

```tsx
  it("links a draft to its version page, where the next step (push, then submit) is shown", () => {
    const withDraft: MyAgentsResponse[] = [
      { agentId: "a2", agentName: "New Agent", category: "GST & TDS", versions: [{ id: "v9", versionNumber: "0.1.0", status: "DRAFT" }] },
    ];
    render(<DashboardList agents={withDraft} />);
    expect(screen.getByRole("link", { name: /0\.1\.0/i })).toHaveAttribute("href", "/dashboard/agents/a2/versions/v9/status");
    expect(screen.getByText("Finish submission")).toBeInTheDocument();
  });
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx vitest run src/components "src/app/(developer)/dashboard"`
Expected: FAIL — `PushInstructions` and `SubmitVersionButton` do not exist, the wizard still shows the template field and a "Submit for review" button, and the status page has no draft panels.

- [ ] **Step 3: Implement the new components**

`frontend/src/components/PushInstructions.tsx`:

```tsx
export function PushInstructions({ agentId, versionNumber }: { agentId: string; versionNumber: string }) {
  return (
    <section aria-label="Push your code" className="mt-6 rounded-lg border border-line p-4">
      <h2 className="mb-2 font-medium">Push your code</h2>
      <p className="mb-2 text-ink2">
        This draft has no code yet. In a project whose <code className="font-mono">agentstore.yaml</code> holds this agent&apos;s id, run:
      </p>
      <pre className="overflow-x-auto rounded bg-paper2 p-3 font-mono text-sm">agentstore push --wait</pre>
      <p className="mt-2 text-sm text-ink2">
        Agent id: <code className="font-mono">{agentId}</code>
      </p>
      <p className="mt-2 text-sm text-ink2">
        The <code className="font-mono">version</code> in your bundle&apos;s <code className="font-mono">manifest.json</code> must be{" "}
        <code className="font-mono">{versionNumber}</code> to fill this draft. The manifest inside the bundle replaces the one you typed here.
      </p>
    </section>
  );
}
```

`frontend/src/components/SubmitVersionButton.tsx`:

```tsx
"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { submitVersionAction } from "./submissionActions";

export function SubmitVersionButton({ agentId, versionId }: { agentId: string; versionId: string }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    setBusy(true);
    setError(null);
    const result = await submitVersionAction(agentId, versionId);
    setBusy(false);
    if (!result.ok) {
      setError(result.error);
      return;
    }
    router.refresh();
  }

  return (
    <div className="mt-6">
      <button
        type="button"
        disabled={busy}
        onClick={submit}
        className="bg-green text-white rounded-lg px-5 py-2 font-medium disabled:opacity-50"
      >
        {busy ? "Submitting…" : "Submit for review"}
      </button>
      {error && <p className="mt-2 text-red">{error}</p>}
    </div>
  );
}
```

- [ ] **Step 4: Update the status page and the dashboard**

`frontend/src/app/(developer)/dashboard/agents/[agentId]/versions/[versionId]/status/ReviewStatusView.tsx`: add the imports and the two conditional blocks.

```tsx
import { BuildPanel } from "@/components/BuildPanel";
import { PushInstructions } from "@/components/PushInstructions";
import { StatusPill } from "@/components/StatusPill";
import { SubmitVersionButton } from "@/components/SubmitVersionButton";
```

and, after the existing `{version.build ? <BuildPanel build={version.build} /> : null}` line:

```tsx
      {version.status === "DRAFT" && !version.build ? (
        <PushInstructions agentId={version.agentId} versionNumber={version.versionNumber} />
      ) : null}
      {version.status === "DRAFT" && version.build?.status === "SUCCEEDED" ? (
        <SubmitVersionButton agentId={version.agentId} versionId={version.id} />
      ) : null}
```

`frontend/src/app/(developer)/dashboard/DashboardList.tsx`: replace `linkFor` and the action label.

```tsx
function linkFor(agentId: string, version: AgentVersionResponse): string {
  if (version.status === "IN_REVIEW" || version.status === "DRAFT") {
    return `/dashboard/agents/${agentId}/versions/${version.id}/status`;
  }
  return `/dashboard/agents/${agentId}`;
}
```

and the label expression becomes:

```tsx
              {version.status === "IN_REVIEW" ? "View status" : version.status === "DRAFT" ? "Finish submission" : "Manage"}
```

- [ ] **Step 5: Update the wizard**

`frontend/src/components/submissionActions.ts`: remove `imageRef` from the input type:

```ts
  input: { versionNumber: string; manifestJson: string; changelog: string },
```

`frontend/src/components/SubmissionWizard.tsx`, five edits:

1. Delete `const [imageRef, setImageRef] = useState("");`.
2. Change the import to `import { createAgentAction, createVersionAction } from "./submissionActions";`.
3. Replace everything in `handleSubmit` from `const versionResult = await createVersionAction(` to the final `router.push(...)` with:

```tsx
    const versionResult = await createVersionAction(agentId, {
      versionNumber,
      manifestJson: JSON.stringify(finalDraft),
      changelog,
    });
    setSubmitting(false);
    if (!versionResult.ok) {
      setError(versionResult.error);
      return;
    }

    router.push(`/dashboard/agents/${agentId}/versions/${versionResult.versionId}/status`);
```

4. Delete the line `<input placeholder="E2B template ID" value={imageRef} onChange={(e) => setImageRef(e.target.value)} className={inputClass} />`.
5. In the last step, replace the heading and paragraph and the submit button:

```tsx
          <h2 className="font-semibold mb-4">Ready to create the draft</h2>
          <p className="text-ink2 mb-4">
            This is exactly what companies will see. Creating the draft does not submit it: next you push your code with the
            AgentStore CLI, and you submit for review once the build succeeds.
          </p>
```

```tsx
            {submitting ? "Creating…" : "Create draft"}
```

(the `onClick={handleSubmit}` button keeps its classes; only its label changes).

- [ ] **Step 6: Run the frontend tests and the typecheck**

Run: `cd frontend && npx vitest run && npx tsc --noEmit`
Expected: PASS everywhere. Fix any other test that still clicks "Submit for review" on the wizard or passes `imageRef`: `grep -rn "imageRef\|Submit for review" frontend/src frontend/e2e` should show only `SubmitVersionButton`, its tests, and the status-page tests.

- [ ] **Step 7: Commit**

```bash
git add frontend
git commit -m "feat(frontend): the wizard makes drafts; the version page shows how to push and submits a built draft"
```

---

### Task 10: Docs, cleanup and end-to-end verification

**Files:**
- Modify: `ONTOLOGY.md`, `runner/README.md`
- Delete: `scripts/dev-push-bundle.sh`
- Create: `cli/README.md`

**Interfaces:**
- Consumes: everything above.
- Produces: an ontology whose stamped hash matches its tracked files (the CI ontology check passes), and a verified branch.

- [ ] **Step 1: Write `cli/README.md`**

````markdown
# agentstore CLI

Push agent code to the AgentStore developer API. Local-only for now: no npm package and no standalone binaries yet.

## Install (from this repository)

```bash
cd cli
npm install
npm run build
npm link          # puts `agentstore` on your PATH
```

Requires Node 22 or newer.

## Use

```bash
agentstore login                       # paste an API key from the portal's API keys page
mkdir my-agent && cd my-agent
agentstore init --python               # creates the agent, then scaffolds the project (or --node)
agentstore validate                    # the same checks the backend runs, offline
agentstore push --wait                 # upload, build, follow the log; exit 0 on success, 1 on a failed build
```

| Exit code | Meaning |
|---|---|
| 0 | Done (`push --wait`: the build succeeded) |
| 1 | The project is not valid, or the build failed (the failure code and reason are printed) |
| 2 | The tool could not do its job: bad usage, the API is unreachable, the upload was rejected, or `--wait` timed out (`--timeout SECONDS`, default 900) |

Credentials live in `~/.config/agentstore/credentials` (mode 0600). `AGENTSTORE_API_KEY` and `AGENTSTORE_API_URL` override the file; the API URL defaults to `http://localhost:8080`. `AGENTSTORE_PORTAL_URL` (default `http://localhost:3000`) is only used to print where to create a key.

Files listed in `.agentstoreignore` (gitignore syntax) are left out of the bundle. `.git/` and the root `agentstore.yaml` are always left out. Submitting a built version for review is done in the portal, on the version's page.

## Develop

```bash
npm test             # Vitest
npm run typecheck
npm run sync-schema  # re-copies backend/src/main/resources/manifest/manifest-schema.json (a test fails if it drifts)
```
````

- [ ] **Step 2: Replace the dev push script**

In `runner/README.md`, replace the two lines beginning "To push a directory through the whole pipeline in development, use `scripts/dev-push-bundle.sh …`" (and its continuation line) with:

```markdown
To push a directory through the whole pipeline in development, use the CLI (`cli/README.md`): create an API key in the
portal, then `agentstore login`, `agentstore init` and `agentstore push --wait`.
```

Then delete the stopgap:

```bash
git rm scripts/dev-push-bundle.sh
```

- [ ] **Step 3: Update `ONTOLOGY.md`**

Make these edits (line numbers are from the current file; find the text rather than trusting them):

1. **Header note (line ~7).** Append to the italic note, before "Regenerate when…": ` Developer API keys and the CLI (WS5c) were added on branch \`feat/ws5c-api-keys-cli\`.`

2. **Identity & tenancy (after the `JwtService.Claims` bullet, line ~22):**

```markdown
* **`DeveloperApiKey`** (`backend/src/main/java/com/agentstore/platform/identity/DeveloperApiKey.java`): An org-scoped credential for the CLI: `orgId`, `createdByUserId`, `name`, `prefix` (first 12 characters, for display), `keyHash` (SHA-256 of the whole `ask_live_…` key; the plaintext is shown once and never stored), `createdAt`, `lastUsedAt`, `revokedAt`. Table `developer_api_key` (migration `backend/src/main/resources/db/migration/V24__developer_api_key.sql`).
```

3. **Rules (section 4, after `Rule UniqueEmail`, line ~140):**

```markdown
- **Rule ApiKeyScope:** A request authenticated by an API key may reach only `POST /api/v1/agents`, `GET /api/v1/agents/mine`, `/api/v1/agents/*/versions/**` except `…/versions/*/submit`, `/api/v1/agents/*/bundles/**` and `/api/v1/agents/*/builds/**`. Anything else answers 403, including key management, wallet, buyer, admin and identity endpoints. The allowlist is `ApiKeyScope` (`backend/src/main/java/com/agentstore/platform/identity/ApiKeyScope.java`), enforced by `ApiKeyAuthFilter` before dispatch, so it also covers `permitAll` paths.
- **Rule ApiKeyRole:** A key always acts as `DEVELOPER` in its own org, never as its creator's role. It is rejected (401) when revoked, unknown, or when its creator no longer holds a `DEVELOPER` `OrgMembership` in the key's org. (`backend/src/main/java/com/agentstore/platform/identity/ApiKeyAuthFilter.java`)
- **Rule ApiKeyShownOnce:** `POST /api/v1/developer/api-keys` returns the plaintext key exactly once; only its hash is stored and the list endpoint never returns it. `last_used_at` is written at most once a minute per key.
```

4. **Endpoints (section 5): add a subsection before `### Catalog & publishing`:**

```markdown
### Developer API keys
- **`DeveloperApiKeyController.create`**: `POST /api/v1/developer/api-keys` [`DEVELOPER`, JWT only] (`backend/src/main/java/com/agentstore/platform/identity/DeveloperApiKeyController.java`)
  - Accepts: `{name}` (not blank, at most 100 characters)
  - Returns: 201 `{id, name, prefix, key, createdAt}`, the only response that contains `key`
- **`DeveloperApiKeyController.list`**: `GET /api/v1/developer/api-keys` [`DEVELOPER`, JWT only]. Returns `[{id, name, prefix, createdAt, lastUsedAt, revokedAt}]` for the caller's org.
- **`DeveloperApiKeyController.revoke`**: `DELETE /api/v1/developer/api-keys/{id}` [`DEVELOPER`, JWT only]. 204, idempotent; 404 for another org's key.
- **`ApiKeyAuthFilter`**: authenticates `Authorization: Bearer ask_live_…` as `JwtService.Claims(createdByUserId, orgId, DEVELOPER)` with authorities `ROLE_DEVELOPER` and `AUTH_API_KEY`.

### Developer CLI (`cli/`, TypeScript, run locally)
- **`agentstore login`**: verifies the key with `GET /api/v1/agents/mine`, then stores `{apiKey, apiUrl}` in `~/.config/agentstore/credentials` (0600).
- **`agentstore init [--python|--node]`**: `POST /api/v1/agents`, then scaffolds a project whose `agentstore.yaml` holds the returned `agentId`. Refuses a non-empty directory before calling the API.
- **`agentstore validate`**: checks `manifest.json` against a bundled copy of `manifest-schema.json` and mirrors `BundleManifestRules`, the entrypoint-in-bundle check, the node lockfile rule and the bundle limits.
- **`agentstore push [--wait]`**: `POST …/bundles/uploads`, `PUT` the signed URL, `POST …/bundles/{id}/finalize`, then with `--wait` polls `GET …/builds/{id}` and tails the pre-signed `logUrl`. Exit 0 on `SUCCEEDED`, 1 on `FAILED` or an invalid project, 2 when the tool could not do its job.
```

5. **`createVersion` bullet (line ~266):** replace "`versionNumber`, `manifestJson` and `imageRef` are required; `changelog` is optional" with "`versionNumber` and `manifestJson` are required; `changelog` is optional. There is no `imageRef`: the version is an image-less `DRAFT` until a pushed bundle builds".

6. **Inconsistencies:** delete the bullet "**`imageRef` is still required on the legacy version endpoint:** …" (it is no longer true), and add:

```markdown
- **Submitting a built draft is portal-only:** `ApiKeyScope` denies `…/versions/*/submit` to keys, and the CLI has no `submit` command (WS8), so the only way to submit is the "Submit for review" button on the version status page.
```

7. **Tracked files (line 2).** Append to the comma-separated `ontology-tracked-files:` list, keeping it on one line:

```
,backend/src/main/java/com/agentstore/platform/identity/DeveloperApiKey.java,backend/src/main/java/com/agentstore/platform/identity/DeveloperApiKeyController.java,backend/src/main/java/com/agentstore/platform/identity/DeveloperApiKeyService.java,backend/src/main/java/com/agentstore/platform/identity/ApiKeyAuthFilter.java,backend/src/main/java/com/agentstore/platform/identity/ApiKeyScope.java,backend/src/main/resources/db/migration/V24__developer_api_key.sql
```

- [ ] **Step 4: Re-stamp the ontology and check it**

Run:

```bash
cd /Users/amittiwari/AgentStore
scripts/stamp-ontology-hash.sh ONTOLOGY.md
scripts/check-ontology-staleness.sh ONTOLOGY.md && echo "ontology fresh"
```

Expected: `ontology fresh`. If either script says `sha256sum: command not found` (macOS), run `brew install coreutils` and retry. Stamp last: any later edit to a tracked file makes the check fail again.

- [ ] **Step 5: Run every suite**

```bash
cd /Users/amittiwari/AgentStore/backend && mvn -q test
cd ../cli && npm test && npm run typecheck && npm run build
cd ../frontend && npx vitest run && npx tsc --noEmit
```

Expected: all PASS. Report the exact counts in the PR description. If a backend test outside this plan's files fails, run it on `master` before assuming this branch broke it (the shared test database makes some tests order-sensitive).

- [ ] **Step 6: Commit and open the PR**

```bash
cd /Users/amittiwari/AgentStore
git add -A
git commit -m "docs: document API keys and the CLI, drop the dev push script, re-stamp the ontology"
git push -u origin feat/ws5c-api-keys-cli
gh pr create --base master --title "feat: developer API keys and the agentstore CLI (WS5c)" --body "$(cat <<'EOF'
Implements WS5c per docs/superpowers/specs/2026-09-26-ws5c-api-keys-cli-design.md.

- Backend: developer_api_key (V24), create/list/revoke endpoints, ApiKeyAuthFilter, an ApiKeyScope allowlist (deny by default; submit excluded), key role fixed at DEVELOPER, keys stop working when the creator leaves the org.
- CLI (cli/, local-only): login, init, validate, push --wait.
- Frontend: API keys page; the wizard no longer takes a template id and creates image-less drafts; the version page shows push instructions and a Submit for review button once the build succeeded.
- CreateAgentVersionRequest drops imageRef.

Not verified here: the interactive hidden-input prompt in `agentstore login`, and the full push against a real runner (see the manual acceptance steps in the plan).
EOF
)"
```

- [ ] **Step 7: Manual acceptance (the developer runs this; it needs the local stack and a runner)**

Follow `runner/README.md` to start Postgres, SeaweedFS, Spring and the runner (E2B key for a real build). Then:

1. Sign up as a developer in the portal, open **API keys**, create a key, and copy it.
2. `cd cli && npm link`, then in a scratch directory: `agentstore login` (paste the key; it must not echo), `mkdir demo && cd demo && agentstore init --python --name Demo --category test`.
3. `agentstore validate` prints `OK`; `agentstore push --wait` tails the log and exits 0 (`echo $?`).
4. Break the project (delete `agent/main.py`): `agentstore validate` exits 1 and names the entrypoint.
5. In the portal, open the new draft from the dashboard ("Finish submission"): the build panel shows SUCCEEDED and **Submit for review** appears; click it and the page shows the version in review.
6. Revoke the key in the portal; `agentstore push` now exits 2 with "API key rejected or revoked".
7. With a valid (not yet revoked) key, `curl -H "Authorization: Bearer <key>" localhost:8080/api/v1/me` returns 403, while the same request with your portal JWT returns 200.

Tell the maintainer which steps passed; the interactive prompt (step 2) and the real build (step 3) are the two things no automated test here covers.
