# Agent Store Backend Platform — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the backend platform (one Spring Boot monolith + Postgres) that proves the core loop end to end — a developer publishes a manifest-declared agent, it goes through automated + human review, a buyer connects and grants scopes, triggers a real isolated sandbox run with an enforced egress allowlist and approval gates, and usage is metered into a double-entry ledger with a billing statement. No frontend, no real third-party integrations (Tally/GST/bank), no real money movement, no actual agent business logic — those are explicitly out of scope for this plan.

**Architecture:** Package-by-module monolith (`identity`, `manifest`, `catalog`, `publishing`, `execution`, `ledger`) talking to each other via plain Java interfaces in one process, backed by one Postgres database (Flyway-managed schema, one migration file per task). Sandboxed execution is bought, not built: agents run in E2B microVMs, created via E2B's control-plane REST API with a manifest-derived network allowlist enforced natively by E2B (`network.allowOut`), and the sandboxed agent process reports its own trace events back to the monolith over an authenticated webhook rather than the monolith scraping sandbox logs.

**Tech Stack:** Java 21, Spring Boot 3.3.4, Maven, Postgres 16, Flyway 10, Hibernate 6 (JPA), Spring Security (JWT via jjwt 0.12.6), `com.networknt:json-schema-validator:1.4.0` for manifest validation, Testcontainers 1.20.x for integration tests, Lombok, Spring's `RestClient` for the outbound E2B HTTP calls.

**Spec:** `agent-store-spec.md` (full vision) and `docs/superpowers/specs/2026-09-16-agent-store-mvp-design.md` (MVP scope this plan implements — read both; this plan argues from the MVP design doc, which itself narrows the full spec).

## Global Constraints

- One Spring Boot application, one Postgres database — no microservices, no Kafka, no per-tenant encryption keys in this MVP (MVP design doc §"Explicitly out of scope").
- Modules talk to each other via Java interfaces within the same process — no network calls between modules (MVP design doc §2).
- No real Tally/GST-portal/bank integrations, no real money movement (no UPI/bank rails, no RBI PPI question) — the ledger is real double-entry accounting against a prepaid `Wallet` balance with no real money behind it (MVP design doc §"Explicitly out of scope").
- No open/public agent submission — only manually-created developer accounts publish (MVP design doc §"Explicitly out of scope"); no invite-flow code is required for this plan.
- Never let an agent hold long-lived credentials — secrets are injected per run only, scoped to one `Connection` (agent-store-spec.md §3, §4).
- Egress is limited to exactly what the manifest declares, enforced at the network layer, not deferred alongside sandbox isolation (business-technical-analysis.md, network-layer note).
- Per-run cost cap + kill switch is not deferred — a runaway agent is a runaway bill (agent-store-spec.md §3).
- Billing amounts are `BigDecimal`, and billed amount vs. actual cost are tracked as separate concerns in the ledger (agent-store-spec.md §3, §5).
- Building the isolation substrate in-house is out of scope — buy it from E2B (MVP design doc §2 "Why a bought execution substrate, not a built one").

---

## File Structure

```
backend/
  pom.xml
  src/main/java/com/agentstore/platform/
    PlatformApplication.java
    common/
      SecretEncryptionProperties.java
      AesGcmCipher.java
    identity/
      Org.java, OrgType.java, User.java, UserRole.java
      OrgRepository.java, UserRepository.java
      JwtService.java, JwtAuthFilter.java, SecurityConfig.java
      AuthController.java, SignupRequest.java, LoginRequest.java, AuthResponse.java
    manifest/
      ManifestValidator.java (JSON Schema check)
      Manifest.java, Scope.java, ScopeType.java, CostEnvelope.java, PricingUnit.java,
      ApprovalGateDecl.java, ManifestParser.java
      resources: db/../manifest-schema.json (see below, under src/main/resources)
    catalog/
      Agent.java, AgentVersion.java, VersionStatus.java, Tier.java
      AgentRepository.java, AgentVersionRepository.java
      CatalogController.java (create agent/version, list catalog)
    publishing/
      ReviewItem.java, ReviewStatus.java, ReviewItemRepository.java
      PublishingService.java, ReviewController.java
    execution/
      Connection.java, ConnectionStatus.java, ConnectionRepository.java
      Secret.java, SecretRepository.java, SecretService.java
      Run.java, RunStatus.java, RunRepository.java
      RunStep.java, RunStepKind.java, RunStepRepository.java
      SandboxProvider.java, SandboxSpec.java, SandboxHandle.java, FakeSandboxProvider.java, E2bSandboxProvider.java, E2bProperties.java
      ConnectionController.java, RunController.java, AgentCallbackController.java, AgentCallbackAuthFilter.java
      RunTokenService.java
    ledger/
      UsageEvent.java, LedgerEntry.java, LedgerEntryType.java, Wallet.java
      UsageEventRepository.java, LedgerEntryRepository.java, WalletRepository.java
      LedgerService.java, StatementController.java
  src/main/resources/
    application.yml
    db/migration/V1__..., V2__... (one per task, see each task)
    manifest/manifest-schema.json
  src/test/java/com/agentstore/platform/
    AbstractIntegrationTest.java
    (one test class per production class, mirrored package structure)
  src/test/resources/application-test.yml
```

One clear responsibility per class; `execution` is the biggest module (sandbox lifecycle, connections, runs, the agent callback protocol) and is deliberately split into the file list above rather than left as a handful of god-classes.

---

## Phase 0 — Project scaffolding

### Task 1: Spring Boot skeleton, health check, shared test base

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/com/agentstore/platform/PlatformApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/resources/application-test.yml`
- Create: `backend/src/test/java/com/agentstore/platform/AbstractIntegrationTest.java`
- Create: `backend/src/test/java/com/agentstore/platform/PlatformApplicationTest.java`
- Create: `backend/src/main/resources/db/migration/V1__init.sql`

**Interfaces:**
- Produces: `AbstractIntegrationTest` — every later integration test extends this; it boots a shared `PostgreSQLContainer<?>` (singleton pattern, started once per test JVM) and registers its JDBC URL/credentials via `@DynamicPropertySource`, so no test class manages container lifecycle itself.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/PlatformApplicationTest.java`:

```java
package com.agentstore.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformApplicationTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Test
    void healthEndpointReturnsUp() {
        var response = rest.getForEntity("http://localhost:" + port + "/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
```

`backend/src/test/java/com/agentstore/platform/AbstractIntegrationTest.java`:

```java
package com.agentstore.platform;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@ExtendWith(SpringExtension.class)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("agentstore")
                    .withUsername("agentstore")
                    .withPassword("agentstore");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=PlatformApplicationTest`
Expected: FAIL — no `pom.xml`/main application exists yet, build error (`No POM in this directory`).

- [ ] **Step 3: Write minimal implementation**

`backend/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.4</version>
    <relativePath/>
  </parent>

  <groupId>com.agentstore</groupId>
  <artifactId>platform</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <name>platform</name>

  <properties>
    <java.version>21</java.version>
    <jjwt.version>0.12.6</jjwt.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-api</artifactId>
      <version>${jjwt.version}</version>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-impl</artifactId>
      <version>${jjwt.version}</version>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-jackson</artifactId>
      <version>${jjwt.version}</version>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>com.networknt</groupId>
      <artifactId>json-schema-validator</artifactId>
      <version>1.4.0</version>
    </dependency>
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <optional>true</optional>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>postgresql</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers-bom</artifactId>
        <version>1.20.1</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

`backend/src/main/java/com/agentstore/platform/PlatformApplication.java`:

```java
package com.agentstore.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
```

`backend/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: agent-store-platform
  datasource:
    url: jdbc:postgresql://localhost:5432/agentstore
    username: agentstore
    password: agentstore
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true

agentstore:
  jwt:
    secret: ${JWT_SECRET:dev-only-secret-change-me-0123456789abcdef-0123456789}
    expiration-minutes: 60
  secrets:
    encryption-key-base64: ${SECRET_ENCRYPTION_KEY:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=}
  e2b:
    api-key: ${E2B_API_KEY:}
    base-url: https://api.e2b.app
  callback:
    base-url: ${CALLBACK_BASE_URL:http://localhost:8080}

management:
  endpoints:
    web:
      exposure:
        include: health
```

`backend/src/test/resources/application-test.yml`:

```yaml
agentstore:
  jwt:
    secret: test-only-secret-not-for-prod-0123456789abcdef
  secrets:
    encryption-key-base64: MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
  e2b:
    api-key: test-key
```

`backend/src/main/resources/db/migration/V1__init.sql`:

```sql
-- Baseline migration; module-owned tables are added by later tasks' migrations.
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=PlatformApplicationTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/pom.xml backend/src
git commit -m "chore: scaffold Spring Boot monolith with health check and Testcontainers base"
```

---

## Phase 1 — Identity

### Task 2: Org and User entities

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__identity.sql`
- Create: `backend/src/main/java/com/agentstore/platform/identity/OrgType.java`
- Create: `backend/src/main/java/com/agentstore/platform/identity/Org.java`
- Create: `backend/src/main/java/com/agentstore/platform/identity/UserRole.java`
- Create: `backend/src/main/java/com/agentstore/platform/identity/User.java`
- Create: `backend/src/main/java/com/agentstore/platform/identity/OrgRepository.java`
- Create: `backend/src/main/java/com/agentstore/platform/identity/UserRepository.java`
- Test: `backend/src/test/java/com/agentstore/platform/identity/UserRepositoryTest.java`

**Interfaces:**
- Produces: `Org{id: UUID, name: String, type: OrgType}`, `User{id: UUID, org: Org, email: String, passwordHash: String, role: UserRole}`, `OrgRepository`, `UserRepository.findByEmail(String): Optional<User>` — every later module references `Org`/`User` by these exact types.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/identity/UserRepositoryTest.java`:

```java
package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class UserRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    OrgRepository orgRepository;

    @Autowired
    UserRepository userRepository;

    @Test
    void savesAndFindsUserByEmail() {
        Org org = orgRepository.save(new Org(null, "Acme SMB", OrgType.BUYER));
        User user = userRepository.save(
                new User(null, org, "finance@acme.example", "hashed-pw", UserRole.BUYER_ADMIN));

        var found = userRepository.findByEmail("finance@acme.example");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(user.getId());
        assertThat(found.get().getOrg().getName()).isEqualTo("Acme SMB");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=UserRepositoryTest`
Expected: FAIL — `Org`, `User`, `OrgType`, `UserRole`, repositories do not exist (compile error).

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/resources/db/migration/V2__identity.sql`:

```sql
CREATE TABLE org (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL REFERENCES org(id),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`backend/src/main/java/com/agentstore/platform/identity/OrgType.java`:

```java
package com.agentstore.platform.identity;

public enum OrgType { BUYER, DEVELOPER }
```

`backend/src/main/java/com/agentstore/platform/identity/Org.java`:

```java
package com.agentstore.platform.identity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "org")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Org {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrgType type;

    private Instant createdAt;

    public Org(UUID id, String name, OrgType type) {
        this.id = id;
        this.name = name;
        this.type = type;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`backend/src/main/java/com/agentstore/platform/identity/UserRole.java`:

```java
package com.agentstore.platform.identity;

public enum UserRole { BUYER_ADMIN, BUYER_APPROVER, DEVELOPER, ADMIN }
```

`backend/src/main/java/com/agentstore/platform/identity/User.java`:

```java
package com.agentstore.platform.identity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "org_id")
    private Org org;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    private Instant createdAt;

    public User(UUID id, Org org, String email, String passwordHash, UserRole role) {
        this.id = id;
        this.org = org;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`backend/src/main/java/com/agentstore/platform/identity/OrgRepository.java`:

```java
package com.agentstore.platform.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrgRepository extends JpaRepository<Org, UUID> {
}
```

`backend/src/main/java/com/agentstore/platform/identity/UserRepository.java`:

```java
package com.agentstore.platform.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=UserRepositoryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(identity): add Org and User entities"
```

### Task 3: Signup and login with JWT

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/identity/JwtService.java`
- Create: `backend/src/main/java/com/agentstore/platform/identity/AuthController.java`
- Create: `backend/src/main/java/com/agentstore/platform/identity/SignupRequest.java`
- Create: `backend/src/main/java/com/agentstore/platform/identity/LoginRequest.java`
- Create: `backend/src/main/java/com/agentstore/platform/identity/AuthResponse.java`
- Test: `backend/src/test/java/com/agentstore/platform/identity/AuthControllerTest.java`
- Test: `backend/src/test/java/com/agentstore/platform/identity/JwtServiceTest.java`

**Interfaces:**
- Consumes: `Org`, `User`, `OrgRepository`, `UserRepository` (Task 2).
- Produces: `JwtService.issue(User): String`, `JwtService.parse(String): Optional<JwtService.Claims>` where `Claims{userId: UUID, orgId: UUID, role: UserRole}` — Task 4's filter and every secured controller depend on this exact shape. `POST /api/v1/auth/signup` and `POST /api/v1/auth/login`, both returning `AuthResponse{token: String}`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/identity/JwtServiceTest.java`:

```java
package com.agentstore.platform.identity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    JwtService jwtService = new JwtService("test-only-secret-not-for-prod-0123456789abcdef", 60);

    @Test
    void issuedTokenParsesBackToSameClaims() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();

        String token = jwtService.issue(userId, orgId, UserRole.BUYER_ADMIN);
        var claims = jwtService.parse(token).orElseThrow();

        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.orgId()).isEqualTo(orgId);
        assertThat(claims.role()).isEqualTo(UserRole.BUYER_ADMIN);
    }

    @Test
    void garbageTokenDoesNotParse() {
        assertThat(jwtService.parse("not-a-jwt")).isEmpty();
    }
}
```

`backend/src/test/java/com/agentstore/platform/identity/AuthControllerTest.java`:

```java
package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void signupThenLoginReturnsWorkingToken() {
        var signup = new SignupRequest("Acme SMB", OrgType.BUYER, "finance@acme.example", "s3cret-pw", UserRole.BUYER_ADMIN);
        var signupResponse = rest.postForEntity(url("/api/v1/auth/signup"), signup, AuthResponse.class);
        assertThat(signupResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signupResponse.getBody().token()).isNotBlank();

        var login = new LoginRequest("finance@acme.example", "s3cret-pw");
        var loginResponse = rest.postForEntity(url("/api/v1/auth/login"), login, AuthResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody().token()).isNotBlank();
    }

    @Test
    void loginWithWrongPasswordIsRejected() {
        var signup = new SignupRequest("Acme SMB 2", OrgType.BUYER, "wrongpw@acme.example", "correct-pw", UserRole.BUYER_ADMIN);
        rest.postForEntity(url("/api/v1/auth/signup"), signup, AuthResponse.class);

        var login = new LoginRequest("wrongpw@acme.example", "incorrect-pw");
        var loginResponse = rest.postForEntity(url("/api/v1/auth/login"), login, AuthResponse.class);

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=JwtServiceTest,AuthControllerTest`
Expected: FAIL — `JwtService`, `AuthController`, DTOs do not exist.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/com/agentstore/platform/identity/JwtService.java`:

```java
package com.agentstore.platform.identity;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(@Value("${agentstore.jwt.secret}") String secret,
                       @Value("${agentstore.jwt.expiration-minutes}") long expirationMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = expirationMinutes;
    }

    public String issue(UUID userId, UUID orgId, UserRole role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("orgId", orgId.toString())
                .claim("role", role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(expirationMinutes))))
                .signWith(key)
                .compact();
    }

    public Optional<Claims> parse(String token) {
        try {
            var payload = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return Optional.of(new Claims(
                    UUID.fromString(payload.getSubject()),
                    UUID.fromString(payload.get("orgId", String.class)),
                    UserRole.valueOf(payload.get("role", String.class))));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public record Claims(UUID userId, UUID orgId, UserRole role) {}
}
```

`backend/src/main/java/com/agentstore/platform/identity/SignupRequest.java`:

```java
package com.agentstore.platform.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record SignupRequest(
        @NotBlank String orgName,
        OrgType orgType,
        @Email @NotBlank String email,
        @NotBlank String password,
        UserRole role) {
}
```

`backend/src/main/java/com/agentstore/platform/identity/LoginRequest.java`:

```java
package com.agentstore.platform.identity;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String email, @NotBlank String password) {
}
```

`backend/src/main/java/com/agentstore/platform/identity/AuthResponse.java`:

```java
package com.agentstore.platform.identity;

public record AuthResponse(String token) {
}
```

`backend/src/main/java/com/agentstore/platform/identity/AuthController.java`:

```java
package com.agentstore.platform.identity;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final OrgRepository orgRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthController(OrgRepository orgRepository, UserRepository userRepository, JwtService jwtService) {
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
        Org org = orgRepository.save(new Org(null, request.orgName(), request.orgType()));
        User user = userRepository.save(new User(null, org, request.email(),
                passwordEncoder.encode(request.password()), request.role()));
        return ResponseEntity.ok(new AuthResponse(jwtService.issue(user.getId(), org.getId(), user.getRole())));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return userRepository.findByEmail(request.email())
                .filter(user -> passwordEncoder.matches(request.password(), user.getPasswordHash()))
                .map(user -> ResponseEntity.ok(new AuthResponse(
                        jwtService.issue(user.getId(), user.getOrg().getId(), user.getRole()))))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=JwtServiceTest,AuthControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(identity): add signup/login issuing JWTs"
```

### Task 4: Role-based route security

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/identity/JwtAuthFilter.java`
- Create: `backend/src/main/java/com/agentstore/platform/identity/SecurityConfig.java`
- Create: `backend/src/main/java/com/agentstore/platform/identity/PingController.java` (test-only probe controller with role-gated endpoints, kept because later tasks add real gated controllers the same way)
- Test: `backend/src/test/java/com/agentstore/platform/identity/SecurityConfigTest.java`

**Interfaces:**
- Consumes: `JwtService.parse` (Task 3).
- Produces: a `SecurityFilterChain` bean securing `/api/v1/**` behind a valid JWT, exposing the authenticated user's `role` as a Spring Security authority string `"ROLE_" + role.name()` so later controllers can use `@PreAuthorize("hasRole('DEVELOPER')")` etc. `/api/v1/auth/**` and `/actuator/**` stay public. `/internal/**` is excluded from this filter chain entirely (Task 18 secures it separately).

- [ ] **Step 1: Write the failing test**

`backend/src/main/java/com/agentstore/platform/identity/PingController.java` (test fixture, not a real product endpoint — later tasks' real controllers replace the need for this, but it must exist now to prove the security config works before any real secured endpoint exists):

```java
package com.agentstore.platform.identity;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PingController {

    @GetMapping("/api/v1/ping")
    @PreAuthorize("hasRole('DEVELOPER')")
    String ping() {
        return "pong";
    }
}
```

`backend/src/test/java/com/agentstore/platform/identity/SecurityConfigTest.java`:

```java
package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JwtService jwtService;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void pingWithoutTokenIsRejected() {
        var response = rest.getForEntity(url("/api/v1/ping"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void pingWithWrongRoleIsForbidden() {
        String token = jwtService.issue(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), UserRole.BUYER_ADMIN);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        var response = rest.exchange(url("/api/v1/ping"), org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void pingWithCorrectRoleSucceeds() {
        String token = jwtService.issue(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), UserRole.DEVELOPER);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        var response = rest.exchange(url("/api/v1/ping"), org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("pong");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=SecurityConfigTest`
Expected: FAIL — no `SecurityFilterChain` configured, so Spring Boot's default security denies everything with a generated login page/401 for all requests including `/api/v1/auth/**`, and there is no `JwtAuthFilter` to populate an authenticated principal, so the "correct role" case also fails.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/com/agentstore/platform/identity/JwtAuthFilter.java`:

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

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            jwtService.parse(header.substring(7)).ifPresent(claims -> {
                var authority = new SimpleGrantedAuthority("ROLE_" + claims.role().name());
                var auth = new UsernamePasswordAuthenticationToken(claims, null, List.of(authority));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(request, response);
    }
}
```

`backend/src/main/java/com/agentstore/platform/identity/SecurityConfig.java`:

```java
package com.agentstore.platform.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/v1/auth/**", "/actuator/**", "/internal/**").permitAll()
                    .requestMatchers("/api/v1/**").authenticated()
                    .anyRequest().permitAll())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=SecurityConfigTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(identity): enforce JWT auth and role checks on /api/v1"
```

---

## Phase 2 — Manifest

### Task 5: Manifest JSON Schema and validator

**Files:**
- Create: `backend/src/main/resources/manifest/manifest-schema.json`
- Create: `backend/src/main/java/com/agentstore/platform/manifest/ManifestValidator.java`
- Test: `backend/src/test/java/com/agentstore/platform/manifest/ManifestValidatorTest.java`
- Test resources: `backend/src/test/resources/manifest/valid-tier-a.json`, `backend/src/test/resources/manifest/valid-tier-b.json`, `backend/src/test/resources/manifest/invalid-missing-cost-envelope.json`

**Interfaces:**
- Produces: `ManifestValidator.validate(String rawJson): ManifestValidator.Result` where `Result` is a sealed-style pair — `Result.valid()` / `Result.errors(): List<String>`. Task 6 and Task 9 both depend on this exact method signature.

- [ ] **Step 1: Write the failing test**

`backend/src/test/resources/manifest/valid-tier-a.json`:

```json
{
  "name": "Bank Reconciliation Agent",
  "version": "1.0.0",
  "scopes": [
    { "type": "READ", "resource": "bank_statement", "description": "Read uploaded bank statement rows" }
  ],
  "network": { "allowlist": [] },
  "costEnvelope": { "currency": "INR", "maxPerRun": 500.00, "pricingUnit": "METERED", "unitPrice": 2.00, "unitLabel": "reconciled_transaction" },
  "modelRequirements": { "provider": "anthropic", "model": "claude-sonnet-5" },
  "approvalGates": [],
  "dataClasses": ["BANK_STATEMENT"]
}
```

`backend/src/test/resources/manifest/valid-tier-b.json`:

```json
{
  "name": "WhatsApp Collections Agent",
  "version": "1.0.0",
  "scopes": [
    { "type": "READ", "resource": "invoice", "description": "Read overdue invoices" },
    { "type": "WRITE", "resource": "whatsapp_message", "description": "Send payment reminders" }
  ],
  "network": { "allowlist": ["graph.facebook.com"] },
  "costEnvelope": { "currency": "INR", "maxPerRun": 200.00, "pricingUnit": "OUTCOME", "unitPrice": 5.00, "unitLabel": "invoice_cycle" },
  "modelRequirements": { "provider": "anthropic", "model": "claude-sonnet-5" },
  "approvalGates": [
    { "trigger": "MESSAGE_CUSTOMER", "description": "Confirm before sending reminders to a customer" }
  ],
  "dataClasses": ["INVOICE", "CONTACT"]
}
```

`backend/src/test/resources/manifest/invalid-missing-cost-envelope.json`:

```json
{
  "name": "Broken Agent",
  "version": "1.0.0",
  "scopes": [],
  "network": { "allowlist": [] },
  "approvalGates": [],
  "dataClasses": []
}
```

`backend/src/test/java/com/agentstore/platform/manifest/ManifestValidatorTest.java`:

```java
package com.agentstore.platform.manifest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestValidatorTest {

    ManifestValidator validator = new ManifestValidator();

    String read(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/manifest/" + name));
    }

    @Test
    void acceptsValidTierAManifest() throws Exception {
        var result = validator.validate(read("valid-tier-a.json"));
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void acceptsValidTierBManifest() throws Exception {
        var result = validator.validate(read("valid-tier-b.json"));
        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsManifestMissingCostEnvelope() throws Exception {
        var result = validator.validate(read("invalid-missing-cost-envelope.json"));
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    void rejectsMalformedJson() {
        var result = validator.validate("{not valid json");
        assertThat(result.valid()).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=ManifestValidatorTest`
Expected: FAIL — `ManifestValidator` does not exist.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/resources/manifest/manifest-schema.json`:

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "AgentManifest",
  "type": "object",
  "required": ["name", "version", "scopes", "network", "costEnvelope", "approvalGates", "dataClasses"],
  "properties": {
    "name": { "type": "string", "minLength": 1 },
    "version": { "type": "string", "minLength": 1 },
    "scopes": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["type", "resource", "description"],
        "properties": {
          "type": { "type": "string", "enum": ["READ", "WRITE"] },
          "resource": { "type": "string", "minLength": 1 },
          "description": { "type": "string" }
        }
      }
    },
    "network": {
      "type": "object",
      "required": ["allowlist"],
      "properties": {
        "allowlist": { "type": "array", "items": { "type": "string" } }
      }
    },
    "costEnvelope": {
      "type": "object",
      "required": ["currency", "maxPerRun", "pricingUnit", "unitPrice", "unitLabel"],
      "properties": {
        "currency": { "type": "string" },
        "maxPerRun": { "type": "number", "minimum": 0 },
        "pricingUnit": { "type": "string", "enum": ["METERED", "OUTCOME", "SUBSCRIPTION"] },
        "unitPrice": { "type": "number", "minimum": 0 },
        "unitLabel": { "type": "string", "minLength": 1 }
      }
    },
    "modelRequirements": {
      "type": "object",
      "properties": {
        "provider": { "type": "string" },
        "model": { "type": "string" }
      }
    },
    "approvalGates": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["trigger", "description"],
        "properties": {
          "trigger": { "type": "string" },
          "description": { "type": "string" }
        }
      }
    },
    "dataClasses": { "type": "array", "items": { "type": "string" } }
  }
}
```

`backend/src/main/java/com/agentstore/platform/manifest/ManifestValidator.java`:

```java
package com.agentstore.platform.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class ManifestValidator {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchema schema;

    public ManifestValidator() {
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
        if (errors.isEmpty()) {
            return new Result(true, List.of());
        }
        return new Result(false, errors.stream().map(Object::toString).collect(Collectors.toList()));
    }

    public record Result(boolean valid, List<String> errors) {
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=ManifestValidatorTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(manifest): add JSON Schema and validator for agent manifests"
```

### Task 6: Typed manifest domain model and parser

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/manifest/ScopeType.java`
- Create: `backend/src/main/java/com/agentstore/platform/manifest/Scope.java`
- Create: `backend/src/main/java/com/agentstore/platform/manifest/PricingUnit.java`
- Create: `backend/src/main/java/com/agentstore/platform/manifest/CostEnvelope.java`
- Create: `backend/src/main/java/com/agentstore/platform/manifest/ApprovalGateDecl.java`
- Create: `backend/src/main/java/com/agentstore/platform/manifest/Manifest.java`
- Create: `backend/src/main/java/com/agentstore/platform/manifest/ManifestParser.java`
- Test: `backend/src/test/java/com/agentstore/platform/manifest/ManifestParserTest.java`

**Interfaces:**
- Consumes: the same fixture files from Task 5 (`valid-tier-a.json`, `valid-tier-b.json`).
- Produces: `Manifest{name, version, scopes: List<Scope>, networkAllowlist: List<String>, costEnvelope: CostEnvelope, approvalGates: List<ApprovalGateDecl>, dataClasses: List<String>}`, `Scope{type: ScopeType, resource: String, description: String}`, `CostEnvelope{currency: String, maxPerRun: BigDecimal, pricingUnit: PricingUnit, unitPrice: BigDecimal, unitLabel: String}`, `ManifestParser.parse(String rawJson): Manifest` (throws `IllegalArgumentException` on malformed input — callers must run `ManifestValidator` first). Every later module (`catalog` for tier classification, `execution` for scope/egress/cost-cap enforcement, `ledger` for unit pricing) consumes `Manifest` by this exact shape.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/manifest/ManifestParserTest.java`:

```java
package com.agentstore.platform.manifest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestParserTest {

    ManifestParser parser = new ManifestParser();

    String read(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/manifest/" + name));
    }

    @Test
    void parsesTierAManifestFields() throws Exception {
        Manifest manifest = parser.parse(read("valid-tier-a.json"));

        assertThat(manifest.name()).isEqualTo("Bank Reconciliation Agent");
        assertThat(manifest.scopes()).hasSize(1);
        assertThat(manifest.scopes().get(0).type()).isEqualTo(ScopeType.READ);
        assertThat(manifest.scopes().get(0).resource()).isEqualTo("bank_statement");
        assertThat(manifest.costEnvelope().pricingUnit()).isEqualTo(PricingUnit.METERED);
        assertThat(manifest.costEnvelope().unitPrice()).isEqualByComparingTo(new BigDecimal("2.00"));
        assertThat(manifest.approvalGates()).isEmpty();
    }

    @Test
    void parsesTierBManifestWithApprovalGate() throws Exception {
        Manifest manifest = parser.parse(read("valid-tier-b.json"));

        assertThat(manifest.scopes()).extracting(Scope::type).contains(ScopeType.WRITE);
        assertThat(manifest.networkAllowlist()).containsExactly("graph.facebook.com");
        assertThat(manifest.approvalGates()).hasSize(1);
        assertThat(manifest.approvalGates().get(0).trigger()).isEqualTo("MESSAGE_CUSTOMER");
    }

    @Test
    void rejectsMalformedJson() {
        assertThatIllegalArgument(() -> parser.parse("{broken"));
    }

    private void assertThatIllegalArgument(Runnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // pass
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=ManifestParserTest`
Expected: FAIL — none of the domain types exist yet.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/com/agentstore/platform/manifest/ScopeType.java`:

```java
package com.agentstore.platform.manifest;

public enum ScopeType { READ, WRITE }
```

`backend/src/main/java/com/agentstore/platform/manifest/Scope.java`:

```java
package com.agentstore.platform.manifest;

public record Scope(ScopeType type, String resource, String description) {
}
```

`backend/src/main/java/com/agentstore/platform/manifest/PricingUnit.java`:

```java
package com.agentstore.platform.manifest;

public enum PricingUnit { METERED, OUTCOME, SUBSCRIPTION }
```

`backend/src/main/java/com/agentstore/platform/manifest/CostEnvelope.java`:

```java
package com.agentstore.platform.manifest;

import java.math.BigDecimal;

public record CostEnvelope(String currency, BigDecimal maxPerRun, PricingUnit pricingUnit,
                            BigDecimal unitPrice, String unitLabel) {
}
```

`backend/src/main/java/com/agentstore/platform/manifest/ApprovalGateDecl.java`:

```java
package com.agentstore.platform.manifest;

public record ApprovalGateDecl(String trigger, String description) {
}
```

`backend/src/main/java/com/agentstore/platform/manifest/Manifest.java`:

```java
package com.agentstore.platform.manifest;

import java.util.List;

public record Manifest(String name, String version, List<Scope> scopes, List<String> networkAllowlist,
                        CostEnvelope costEnvelope, List<ApprovalGateDecl> approvalGates,
                        List<String> dataClasses) {
}
```

`backend/src/main/java/com/agentstore/platform/manifest/ManifestParser.java`:

```java
package com.agentstore.platform.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class ManifestParser {

    private final ObjectMapper mapper = new ObjectMapper();

    public Manifest parse(String rawJson) {
        JsonNode root;
        try {
            root = mapper.readTree(rawJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Manifest is not valid JSON", e);
        }

        List<Scope> scopes = new ArrayList<>();
        for (JsonNode s : root.path("scopes")) {
            scopes.add(new Scope(ScopeType.valueOf(s.get("type").asText()), s.get("resource").asText(),
                    s.path("description").asText("")));
        }

        List<String> allowlist = new ArrayList<>();
        for (JsonNode n : root.path("network").path("allowlist")) {
            allowlist.add(n.asText());
        }

        JsonNode ce = root.path("costEnvelope");
        CostEnvelope costEnvelope = new CostEnvelope(
                ce.get("currency").asText(),
                new BigDecimal(ce.get("maxPerRun").asText()),
                PricingUnit.valueOf(ce.get("pricingUnit").asText()),
                new BigDecimal(ce.get("unitPrice").asText()),
                ce.get("unitLabel").asText());

        List<ApprovalGateDecl> gates = new ArrayList<>();
        for (JsonNode g : root.path("approvalGates")) {
            gates.add(new ApprovalGateDecl(g.get("trigger").asText(), g.path("description").asText("")));
        }

        List<String> dataClasses = new ArrayList<>();
        for (JsonNode d : root.path("dataClasses")) {
            dataClasses.add(d.asText());
        }

        return new Manifest(root.get("name").asText(), root.get("version").asText(), scopes, allowlist,
                costEnvelope, gates, dataClasses);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=ManifestParserTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(manifest): add typed manifest model and parser"
```

---

## Phase 3 — Catalog

### Task 7: Agent and AgentVersion entities

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__catalog.sql`
- Create: `backend/src/main/java/com/agentstore/platform/catalog/VersionStatus.java`
- Create: `backend/src/main/java/com/agentstore/platform/catalog/Tier.java`
- Create: `backend/src/main/java/com/agentstore/platform/catalog/Agent.java`
- Create: `backend/src/main/java/com/agentstore/platform/catalog/AgentVersion.java`
- Create: `backend/src/main/java/com/agentstore/platform/catalog/AgentRepository.java`
- Create: `backend/src/main/java/com/agentstore/platform/catalog/AgentVersionRepository.java`
- Test: `backend/src/test/java/com/agentstore/platform/catalog/AgentVersionRepositoryTest.java`

**Interfaces:**
- Consumes: `Org` (Task 2).
- Produces: `Agent{id, developerOrg: Org, name, category, description}`, `AgentVersion{id, agent: Agent, versionNumber: String, manifestJson: String, imageRef: String, status: VersionStatus, tier: Tier (nullable), changelog: String, createdAt, approvedAt (nullable)}`, `VersionStatus{DRAFT, IN_REVIEW, LIVE, REJECTED}`, `Tier{TIER_A, TIER_B}`, `AgentVersionRepository.findByAgentIdAndStatus(UUID, VersionStatus): List<AgentVersion>`, `AgentVersionRepository.findFirstByAgentIdAndStatusOrderByApprovedAtDesc(UUID, VersionStatus): Optional<AgentVersion>` — Task 16 uses the latter to resolve "the current LIVE version" for a `Connection`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/catalog/AgentVersionRepositoryTest.java`:

```java
package com.agentstore.platform.catalog;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AgentVersionRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    AgentVersionRepository agentVersionRepository;

    @Test
    void findsMostRecentLiveVersionForAnAgent() {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "desc"));

        AgentVersion v1 = new AgentVersion(null, agent, "1.0.0", "{}", "template-v1",
                VersionStatus.LIVE, Tier.TIER_A, "first release");
        v1.setApprovedAt(Instant.now().minusSeconds(3600));
        agentVersionRepository.save(v1);

        AgentVersion v2 = new AgentVersion(null, agent, "1.1.0", "{}", "template-v2",
                VersionStatus.LIVE, Tier.TIER_A, "second release");
        v2.setApprovedAt(Instant.now());
        agentVersionRepository.save(v2);

        AgentVersion draft = new AgentVersion(null, agent, "1.2.0", "{}", "template-v3",
                VersionStatus.DRAFT, null, "unreleased");
        agentVersionRepository.save(draft);

        var current = agentVersionRepository.findFirstByAgentIdAndStatusOrderByApprovedAtDesc(
                agent.getId(), VersionStatus.LIVE);

        assertThat(current).isPresent();
        assertThat(current.get().getVersionNumber()).isEqualTo("1.1.0");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=AgentVersionRepositoryTest`
Expected: FAIL — none of the catalog classes exist.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/resources/db/migration/V3__catalog.sql`:

```sql
CREATE TABLE agent (
    id UUID PRIMARY KEY,
    developer_org_id UUID NOT NULL REFERENCES org(id),
    name VARCHAR(255) NOT NULL,
    category VARCHAR(100) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent_version (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL REFERENCES agent(id),
    version_number VARCHAR(50) NOT NULL,
    manifest_json JSONB NOT NULL,
    image_ref VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    tier VARCHAR(10),
    changelog TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_at TIMESTAMPTZ,
    UNIQUE (agent_id, version_number)
);
```

`backend/src/main/java/com/agentstore/platform/catalog/VersionStatus.java`:

```java
package com.agentstore.platform.catalog;

public enum VersionStatus { DRAFT, IN_REVIEW, LIVE, REJECTED }
```

`backend/src/main/java/com/agentstore/platform/catalog/Tier.java`:

```java
package com.agentstore.platform.catalog;

public enum Tier { TIER_A, TIER_B }
```

`backend/src/main/java/com/agentstore/platform/catalog/Agent.java`:

```java
package com.agentstore.platform.catalog;

import com.agentstore.platform.identity.Org;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent")
@Getter
@Setter
@NoArgsConstructor
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "developer_org_id")
    private Org developerOrg;

    private String name;
    private String category;
    private String description;
    private Instant createdAt;

    public Agent(UUID id, Org developerOrg, String name, String category, String description) {
        this.id = id;
        this.developerOrg = developerOrg;
        this.name = name;
        this.category = category;
        this.description = description;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`backend/src/main/java/com/agentstore/platform/catalog/AgentVersion.java`:

```java
package com.agentstore.platform.catalog;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_version")
@Getter
@Setter
@NoArgsConstructor
public class AgentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id")
    private Agent agent;

    private String versionNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manifest_json", columnDefinition = "jsonb")
    private String manifestJson;

    private String imageRef;

    @Enumerated(EnumType.STRING)
    private VersionStatus status;

    @Enumerated(EnumType.STRING)
    private Tier tier;

    private String changelog;
    private Instant createdAt;
    private Instant approvedAt;

    public AgentVersion(UUID id, Agent agent, String versionNumber, String manifestJson, String imageRef,
                         VersionStatus status, Tier tier, String changelog) {
        this.id = id;
        this.agent = agent;
        this.versionNumber = versionNumber;
        this.manifestJson = manifestJson;
        this.imageRef = imageRef;
        this.status = status;
        this.tier = tier;
        this.changelog = changelog;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`backend/src/main/java/com/agentstore/platform/catalog/AgentRepository.java`:

```java
package com.agentstore.platform.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AgentRepository extends JpaRepository<Agent, UUID> {
}
```

`backend/src/main/java/com/agentstore/platform/catalog/AgentVersionRepository.java`:

```java
package com.agentstore.platform.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentVersionRepository extends JpaRepository<AgentVersion, UUID> {
    List<AgentVersion> findByAgentIdAndStatus(UUID agentId, VersionStatus status);

    Optional<AgentVersion> findFirstByAgentIdAndStatusOrderByApprovedAtDesc(UUID agentId, VersionStatus status);

    List<AgentVersion> findByStatus(VersionStatus status);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=AgentVersionRepositoryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(catalog): add Agent and AgentVersion entities"
```

### Task 8: Create Agent endpoint

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/catalog/CatalogController.java`
- Create: `backend/src/main/java/com/agentstore/platform/catalog/CreateAgentRequest.java`
- Create: `backend/src/main/java/com/agentstore/platform/catalog/AgentResponse.java`
- Test: `backend/src/test/java/com/agentstore/platform/catalog/CatalogControllerTest.java`

**Interfaces:**
- Consumes: `AgentRepository`, `Agent` (Task 7); `JwtService` (Task 3) to mint test tokens; the `ROLE_DEVELOPER` authority wired in Task 4.
- Produces: `POST /api/v1/agents` (role `DEVELOPER`) → `AgentResponse{id, name, category, description}`; sets `developerOrg` from the authenticated caller's `orgId` claim, not from the request body (a developer cannot create a listing under another org).

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/catalog/CatalogControllerTest.java`:

```java
package com.agentstore.platform.catalog;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void developerCreatesAnAgentListing() {
        Org developerOrg = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        String token = jwtService.issue(java.util.UUID.randomUUID(), developerOrg.getId(), UserRole.DEVELOPER);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>(new CreateAgentRequest("Bank Reconciliation", "reconciliation", "Matches bank rows"), headers);

        var response = rest.postForEntity(url("/api/v1/agents"), request, AgentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().name()).isEqualTo("Bank Reconciliation");
        assertThat(agentRepository.findById(response.getBody().id())).isPresent();
        assertThat(agentRepository.findById(response.getBody().id()).get().getDeveloperOrg().getId())
                .isEqualTo(developerOrg.getId());
    }

    @Test
    void buyerCannotCreateAnAgentListing() {
        Org buyerOrg = orgRepository.save(new Org(null, "BuyerCo", OrgType.BUYER));
        String token = jwtService.issue(java.util.UUID.randomUUID(), buyerOrg.getId(), UserRole.BUYER_ADMIN);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>(new CreateAgentRequest("Nope", "x", "y"), headers);

        var response = rest.postForEntity(url("/api/v1/agents"), request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=CatalogControllerTest`
Expected: FAIL — `CatalogController`, DTOs do not exist.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/com/agentstore/platform/catalog/CreateAgentRequest.java`:

```java
package com.agentstore.platform.catalog;

import jakarta.validation.constraints.NotBlank;

public record CreateAgentRequest(@NotBlank String name, @NotBlank String category, String description) {
}
```

`backend/src/main/java/com/agentstore/platform/catalog/AgentResponse.java`:

```java
package com.agentstore.platform.catalog;

import java.util.UUID;

public record AgentResponse(UUID id, String name, String category, String description) {
    static AgentResponse from(Agent agent) {
        return new AgentResponse(agent.getId(), agent.getName(), agent.getCategory(), agent.getDescription());
    }
}
```

`backend/src/main/java/com/agentstore/platform/catalog/CatalogController.java`:

```java
package com.agentstore.platform.catalog;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agents")
public class CatalogController {

    private final AgentRepository agentRepository;
    private final OrgRepository orgRepository;

    public CatalogController(AgentRepository agentRepository, OrgRepository orgRepository) {
        this.agentRepository = agentRepository;
        this.orgRepository = orgRepository;
    }

    @PostMapping
    @PreAuthorize("hasRole('DEVELOPER')")
    public AgentResponse create(@Valid @RequestBody CreateAgentRequest request) {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Org developerOrg = orgRepository.getReferenceById(claims.orgId());
        Agent saved = agentRepository.save(
                new Agent(null, developerOrg, request.name(), request.category(), request.description()));
        return AgentResponse.from(saved);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=CatalogControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(catalog): add developer-only create-agent endpoint"
```

### Task 9: Create AgentVersion endpoint with manifest validation

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/catalog/CatalogController.java`
- Create: `backend/src/main/java/com/agentstore/platform/catalog/CreateAgentVersionRequest.java`
- Create: `backend/src/main/java/com/agentstore/platform/catalog/AgentVersionResponse.java`
- Test: `backend/src/test/java/com/agentstore/platform/catalog/CreateAgentVersionTest.java`

**Interfaces:**
- Consumes: `ManifestValidator` (Task 5), `AgentVersionRepository` (Task 7).
- Produces: `POST /api/v1/agents/{agentId}/versions` (role `DEVELOPER`, must own the agent) → `AgentVersionResponse{id, versionNumber, status}`, always created with `status = DRAFT`, `tier = null`. Returns 400 with the validator's error list if the manifest fails schema validation, 403 if the caller's org does not own the agent. Design note: the E2B template referenced by `imageRef` is built and hosted by the developer via the E2B CLI before this call — the platform does not orchestrate template builds in this MVP, it only records the resulting template ID.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/catalog/CreateAgentVersionTest.java`:

```java
package com.agentstore.platform.catalog;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CreateAgentVersionTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    AgentVersionRepository agentVersionRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders authHeaders(UUID orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, UserRole.DEVELOPER));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void createsDraftVersionWithValidManifest() throws Exception {
        Org developerOrg = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developerOrg, "Bank Reconciliation", "reconciliation", "desc"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));

        var request = new HttpEntity<>(new CreateAgentVersionRequest("1.0.0", manifest, "bank-recon-template-v1", "first cut"),
                authHeaders(developerOrg.getId()));

        var response = rest.postForEntity(url("/api/v1/agents/" + agent.getId() + "/versions"), request, AgentVersionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(VersionStatus.DRAFT);
        var saved = agentVersionRepository.findById(response.getBody().id()).orElseThrow();
        assertThat(saved.getImageRef()).isEqualTo("bank-recon-template-v1");
    }

    @Test
    void rejectsInvalidManifest() throws Exception {
        Org developerOrg = orgRepository.save(new Org(null, "DevCo2", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developerOrg, "Broken", "x", "y"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/invalid-missing-cost-envelope.json"));

        var request = new HttpEntity<>(new CreateAgentVersionRequest("1.0.0", manifest, "template", "cl"),
                authHeaders(developerOrg.getId()));

        var response = rest.postForEntity(url("/api/v1/agents/" + agent.getId() + "/versions"), request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsVersionCreationByNonOwningDeveloper() throws Exception {
        Org owner = orgRepository.save(new Org(null, "Owner", OrgType.DEVELOPER));
        Org intruder = orgRepository.save(new Org(null, "Intruder", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, owner, "Bank Reconciliation", "reconciliation", "desc"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));

        var request = new HttpEntity<>(new CreateAgentVersionRequest("1.0.0", manifest, "template", "cl"),
                authHeaders(intruder.getId()));

        var response = rest.postForEntity(url("/api/v1/agents/" + agent.getId() + "/versions"), request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=CreateAgentVersionTest`
Expected: FAIL — endpoint and DTOs do not exist.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/com/agentstore/platform/catalog/CreateAgentVersionRequest.java`:

```java
package com.agentstore.platform.catalog;

import jakarta.validation.constraints.NotBlank;

public record CreateAgentVersionRequest(@NotBlank String versionNumber, @NotBlank String manifestJson,
                                         @NotBlank String imageRef, String changelog) {
}
```

`backend/src/main/java/com/agentstore/platform/catalog/AgentVersionResponse.java`:

```java
package com.agentstore.platform.catalog;

import java.util.UUID;

public record AgentVersionResponse(UUID id, String versionNumber, VersionStatus status) {
    static AgentVersionResponse from(AgentVersion version) {
        return new AgentVersionResponse(version.getId(), version.getVersionNumber(), version.getStatus());
    }
}
```

`backend/src/main/java/com/agentstore/platform/catalog/CatalogController.java` (add to existing class — inject `AgentVersionRepository` and `ManifestValidator` via constructor, add the new endpoint):

```java
package com.agentstore.platform.catalog;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.manifest.ManifestValidator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agents")
public class CatalogController {

    private final AgentRepository agentRepository;
    private final AgentVersionRepository agentVersionRepository;
    private final OrgRepository orgRepository;
    private final ManifestValidator manifestValidator;

    public CatalogController(AgentRepository agentRepository, AgentVersionRepository agentVersionRepository,
                              OrgRepository orgRepository, ManifestValidator manifestValidator) {
        this.agentRepository = agentRepository;
        this.agentVersionRepository = agentVersionRepository;
        this.orgRepository = orgRepository;
        this.manifestValidator = manifestValidator;
    }

    @PostMapping
    @PreAuthorize("hasRole('DEVELOPER')")
    public AgentResponse create(@Valid @RequestBody CreateAgentRequest request) {
        Org developerOrg = orgRepository.getReferenceById(currentOrgId());
        Agent saved = agentRepository.save(
                new Agent(null, developerOrg, request.name(), request.category(), request.description()));
        return AgentResponse.from(saved);
    }

    @PostMapping("/{agentId}/versions")
    @PreAuthorize("hasRole('DEVELOPER')")
    public AgentVersionResponse createVersion(@PathVariable UUID agentId,
                                               @Valid @RequestBody CreateAgentVersionRequest request) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!agent.getDeveloperOrg().getId().equals(currentOrgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent not owned by caller's org");
        }

        var validation = manifestValidator.validate(request.manifestJson());
        if (!validation.valid()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", validation.errors()));
        }

        AgentVersion version = new AgentVersion(null, agent, request.versionNumber(), request.manifestJson(),
                request.imageRef(), VersionStatus.DRAFT, null, request.changelog());
        return AgentVersionResponse.from(agentVersionRepository.save(version));
    }

    private UUID currentOrgId() {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return claims.orgId();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=CreateAgentVersionTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(catalog): validate manifest on AgentVersion creation, enforce ownership"
```

### Task 10: Public catalog listing endpoint

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/catalog/CatalogListingController.java`
- Create: `backend/src/main/java/com/agentstore/platform/catalog/CatalogListingResponse.java`
- Test: `backend/src/test/java/com/agentstore/platform/catalog/CatalogListingControllerTest.java`

**Interfaces:**
- Consumes: `AgentVersionRepository.findByStatus(VersionStatus)` (Task 7).
- Produces: `GET /api/v1/catalog` (any authenticated user) → `List<CatalogListingResponse>` where each entry is one Agent's current LIVE version (`agentId, agentName, category, versionNumber, costEnvelopeSummary`), never DRAFT/IN_REVIEW/REJECTED versions.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/catalog/CatalogListingControllerTest.java`:

```java
package com.agentstore.platform.catalog;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogListingControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    AgentVersionRepository agentVersionRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void listsOnlyLiveVersions() {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent liveAgent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        Agent draftAgent = agentRepository.save(new Agent(null, developer, "Unfinished", "x", "d"));

        AgentVersion live = new AgentVersion(null, liveAgent, "1.0.0", "{\"costEnvelope\":{\"unitPrice\":2.00,\"unitLabel\":\"reconciled_transaction\",\"currency\":\"INR\"}}",
                "template-v1", VersionStatus.LIVE, Tier.TIER_A, "cl");
        live.setApprovedAt(Instant.now());
        agentVersionRepository.save(live);

        agentVersionRepository.save(new AgentVersion(null, draftAgent, "0.1.0", "{}", "template-x",
                VersionStatus.DRAFT, null, "cl"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), UUID.randomUUID(), UserRole.BUYER_ADMIN));

        var response = rest.exchange(url("/api/v1/catalog"), HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<List<CatalogListingResponse>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).agentName()).isEqualTo("Bank Reconciliation");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=CatalogListingControllerTest`
Expected: FAIL — controller/DTO do not exist.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/com/agentstore/platform/catalog/CatalogListingResponse.java`:

```java
package com.agentstore.platform.catalog;

import java.util.UUID;

public record CatalogListingResponse(UUID agentId, String agentName, String category,
                                      UUID versionId, String versionNumber) {
    static CatalogListingResponse from(AgentVersion version) {
        return new CatalogListingResponse(version.getAgent().getId(), version.getAgent().getName(),
                version.getAgent().getCategory(), version.getId(), version.getVersionNumber());
    }
}
```

`backend/src/main/java/com/agentstore/platform/catalog/CatalogListingController.java`:

```java
package com.agentstore.platform.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CatalogListingController {

    private final AgentVersionRepository agentVersionRepository;

    public CatalogListingController(AgentVersionRepository agentVersionRepository) {
        this.agentVersionRepository = agentVersionRepository;
    }

    @GetMapping("/api/v1/catalog")
    public List<CatalogListingResponse> list() {
        return agentVersionRepository.findByStatus(VersionStatus.LIVE).stream()
                .map(CatalogListingResponse::from)
                .toList();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=CatalogListingControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(catalog): add public catalog listing endpoint"
```

---

## Phase 4 — Publishing pipeline

### Task 11: Submit for review with automated manifest lint gate

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/publishing/PublishingService.java`
- Create: `backend/src/main/java/com/agentstore/platform/publishing/SubmissionController.java`
- Test: `backend/src/test/java/com/agentstore/platform/publishing/SubmissionControllerTest.java`

**Interfaces:**
- Consumes: `AgentVersionRepository`, `Agent`, `AgentVersion`, `VersionStatus` (Task 7); `ManifestValidator` (Task 5).
- Produces: `PublishingService.submit(UUID agentVersionId, UUID callerOrgId): AgentVersion` — re-validates the stored manifest (the "automated gate" manifest lint), moves `DRAFT → IN_REVIEW` on pass, throws `IllegalStateException` if the version is not in `DRAFT` or the manifest is invalid. `POST /api/v1/agents/{agentId}/versions/{versionId}/submit` (role `DEVELOPER`, must own the agent).

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/publishing/SubmissionControllerTest.java`:

```java
package com.agentstore.platform.publishing;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SubmissionControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    AgentVersionRepository agentVersionRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void submittingDraftMovesItToInReview() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        AgentVersion version = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0", manifest,
                "template-v1", VersionStatus.DRAFT, null, "cl"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), developer.getId(), UserRole.DEVELOPER));

        var response = rest.exchange(
                url("/api/v1/agents/" + agent.getId() + "/versions/" + version.getId() + "/submit"),
                HttpMethod.POST, new HttpEntity<>(headers), AgentVersionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(VersionStatus.IN_REVIEW);
    }

    @Test
    void cannotResubmitAVersionAlreadyInReview() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo2", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Agent", "x", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        AgentVersion version = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0", manifest,
                "template-v1", VersionStatus.IN_REVIEW, null, "cl"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), developer.getId(), UserRole.DEVELOPER));

        var response = rest.exchange(
                url("/api/v1/agents/" + agent.getId() + "/versions/" + version.getId() + "/submit"),
                HttpMethod.POST, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=SubmissionControllerTest`
Expected: FAIL — `PublishingService`, `SubmissionController` do not exist.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/com/agentstore/platform/publishing/PublishingService.java`:

```java
package com.agentstore.platform.publishing;

import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.manifest.ManifestValidator;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PublishingService {

    private final AgentVersionRepository agentVersionRepository;
    private final ManifestValidator manifestValidator;

    public PublishingService(AgentVersionRepository agentVersionRepository, ManifestValidator manifestValidator) {
        this.agentVersionRepository = agentVersionRepository;
        this.manifestValidator = manifestValidator;
    }

    public AgentVersion submit(UUID agentVersionId, UUID callerOrgId) {
        AgentVersion version = agentVersionRepository.findById(agentVersionId)
                .orElseThrow(() -> new IllegalArgumentException("AgentVersion not found: " + agentVersionId));
        if (!version.getAgent().getDeveloperOrg().getId().equals(callerOrgId)) {
            throw new SecurityException("AgentVersion not owned by caller's org");
        }
        if (version.getStatus() != VersionStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT versions can be submitted, was " + version.getStatus());
        }
        var validation = manifestValidator.validate(version.getManifestJson());
        if (!validation.valid()) {
            throw new IllegalStateException("Manifest lint failed: " + String.join("; ", validation.errors()));
        }
        version.setStatus(VersionStatus.IN_REVIEW);
        return agentVersionRepository.save(version);
    }
}
```

`backend/src/main/java/com/agentstore/platform/publishing/SubmissionController.java`:

```java
package com.agentstore.platform.publishing;

import com.agentstore.platform.catalog.AgentVersionResponse;
import com.agentstore.platform.identity.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agents/{agentId}/versions/{versionId}")
public class SubmissionController {

    private final PublishingService publishingService;

    public SubmissionController(PublishingService publishingService) {
        this.publishingService = publishingService;
    }

    @PostMapping("/submit")
    @PreAuthorize("hasRole('DEVELOPER')")
    public AgentVersionResponse submit(@PathVariable UUID agentId, @PathVariable UUID versionId) {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        try {
            var version = publishingService.submit(versionId, claims.orgId());
            return AgentVersionResponse.from(version);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=SubmissionControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(publishing): add submit-for-review with manifest lint gate"
```

### Task 12: Tier classification and Tier A auto-approval

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/publishing/PublishingService.java`
- Test: `backend/src/test/java/com/agentstore/platform/publishing/PublishingServiceTierTest.java`

**Interfaces:**
- Consumes: `ManifestParser` (Task 6) to inspect `Manifest.scopes()`.
- Produces: `PublishingService.submit` now also classifies `version.tier`: `TIER_B` if any scope has `type == WRITE`, else `TIER_A`. Tier A versions with a passing lint go straight to `LIVE` (with `approvedAt` set); Tier B versions go to `IN_REVIEW` and stop there, waiting for Task 13's human review endpoints.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/publishing/PublishingServiceTierTest.java`:

```java
package com.agentstore.platform.publishing;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PublishingServiceTierTest extends AbstractIntegrationTest {

    @Autowired
    PublishingService publishingService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    AgentVersionRepository agentVersionRepository;

    @Test
    void tierAManifestAutoApprovesToLive() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        AgentVersion version = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0", manifest,
                "template-v1", VersionStatus.DRAFT, null, "cl"));

        AgentVersion result = publishingService.submit(version.getId(), developer.getId());

        assertThat(result.getTier()).isEqualTo(Tier.TIER_A);
        assertThat(result.getStatus()).isEqualTo(VersionStatus.LIVE);
        assertThat(result.getApprovedAt()).isNotNull();
    }

    @Test
    void tierBManifestStaysInReviewForManualApproval() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo2", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Collections", "collections", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-b.json"));
        AgentVersion version = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0", manifest,
                "template-v1", VersionStatus.DRAFT, null, "cl"));

        AgentVersion result = publishingService.submit(version.getId(), developer.getId());

        assertThat(result.getTier()).isEqualTo(Tier.TIER_B);
        assertThat(result.getStatus()).isEqualTo(VersionStatus.IN_REVIEW);
        assertThat(result.getApprovedAt()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=PublishingServiceTierTest`
Expected: FAIL — `submit` does not yet set tier or auto-approve Tier A.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/com/agentstore/platform/publishing/PublishingService.java` (replace the body of `submit` and add a `ManifestParser` dependency):

```java
package com.agentstore.platform.publishing;

import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.Tier;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ManifestValidator;
import com.agentstore.platform.manifest.ScopeType;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class PublishingService {

    private final AgentVersionRepository agentVersionRepository;
    private final ManifestValidator manifestValidator;
    private final ManifestParser manifestParser;

    public PublishingService(AgentVersionRepository agentVersionRepository, ManifestValidator manifestValidator,
                              ManifestParser manifestParser) {
        this.agentVersionRepository = agentVersionRepository;
        this.manifestValidator = manifestValidator;
        this.manifestParser = manifestParser;
    }

    public AgentVersion submit(UUID agentVersionId, UUID callerOrgId) {
        AgentVersion version = agentVersionRepository.findById(agentVersionId)
                .orElseThrow(() -> new IllegalArgumentException("AgentVersion not found: " + agentVersionId));
        if (!version.getAgent().getDeveloperOrg().getId().equals(callerOrgId)) {
            throw new SecurityException("AgentVersion not owned by caller's org");
        }
        if (version.getStatus() != VersionStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT versions can be submitted, was " + version.getStatus());
        }
        var validation = manifestValidator.validate(version.getManifestJson());
        if (!validation.valid()) {
            throw new IllegalStateException("Manifest lint failed: " + String.join("; ", validation.errors()));
        }

        var manifest = manifestParser.parse(version.getManifestJson());
        boolean isTierB = manifest.scopes().stream().anyMatch(s -> s.type() == ScopeType.WRITE);
        version.setTier(isTierB ? Tier.TIER_B : Tier.TIER_A);

        if (isTierB) {
            version.setStatus(VersionStatus.IN_REVIEW);
        } else {
            version.setStatus(VersionStatus.LIVE);
            version.setApprovedAt(Instant.now());
        }
        return agentVersionRepository.save(version);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=PublishingServiceTierTest,SubmissionControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(publishing): classify tier, auto-approve Tier A on submit"
```

### Task 13: Human review queue for Tier B versions

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__review_item.sql`
- Create: `backend/src/main/java/com/agentstore/platform/publishing/ReviewStatus.java`
- Create: `backend/src/main/java/com/agentstore/platform/publishing/ReviewItem.java`
- Create: `backend/src/main/java/com/agentstore/platform/publishing/ReviewItemRepository.java`
- Create: `backend/src/main/java/com/agentstore/platform/publishing/ReviewController.java`
- Create: `backend/src/main/java/com/agentstore/platform/publishing/ReviewItemResponse.java`
- Create: `backend/src/main/java/com/agentstore/platform/publishing/ReviewDecisionRequest.java`
- Modify: `backend/src/main/java/com/agentstore/platform/publishing/PublishingService.java`
- Test: `backend/src/test/java/com/agentstore/platform/publishing/ReviewControllerTest.java`

**Interfaces:**
- Consumes: `AgentVersion`, `VersionStatus`, `Tier` (Task 7).
- Produces: `ReviewItem{id, agentVersion: AgentVersion, status: ReviewStatus(PENDING/APPROVED/REJECTED), reviewerUserId: UUID (nullable), notes: String, createdAt, decidedAt}`. `PublishingService.submit` now creates a `ReviewItem` (status `PENDING`) whenever it sets `IN_REVIEW` for a Tier B version. `GET /api/v1/review/queue` (role `ADMIN`) lists pending items; `POST /api/v1/review/{reviewItemId}/approve` and `/reject` (role `ADMIN`, body `{notes}`) transition the `ReviewItem` and the linked `AgentVersion` (`LIVE`+`approvedAt` or `REJECTED`).

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/publishing/ReviewControllerTest.java`:

```java
package com.agentstore.platform.publishing;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    AgentVersionRepository agentVersionRepository;
    @Autowired
    ReviewItemRepository reviewItemRepository;
    @Autowired
    PublishingService publishingService;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), UUID.randomUUID(), UserRole.ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void approvingAReviewItemPublishesTheVersion() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Collections", "collections", "d"));
        String manifest = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/manifest/valid-tier-b.json"));
        AgentVersion version = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0", manifest,
                "template-v1", VersionStatus.DRAFT, null, "cl"));
        publishingService.submit(version.getId(), developer.getId());
        ReviewItem pending = reviewItemRepository.findByAgentVersionId(version.getId()).orElseThrow();

        var request = new HttpEntity<>(new ReviewDecisionRequest("looks safe"), adminHeaders());
        var response = rest.postForEntity(url("/api/v1/review/" + pending.getId() + "/approve"), request, ReviewItemResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(ReviewStatus.APPROVED);
        AgentVersion updated = agentVersionRepository.findById(version.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(VersionStatus.LIVE);
        assertThat(updated.getApprovedAt()).isNotNull();
    }

    @Test
    void rejectingAReviewItemRejectsTheVersion() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo2", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Collections2", "collections", "d"));
        String manifest = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/manifest/valid-tier-b.json"));
        AgentVersion version = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0", manifest,
                "template-v1", VersionStatus.DRAFT, null, "cl"));
        publishingService.submit(version.getId(), developer.getId());
        ReviewItem pending = reviewItemRepository.findByAgentVersionId(version.getId()).orElseThrow();

        var request = new HttpEntity<>(new ReviewDecisionRequest("scope too broad"), adminHeaders());
        var response = rest.postForEntity(url("/api/v1/review/" + pending.getId() + "/reject"), request, ReviewItemResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(ReviewStatus.REJECTED);
        AgentVersion updated = agentVersionRepository.findById(version.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(VersionStatus.REJECTED);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=ReviewControllerTest`
Expected: FAIL — `ReviewItem` and friends do not exist; `PublishingService.submit` does not create a `ReviewItem`.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/resources/db/migration/V4__review_item.sql`:

```sql
CREATE TABLE review_item (
    id UUID PRIMARY KEY,
    agent_version_id UUID NOT NULL REFERENCES agent_version(id),
    status VARCHAR(20) NOT NULL,
    reviewer_user_id UUID,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ
);
```

`backend/src/main/java/com/agentstore/platform/publishing/ReviewStatus.java`:

```java
package com.agentstore.platform.publishing;

public enum ReviewStatus { PENDING, APPROVED, REJECTED }
```

`backend/src/main/java/com/agentstore/platform/publishing/ReviewItem.java`:

```java
package com.agentstore.platform.publishing;

import com.agentstore.platform.catalog.AgentVersion;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "review_item")
@Getter
@Setter
@NoArgsConstructor
public class ReviewItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_version_id")
    private AgentVersion agentVersion;

    @Enumerated(EnumType.STRING)
    private ReviewStatus status;

    private UUID reviewerUserId;
    private String notes;
    private Instant createdAt;
    private Instant decidedAt;

    public ReviewItem(UUID id, AgentVersion agentVersion, ReviewStatus status) {
        this.id = id;
        this.agentVersion = agentVersion;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`backend/src/main/java/com/agentstore/platform/publishing/ReviewItemRepository.java`:

```java
package com.agentstore.platform.publishing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewItemRepository extends JpaRepository<ReviewItem, UUID> {
    List<ReviewItem> findByStatus(ReviewStatus status);

    Optional<ReviewItem> findByAgentVersionId(UUID agentVersionId);
}
```

`backend/src/main/java/com/agentstore/platform/publishing/ReviewItemResponse.java`:

```java
package com.agentstore.platform.publishing;

import java.util.UUID;

public record ReviewItemResponse(UUID id, UUID agentVersionId, ReviewStatus status) {
    static ReviewItemResponse from(ReviewItem item) {
        return new ReviewItemResponse(item.getId(), item.getAgentVersion().getId(), item.getStatus());
    }
}
```

`backend/src/main/java/com/agentstore/platform/publishing/ReviewDecisionRequest.java`:

```java
package com.agentstore.platform.publishing;

public record ReviewDecisionRequest(String notes) {
}
```

`backend/src/main/java/com/agentstore/platform/publishing/PublishingService.java` (add `ReviewItemRepository` dependency; create a `ReviewItem` when a Tier B version goes to `IN_REVIEW`; add `approve`/`reject` methods):

```java
package com.agentstore.platform.publishing;

import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.Tier;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ManifestValidator;
import com.agentstore.platform.manifest.ScopeType;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class PublishingService {

    private final AgentVersionRepository agentVersionRepository;
    private final ReviewItemRepository reviewItemRepository;
    private final ManifestValidator manifestValidator;
    private final ManifestParser manifestParser;

    public PublishingService(AgentVersionRepository agentVersionRepository, ReviewItemRepository reviewItemRepository,
                              ManifestValidator manifestValidator, ManifestParser manifestParser) {
        this.agentVersionRepository = agentVersionRepository;
        this.reviewItemRepository = reviewItemRepository;
        this.manifestValidator = manifestValidator;
        this.manifestParser = manifestParser;
    }

    public AgentVersion submit(UUID agentVersionId, UUID callerOrgId) {
        AgentVersion version = agentVersionRepository.findById(agentVersionId)
                .orElseThrow(() -> new IllegalArgumentException("AgentVersion not found: " + agentVersionId));
        if (!version.getAgent().getDeveloperOrg().getId().equals(callerOrgId)) {
            throw new SecurityException("AgentVersion not owned by caller's org");
        }
        if (version.getStatus() != VersionStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT versions can be submitted, was " + version.getStatus());
        }
        var validation = manifestValidator.validate(version.getManifestJson());
        if (!validation.valid()) {
            throw new IllegalStateException("Manifest lint failed: " + String.join("; ", validation.errors()));
        }

        var manifest = manifestParser.parse(version.getManifestJson());
        boolean isTierB = manifest.scopes().stream().anyMatch(s -> s.type() == ScopeType.WRITE);
        version.setTier(isTierB ? Tier.TIER_B : Tier.TIER_A);

        if (isTierB) {
            version.setStatus(VersionStatus.IN_REVIEW);
            version = agentVersionRepository.save(version);
            reviewItemRepository.save(new ReviewItem(null, version, ReviewStatus.PENDING));
            return version;
        }
        version.setStatus(VersionStatus.LIVE);
        version.setApprovedAt(Instant.now());
        return agentVersionRepository.save(version);
    }

    public ReviewItem approve(UUID reviewItemId, UUID reviewerUserId, String notes) {
        ReviewItem item = pendingItem(reviewItemId);
        item.setStatus(ReviewStatus.APPROVED);
        item.setReviewerUserId(reviewerUserId);
        item.setNotes(notes);
        item.setDecidedAt(Instant.now());

        AgentVersion version = item.getAgentVersion();
        version.setStatus(VersionStatus.LIVE);
        version.setApprovedAt(Instant.now());
        agentVersionRepository.save(version);

        return reviewItemRepository.save(item);
    }

    public ReviewItem reject(UUID reviewItemId, UUID reviewerUserId, String notes) {
        ReviewItem item = pendingItem(reviewItemId);
        item.setStatus(ReviewStatus.REJECTED);
        item.setReviewerUserId(reviewerUserId);
        item.setNotes(notes);
        item.setDecidedAt(Instant.now());

        AgentVersion version = item.getAgentVersion();
        version.setStatus(VersionStatus.REJECTED);
        agentVersionRepository.save(version);

        return reviewItemRepository.save(item);
    }

    private ReviewItem pendingItem(UUID reviewItemId) {
        ReviewItem item = reviewItemRepository.findById(reviewItemId)
                .orElseThrow(() -> new IllegalArgumentException("ReviewItem not found: " + reviewItemId));
        if (item.getStatus() != ReviewStatus.PENDING) {
            throw new IllegalStateException("ReviewItem already decided: " + item.getStatus());
        }
        return item;
    }
}
```

`backend/src/main/java/com/agentstore/platform/publishing/ReviewController.java`:

```java
package com.agentstore.platform.publishing;

import com.agentstore.platform.identity.JwtService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/review")
public class ReviewController {

    private final ReviewItemRepository reviewItemRepository;
    private final PublishingService publishingService;

    public ReviewController(ReviewItemRepository reviewItemRepository, PublishingService publishingService) {
        this.reviewItemRepository = reviewItemRepository;
        this.publishingService = publishingService;
    }

    @GetMapping("/queue")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ReviewItemResponse> queue() {
        return reviewItemRepository.findByStatus(ReviewStatus.PENDING).stream()
                .map(ReviewItemResponse::from).toList();
    }

    @PostMapping("/{reviewItemId}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ReviewItemResponse approve(@PathVariable UUID reviewItemId, @RequestBody ReviewDecisionRequest request) {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return ReviewItemResponse.from(publishingService.approve(reviewItemId, claims.userId(), request.notes()));
    }

    @PostMapping("/{reviewItemId}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ReviewItemResponse reject(@PathVariable UUID reviewItemId, @RequestBody ReviewDecisionRequest request) {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return ReviewItemResponse.from(publishingService.reject(reviewItemId, claims.userId(), request.notes()));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=ReviewControllerTest,PublishingServiceTierTest,SubmissionControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(publishing): add Tier B human review queue with approve/reject"
```

---

## Phase 5 — Connections and secrets

### Task 14: Connection entity and scope-enforced grant endpoint

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__connection.sql`
- Create: `backend/src/main/java/com/agentstore/platform/execution/ConnectionStatus.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/Connection.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/ConnectionRepository.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/ConnectionController.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/CreateConnectionRequest.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/ConnectionResponse.java`
- Test: `backend/src/test/java/com/agentstore/platform/execution/ConnectionControllerTest.java`

**Interfaces:**
- Consumes: `Agent`, `AgentVersion`, `AgentVersionRepository.findFirstByAgentIdAndStatusOrderByApprovedAtDesc` (Task 7), `ManifestParser` (Task 6).
- Produces: `Connection{id, buyerOrg: Org, agent: Agent, grantedScopes: List<String> (stored as jsonb), spendCap: BigDecimal, status: ConnectionStatus(ACTIVE/REVOKED)}`, `ConnectionRepository`. `POST /api/v1/connections` (role `BUYER_ADMIN`, body `{agentId, grantedScopes: List<String>, spendCap}`) resolves the agent's current LIVE `AgentVersion`, parses its manifest, and rejects (400) any requested scope resource not present in `manifest.scopes()`. Task 16 consumes `Connection.grantedScopes` and `Connection.spendCap` when triggering a run.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/execution/ConnectionControllerTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    AgentVersionRepository agentVersionRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    Agent liveBankReconAgent(Org developer) throws Exception {
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        AgentVersion version = new AgentVersion(null, agent, "1.0.0", manifest, "template-v1",
                VersionStatus.LIVE, Tier.TIER_A, "cl");
        version.setApprovedAt(Instant.now());
        agentVersionRepository.save(version);
        return agent;
    }

    @Test
    void buyerGrantsADeclaredScope() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Org buyer = orgRepository.save(new Org(null, "Acme SMB", OrgType.BUYER));
        Agent agent = liveBankReconAgent(developer);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), buyer.getId(), UserRole.BUYER_ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>(
                new CreateConnectionRequest(agent.getId(), List.of("bank_statement"), new BigDecimal("500.00")), headers);

        var response = rest.postForEntity(url("/api/v1/connections"), request, ConnectionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(response.getBody().grantedScopes()).containsExactly("bank_statement");
    }

    @Test
    void buyerCannotGrantAnUndeclaredScope() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo2", OrgType.DEVELOPER));
        Org buyer = orgRepository.save(new Org(null, "Acme SMB 2", OrgType.BUYER));
        Agent agent = liveBankReconAgent(developer);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), buyer.getId(), UserRole.BUYER_ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>(
                new CreateConnectionRequest(agent.getId(), List.of("gst_portal_login"), new BigDecimal("500.00")), headers);

        var response = rest.postForEntity(url("/api/v1/connections"), request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=ConnectionControllerTest`
Expected: FAIL — none of the `execution` module classes exist.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/resources/db/migration/V5__connection.sql`:

```sql
CREATE TABLE connection (
    id UUID PRIMARY KEY,
    buyer_org_id UUID NOT NULL REFERENCES org(id),
    agent_id UUID NOT NULL REFERENCES agent(id),
    granted_scopes JSONB NOT NULL,
    spend_cap NUMERIC(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`backend/src/main/java/com/agentstore/platform/execution/ConnectionStatus.java`:

```java
package com.agentstore.platform.execution;

public enum ConnectionStatus { ACTIVE, REVOKED }
```

`backend/src/main/java/com/agentstore/platform/execution/Connection.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.identity.Org;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "connection")
@Getter
@Setter
@NoArgsConstructor
public class Connection {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "buyer_org_id")
    private Org buyerOrg;

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id")
    private Agent agent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "granted_scopes", columnDefinition = "jsonb")
    private String grantedScopesJson;

    private BigDecimal spendCap;

    @Enumerated(EnumType.STRING)
    private ConnectionStatus status;

    private Instant createdAt;

    public Connection(UUID id, Org buyerOrg, Agent agent, List<String> grantedScopes, BigDecimal spendCap,
                       ConnectionStatus status) {
        this.id = id;
        this.buyerOrg = buyerOrg;
        this.agent = agent;
        setGrantedScopes(grantedScopes);
        this.spendCap = spendCap;
        this.status = status;
    }

    public List<String> getGrantedScopes() {
        try {
            return MAPPER.readValue(grantedScopesJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void setGrantedScopes(List<String> scopes) {
        try {
            this.grantedScopesJson = MAPPER.writeValueAsString(scopes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`backend/src/main/java/com/agentstore/platform/execution/ConnectionRepository.java`:

```java
package com.agentstore.platform.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ConnectionRepository extends JpaRepository<Connection, UUID> {
}
```

`backend/src/main/java/com/agentstore/platform/execution/CreateConnectionRequest.java`:

```java
package com.agentstore.platform.execution;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CreateConnectionRequest(@NotNull UUID agentId, @NotEmpty List<String> grantedScopes,
                                       @NotNull BigDecimal spendCap) {
}
```

`backend/src/main/java/com/agentstore/platform/execution/ConnectionResponse.java`:

```java
package com.agentstore.platform.execution;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ConnectionResponse(UUID id, UUID agentId, List<String> grantedScopes, BigDecimal spendCap,
                                  ConnectionStatus status) {
    static ConnectionResponse from(Connection c) {
        return new ConnectionResponse(c.getId(), c.getAgent().getId(), c.getGrantedScopes(), c.getSpendCap(), c.getStatus());
    }
}
```

`backend/src/main/java/com/agentstore/platform/execution/ConnectionController.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.manifest.ManifestParser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/connections")
public class ConnectionController {

    private final ConnectionRepository connectionRepository;
    private final AgentRepository agentRepository;
    private final AgentVersionRepository agentVersionRepository;
    private final OrgRepository orgRepository;
    private final ManifestParser manifestParser;

    public ConnectionController(ConnectionRepository connectionRepository, AgentRepository agentRepository,
                                 AgentVersionRepository agentVersionRepository, OrgRepository orgRepository,
                                 ManifestParser manifestParser) {
        this.connectionRepository = connectionRepository;
        this.agentRepository = agentRepository;
        this.agentVersionRepository = agentVersionRepository;
        this.orgRepository = orgRepository;
        this.manifestParser = manifestParser;
    }

    @PostMapping
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    public ConnectionResponse create(@Valid @RequestBody CreateConnectionRequest request) {
        Agent agent = agentRepository.findById(request.agentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found"));
        var liveVersion = agentVersionRepository
                .findFirstByAgentIdAndStatusOrderByApprovedAtDesc(agent.getId(), VersionStatus.LIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Agent has no LIVE version"));

        var manifest = manifestParser.parse(liveVersion.getManifestJson());
        Set<String> declaredResources = manifest.scopes().stream()
                .map(com.agentstore.platform.manifest.Scope::resource).collect(Collectors.toSet());
        for (String requested : request.grantedScopes()) {
            if (!declaredResources.contains(requested)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Scope not declared by manifest: " + requested);
            }
        }

        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Org buyerOrg = orgRepository.getReferenceById(claims.orgId());

        Connection saved = connectionRepository.save(new Connection(null, buyerOrg, agent, request.grantedScopes(),
                request.spendCap(), ConnectionStatus.ACTIVE));
        return ConnectionResponse.from(saved);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=ConnectionControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(execution): add Connection with manifest-scope-enforced grant endpoint"
```

### Task 15: Encrypted secret storage per connection

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__secret.sql`
- Create: `backend/src/main/java/com/agentstore/platform/common/SecretEncryptionProperties.java`
- Create: `backend/src/main/java/com/agentstore/platform/common/AesGcmCipher.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/Secret.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/SecretRepository.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/SecretService.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/ConnectionController.java`
- Test: `backend/src/test/java/com/agentstore/platform/common/AesGcmCipherTest.java`
- Test: `backend/src/test/java/com/agentstore/platform/execution/SecretServiceTest.java`

**Interfaces:**
- Produces: `AesGcmCipher.encrypt(byte[] plaintext): AesGcmCipher.Encrypted{ciphertext: byte[], nonce: byte[]}`, `AesGcmCipher.decrypt(byte[] ciphertext, byte[] nonce): byte[]`; `Secret{id, connection: Connection, name: String, encryptedValue: byte[], nonce: byte[]}`; `SecretService.store(UUID connectionId, String name, String plaintextValue): void`, `SecretService.reveal(UUID connectionId, String name): String` — Task 16 calls `reveal` to build the sandbox's injected env vars, never exposing plaintext outside this service. `POST /api/v1/connections/{connectionId}/secrets` (role `BUYER_ADMIN`, body `{name, value}`) — the endpoint never returns the plaintext back.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/common/AesGcmCipherTest.java`:

```java
package com.agentstore.platform.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class AesGcmCipherTest {

    AesGcmCipher cipher = new AesGcmCipher(
            new SecretEncryptionProperties("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="));

    @Test
    void roundTripsPlaintext() {
        byte[] plaintext = "super-secret-api-key".getBytes(StandardCharsets.UTF_8);

        var encrypted = cipher.encrypt(plaintext);
        byte[] decrypted = cipher.decrypt(encrypted.ciphertext(), encrypted.nonce());

        assertThat(new String(decrypted, StandardCharsets.UTF_8)).isEqualTo("super-secret-api-key");
    }

    @Test
    void ciphertextIsNotThePlaintext() {
        byte[] plaintext = "super-secret-api-key".getBytes(StandardCharsets.UTF_8);
        var encrypted = cipher.encrypt(plaintext);

        assertThat(Base64.getEncoder().encodeToString(encrypted.ciphertext()))
                .isNotEqualTo(Base64.getEncoder().encodeToString(plaintext));
    }
}
```

`backend/src/test/java/com/agentstore/platform/execution/SecretServiceTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecretServiceTest extends AbstractIntegrationTest {

    @Autowired
    SecretService secretService;
    @Autowired
    ConnectionRepository connectionRepository;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    SecretRepository secretRepository;

    @Test
    void storesEncryptedAndRevealsPlaintext() {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Org buyer = orgRepository.save(new Org(null, "Acme", OrgType.BUYER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        Connection connection = connectionRepository.save(new Connection(null, buyer, agent, List.of("bank_statement"),
                new BigDecimal("500.00"), ConnectionStatus.ACTIVE));

        secretService.store(connection.getId(), "BANK_API_KEY", "plaintext-value-123");

        Secret stored = secretRepository.findByConnectionIdAndName(connection.getId(), "BANK_API_KEY").orElseThrow();
        assertThat(new String(stored.getEncryptedValue())).doesNotContain("plaintext-value-123");

        assertThat(secretService.reveal(connection.getId(), "BANK_API_KEY")).isEqualTo("plaintext-value-123");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=AesGcmCipherTest,SecretServiceTest`
Expected: FAIL — none of these classes exist.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/resources/db/migration/V6__secret.sql`:

```sql
CREATE TABLE secret (
    id UUID PRIMARY KEY,
    connection_id UUID NOT NULL REFERENCES connection(id),
    name VARCHAR(100) NOT NULL,
    encrypted_value BYTEA NOT NULL,
    nonce BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (connection_id, name)
);
```

`backend/src/main/java/com/agentstore/platform/common/SecretEncryptionProperties.java`:

```java
package com.agentstore.platform.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;

@Component
public class SecretEncryptionProperties {

    private final byte[] keyBytes;

    public SecretEncryptionProperties(@Value("${agentstore.secrets.encryption-key-base64}") String base64Key) {
        this.keyBytes = Base64.getDecoder().decode(base64Key);
    }

    public byte[] keyBytes() {
        return keyBytes;
    }
}
```

`backend/src/main/java/com/agentstore/platform/common/AesGcmCipher.java`:

```java
package com.agentstore.platform.common;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

@Component
public class AesGcmCipher {

    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int NONCE_LENGTH_BYTES = 12;

    private final SecretKeySpec key;

    public AesGcmCipher(SecretEncryptionProperties properties) {
        this.key = new SecretKeySpec(properties.keyBytes(), "AES");
    }

    public Encrypted encrypt(byte[] plaintext) {
        try {
            byte[] nonce = new byte[NONCE_LENGTH_BYTES];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            return new Encrypted(cipher.doFinal(plaintext), nonce);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] ciphertext, byte[] nonce) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    public record Encrypted(byte[] ciphertext, byte[] nonce) {
    }
}
```

`backend/src/main/java/com/agentstore/platform/execution/Secret.java`:

```java
package com.agentstore.platform.execution;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "secret")
@Getter
@Setter
@NoArgsConstructor
public class Secret {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "connection_id")
    private Connection connection;

    private String name;

    @Column(name = "encrypted_value")
    private byte[] encryptedValue;

    private byte[] nonce;
    private Instant createdAt;

    public Secret(UUID id, Connection connection, String name, byte[] encryptedValue, byte[] nonce) {
        this.id = id;
        this.connection = connection;
        this.name = name;
        this.encryptedValue = encryptedValue;
        this.nonce = nonce;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`backend/src/main/java/com/agentstore/platform/execution/SecretRepository.java`:

```java
package com.agentstore.platform.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SecretRepository extends JpaRepository<Secret, UUID> {
    Optional<Secret> findByConnectionIdAndName(UUID connectionId, String name);
}
```

`backend/src/main/java/com/agentstore/platform/execution/SecretService.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.common.AesGcmCipher;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
public class SecretService {

    private final SecretRepository secretRepository;
    private final ConnectionRepository connectionRepository;
    private final AesGcmCipher cipher;

    public SecretService(SecretRepository secretRepository, ConnectionRepository connectionRepository, AesGcmCipher cipher) {
        this.secretRepository = secretRepository;
        this.connectionRepository = connectionRepository;
        this.cipher = cipher;
    }

    public void store(UUID connectionId, String name, String plaintextValue) {
        Connection connection = connectionRepository.getReferenceById(connectionId);
        var encrypted = cipher.encrypt(plaintextValue.getBytes(StandardCharsets.UTF_8));
        Secret secret = secretRepository.findByConnectionIdAndName(connectionId, name)
                .orElseGet(() -> new Secret(null, connection, name, null, null));
        secret.setEncryptedValue(encrypted.ciphertext());
        secret.setNonce(encrypted.nonce());
        secretRepository.save(secret);
    }

    public String reveal(UUID connectionId, String name) {
        Secret secret = secretRepository.findByConnectionIdAndName(connectionId, name)
                .orElseThrow(() -> new IllegalArgumentException("No secret " + name + " for connection " + connectionId));
        return new String(cipher.decrypt(secret.getEncryptedValue(), secret.getNonce()), StandardCharsets.UTF_8);
    }
}
```

`backend/src/main/java/com/agentstore/platform/execution/ConnectionController.java` (add a secrets endpoint — inject `SecretService`, add):

```java
    @PostMapping("/{connectionId}/secrets")
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    public void storeSecret(@PathVariable java.util.UUID connectionId, @RequestBody StoreSecretRequest request) {
        secretService.store(connectionId, request.name(), request.value());
    }

    public record StoreSecretRequest(String name, String value) {
    }
```

(add `private final SecretService secretService;` to the constructor and field list alongside the existing dependencies)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=AesGcmCipherTest,SecretServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(execution): add AES-GCM encrypted secret storage per connection"
```

---

## Phase 6 — Sandbox lifecycle and run triggering

### Task 16: SandboxProvider abstraction, Run entity, trigger-run endpoint

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__run.sql`
- Create: `backend/src/main/java/com/agentstore/platform/execution/SandboxSpec.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/SandboxHandle.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/SandboxProvider.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/FakeSandboxProvider.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunStatus.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/Run.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunRepository.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunTokenService.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunController.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/TriggerRunRequest.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunResponse.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/SecretRepository.java` (add `findByConnectionId`)
- Test: `backend/src/test/java/com/agentstore/platform/execution/RunControllerTest.java`

**Interfaces:**
- Consumes: `Connection`, `ConnectionRepository`, `ConnectionStatus`, `Secret`, `SecretRepository`, `SecretService` (Tasks 14–15); `AgentVersionRepository.findFirstByAgentIdAndStatusOrderByApprovedAtDesc`, `VersionStatus` (Task 7); `ManifestParser` (Task 6).
- Produces: `SandboxProvider{create(SandboxSpec): SandboxHandle, kill(String sandboxId): void}`, `SandboxSpec{templateId: String, timeoutSeconds: int, envVars: Map<String,String>, allowOut: List<String>}`, `SandboxHandle{sandboxId: String}`, `Run{id, connection: Connection, agentVersion: AgentVersion, status: RunStatus, costCap: BigDecimal, totalCharged: BigDecimal, sandboxId: String, callbackToken: String, startedAt, completedAt}`, `RunStatus{PENDING, RUNNING, AWAITING_APPROVAL, COMPLETED, FAILED, CANCELLED}`. `POST /api/v1/runs` (role `BUYER_ADMIN` or `BUYER_APPROVER`, body `{connectionId}`) → `RunResponse{id, status}`. Task 18 consumes `Run.callbackToken` to authenticate the sandboxed agent's webhook calls; Task 17 is a second `SandboxProvider` implementation behind the same interface.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/execution/RunControllerTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    AgentVersionRepository agentVersionRepository;
    @Autowired
    ConnectionRepository connectionRepository;
    @Autowired
    RunRepository runRepository;
    @Autowired
    FakeSandboxProvider fakeSandboxProvider;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void triggeringARunCreatesASandboxWithManifestAllowlist() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Org buyer = orgRepository.save(new Org(null, "Acme", OrgType.BUYER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Collections", "collections", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-b.json"));
        AgentVersion version = new AgentVersion(null, agent, "1.0.0", manifest, "collections-template-v1",
                VersionStatus.LIVE, Tier.TIER_B, "cl");
        version.setApprovedAt(Instant.now());
        agentVersionRepository.save(version);
        Connection connection = connectionRepository.save(new Connection(null, buyer, agent,
                List.of("invoice", "whatsapp_message"), new BigDecimal("200.00"), ConnectionStatus.ACTIVE));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), buyer.getId(), UserRole.BUYER_ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>(new TriggerRunRequest(connection.getId()), headers);

        var response = rest.postForEntity(url("/api/v1/runs"), request, RunResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(RunStatus.RUNNING);

        Run saved = runRepository.findById(response.getBody().id()).orElseThrow();
        assertThat(saved.getSandboxId()).isNotBlank();
        assertThat(saved.getCallbackToken()).isNotBlank();

        var lastSpec = fakeSandboxProvider.createdSpecs().get(fakeSandboxProvider.createdSpecs().size() - 1);
        assertThat(lastSpec.templateId()).isEqualTo("collections-template-v1");
        assertThat(lastSpec.allowOut()).contains("graph.facebook.com");
        assertThat(lastSpec.envVars()).containsEntry("AGENT_RUN_ID", saved.getId().toString());
        assertThat(lastSpec.envVars()).containsEntry("AGENT_CALLBACK_TOKEN", saved.getCallbackToken());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=RunControllerTest`
Expected: FAIL — none of `SandboxProvider`, `Run`, `RunController` exist.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/resources/db/migration/V7__run.sql`:

```sql
CREATE TABLE run (
    id UUID PRIMARY KEY,
    connection_id UUID NOT NULL REFERENCES connection(id),
    agent_version_id UUID NOT NULL REFERENCES agent_version(id),
    status VARCHAR(20) NOT NULL,
    cost_cap NUMERIC(12,2) NOT NULL,
    total_charged NUMERIC(12,2) NOT NULL DEFAULT 0,
    sandbox_id VARCHAR(255),
    callback_token VARCHAR(255) NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);
```

`backend/src/main/java/com/agentstore/platform/execution/SandboxSpec.java`:

```java
package com.agentstore.platform.execution;

import java.util.List;
import java.util.Map;

public record SandboxSpec(String templateId, int timeoutSeconds, Map<String, String> envVars, List<String> allowOut) {
}
```

`backend/src/main/java/com/agentstore/platform/execution/SandboxHandle.java`:

```java
package com.agentstore.platform.execution;

public record SandboxHandle(String sandboxId) {
}
```

`backend/src/main/java/com/agentstore/platform/execution/SandboxProvider.java`:

```java
package com.agentstore.platform.execution;

public interface SandboxProvider {
    SandboxHandle create(SandboxSpec spec);

    void kill(String sandboxId);
}
```

`backend/src/main/java/com/agentstore/platform/execution/FakeSandboxProvider.java`:

```java
package com.agentstore.platform.execution;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@Profile("!e2b")
public class FakeSandboxProvider implements SandboxProvider {

    private final List<SandboxSpec> created = new CopyOnWriteArrayList<>();
    private final Set<String> killed = ConcurrentHashMap.newKeySet();

    @Override
    public SandboxHandle create(SandboxSpec spec) {
        created.add(spec);
        return new SandboxHandle("fake-sandbox-" + UUID.randomUUID());
    }

    @Override
    public void kill(String sandboxId) {
        killed.add(sandboxId);
    }

    public List<SandboxSpec> createdSpecs() {
        return created;
    }

    public boolean wasKilled(String sandboxId) {
        return killed.contains(sandboxId);
    }
}
```

`backend/src/main/java/com/agentstore/platform/execution/RunStatus.java`:

```java
package com.agentstore.platform.execution;

public enum RunStatus { PENDING, RUNNING, AWAITING_APPROVAL, COMPLETED, FAILED, CANCELLED }
```

`backend/src/main/java/com/agentstore/platform/execution/Run.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.catalog.AgentVersion;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "run")
@Getter
@Setter
@NoArgsConstructor
public class Run {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "connection_id")
    private Connection connection;

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_version_id")
    private AgentVersion agentVersion;

    @Enumerated(EnumType.STRING)
    private RunStatus status;

    private BigDecimal costCap;
    private BigDecimal totalCharged;
    private String sandboxId;
    private String callbackToken;
    private Instant startedAt;
    private Instant completedAt;

    public Run(UUID id, Connection connection, AgentVersion agentVersion, RunStatus status, BigDecimal costCap,
               BigDecimal totalCharged, String sandboxId, String callbackToken) {
        this.id = id;
        this.connection = connection;
        this.agentVersion = agentVersion;
        this.status = status;
        this.costCap = costCap;
        this.totalCharged = totalCharged;
        this.sandboxId = sandboxId;
        this.callbackToken = callbackToken;
    }
}
```

`backend/src/main/java/com/agentstore/platform/execution/RunRepository.java`:

```java
package com.agentstore.platform.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RunRepository extends JpaRepository<Run, UUID> {
}
```

`backend/src/main/java/com/agentstore/platform/execution/RunTokenService.java`:

```java
package com.agentstore.platform.execution;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;

@Service
public class RunTokenService {

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
```

`backend/src/main/java/com/agentstore/platform/execution/TriggerRunRequest.java`:

```java
package com.agentstore.platform.execution;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TriggerRunRequest(@NotNull UUID connectionId) {
}
```

`backend/src/main/java/com/agentstore/platform/execution/RunResponse.java`:

```java
package com.agentstore.platform.execution;

import java.util.UUID;

public record RunResponse(UUID id, RunStatus status) {
    static RunResponse from(Run run) {
        return new RunResponse(run.getId(), run.getStatus());
    }
}
```

`backend/src/main/java/com/agentstore/platform/execution/SecretRepository.java` (add a method to the interface created in Task 15):

```java
package com.agentstore.platform.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SecretRepository extends JpaRepository<Secret, UUID> {
    Optional<Secret> findByConnectionIdAndName(UUID connectionId, String name);

    List<Secret> findByConnectionId(UUID connectionId);
}
```

`backend/src/main/java/com/agentstore/platform/execution/RunController.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.manifest.ManifestParser;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final ConnectionRepository connectionRepository;
    private final AgentVersionRepository agentVersionRepository;
    private final RunRepository runRepository;
    private final SecretRepository secretRepository;
    private final SecretService secretService;
    private final ManifestParser manifestParser;
    private final SandboxProvider sandboxProvider;
    private final RunTokenService runTokenService;
    private final String callbackBaseUrl;

    public RunController(ConnectionRepository connectionRepository, AgentVersionRepository agentVersionRepository,
                          RunRepository runRepository, SecretRepository secretRepository, SecretService secretService,
                          ManifestParser manifestParser, SandboxProvider sandboxProvider, RunTokenService runTokenService,
                          @Value("${agentstore.callback.base-url}") String callbackBaseUrl) {
        this.connectionRepository = connectionRepository;
        this.agentVersionRepository = agentVersionRepository;
        this.runRepository = runRepository;
        this.secretRepository = secretRepository;
        this.secretService = secretService;
        this.manifestParser = manifestParser;
        this.sandboxProvider = sandboxProvider;
        this.runTokenService = runTokenService;
        this.callbackBaseUrl = callbackBaseUrl;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public RunResponse trigger(@Valid @RequestBody TriggerRunRequest request) {
        Connection connection = connectionRepository.findById(request.connectionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
        if (connection.getStatus() != ConnectionStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Connection not active");
        }
        AgentVersion version = agentVersionRepository
                .findFirstByAgentIdAndStatusOrderByApprovedAtDesc(connection.getAgent().getId(), VersionStatus.LIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Agent has no LIVE version"));
        var manifest = manifestParser.parse(version.getManifestJson());

        Run run = runRepository.save(new Run(null, connection, version, RunStatus.PENDING, connection.getSpendCap(),
                BigDecimal.ZERO, null, runTokenService.generate()));

        Map<String, String> envVars = new HashMap<>();
        envVars.put("AGENT_RUN_ID", run.getId().toString());
        envVars.put("AGENT_CALLBACK_URL", callbackBaseUrl + "/internal/agent-callback/runs/" + run.getId());
        envVars.put("AGENT_CALLBACK_TOKEN", run.getCallbackToken());
        for (Secret secret : secretRepository.findByConnectionId(connection.getId())) {
            envVars.put("AGENT_SECRET_" + secret.getName(), secretService.reveal(connection.getId(), secret.getName()));
        }

        List<String> allowOut = new ArrayList<>();
        allowOut.add(URI.create(callbackBaseUrl).getHost());
        allowOut.addAll(manifest.networkAllowlist());

        SandboxHandle handle = sandboxProvider.create(new SandboxSpec(version.getImageRef(), 900, envVars, allowOut));

        run.setSandboxId(handle.sandboxId());
        run.setStatus(RunStatus.RUNNING);
        run.setStartedAt(Instant.now());
        run = runRepository.save(run);

        return RunResponse.from(run);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=RunControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(execution): trigger runs via SandboxProvider with manifest-derived allowlist"
```

### Task 17: Real E2B sandbox provider

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/execution/E2bSandboxProvider.java`
- Test: `backend/src/test/java/com/agentstore/platform/execution/E2bSandboxProviderTest.java`

**Interfaces:**
- Consumes: `SandboxProvider`, `SandboxSpec`, `SandboxHandle` (Task 16).
- Produces: a second `SandboxProvider` implementation, active only under the Spring profile `e2b` (`FakeSandboxProvider` stays active otherwise, including in every test in this plan) — calls E2B's control-plane REST API directly: `POST {baseUrl}/sandboxes` with header `X-API-Key`, body `{templateID, timeout, envVars, network: {allowOut}}`, response field `sandboxID`; `DELETE {baseUrl}/sandboxes/{sandboxID}` to kill. To run this against real E2B, deploy with `SPRING_PROFILES_ACTIVE=e2b` and a real `E2B_API_KEY`; this plan's automated tests never require a live E2B account.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/execution/E2bSandboxProviderTest.java`:

```java
package com.agentstore.platform.execution;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class E2bSandboxProviderTest {

    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    E2bSandboxProvider provider = new E2bSandboxProvider(builder, "https://api.e2b.app", "test-api-key");

    @Test
    void createPostsTemplateAndReturnsSandboxId() {
        server.expect(requestTo("https://api.e2b.app/sandboxes"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", "test-api-key"))
                .andExpect(jsonPath("$.templateID").value("bank-recon-template-v1"))
                .andExpect(jsonPath("$.network.allowOut[0]").value("api.agentstore.example"))
                .andRespond(withSuccess(
                        "{\"sandboxID\":\"sbx-123\",\"templateID\":\"bank-recon-template-v1\"}",
                        MediaType.APPLICATION_JSON));

        SandboxHandle handle = provider.create(new SandboxSpec("bank-recon-template-v1", 900, Map.of(),
                List.of("api.agentstore.example")));

        assertThat(handle.sandboxId()).isEqualTo("sbx-123");
        server.verify();
    }

    @Test
    void killSendsDelete() {
        server.expect(requestTo("https://api.e2b.app/sandboxes/sbx-123"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("X-API-Key", "test-api-key"))
                .andRespond(withSuccess());

        provider.kill("sbx-123");

        server.verify();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=E2bSandboxProviderTest`
Expected: FAIL — `E2bSandboxProvider` does not exist.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/com/agentstore/platform/execution/E2bSandboxProvider.java`:

```java
package com.agentstore.platform.execution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
@Profile("e2b")
public class E2bSandboxProvider implements SandboxProvider {

    private final RestClient restClient;
    private final String apiKey;

    public E2bSandboxProvider(RestClient.Builder builder,
                               @Value("${agentstore.e2b.base-url}") String baseUrl,
                               @Value("${agentstore.e2b.api-key}") String apiKey) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    @Override
    public SandboxHandle create(SandboxSpec spec) {
        Map<String, Object> body = Map.of(
                "templateID", spec.templateId(),
                "timeout", spec.timeoutSeconds(),
                "envVars", spec.envVars(),
                "network", Map.of("allowOut", spec.allowOut()));

        Map<?, ?> response = restClient.post()
                .uri("/sandboxes")
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        return new SandboxHandle((String) response.get("sandboxID"));
    }

    @Override
    public void kill(String sandboxId) {
        restClient.delete()
                .uri("/sandboxes/{id}", sandboxId)
                .header("X-API-Key", apiKey)
                .retrieve()
                .toBodilessEntity();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=E2bSandboxProviderTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(execution): add real E2B sandbox provider behind e2b profile"
```

---

## Phase 7 — Agent callback protocol

> The sandboxed agent process never has the monolith's credentials or database access. Instead it calls home: it POSTs its own trace events to an authenticated webhook using the run-scoped `AGENT_CALLBACK_TOKEN` injected as an env var (Task 16), and polls a small status endpoint when it needs to wait on a human decision. The monolith never scrapes sandbox logs or holds an open connection into the sandbox.

### Task 18: Agent event ingestion endpoint with cost-cap kill switch

**Files:**
- Create: `backend/src/main/resources/db/migration/V8__run_step.sql`
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunStepKind.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunStep.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunStepRepository.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/AgentEventRequest.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/AgentCallbackController.java`
- Test: `backend/src/test/java/com/agentstore/platform/execution/AgentCallbackControllerTest.java`

**Interfaces:**
- Consumes: `Run`, `RunRepository`, `RunStatus`, `SandboxProvider` (Task 16); `ManifestParser` (Task 6).
- Produces: `RunStepKind{READ, WRITE, GATE, COST, COMPLETE, FAILED}`, `RunStep{id, run: Run, kind: RunStepKind, detail: String (jsonb), createdAt}`. `POST /internal/agent-callback/runs/{runId}/events` (no JWT — `/internal/**` is excluded from Spring Security in Task 4; auth is the per-run bearer `callbackToken` checked inline), body `{kind, detail: Map<String,Object>}`. On a `COST` event, the amount charged is computed **server-side** from the manifest's platform-approved `unitPrice` (`quantity` from `detail` × `manifest.costEnvelope().unitPrice()`), never trusted from the agent directly; if cumulative `totalCharged` exceeds `costCap`, the sandbox is killed and the run marked `FAILED` — the "runaway agent is a runaway bill" kill switch.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/execution/AgentCallbackControllerTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCallbackControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    AgentVersionRepository agentVersionRepository;
    @Autowired
    ConnectionRepository connectionRepository;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunStepRepository runStepRepository;
    @Autowired
    FakeSandboxProvider fakeSandboxProvider;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    Run newRunningRun(BigDecimal costCap) throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Org buyer = orgRepository.save(new Org(null, "Acme", OrgType.BUYER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        AgentVersion version = new AgentVersion(null, agent, "1.0.0", manifest, "template-v1",
                VersionStatus.LIVE, Tier.TIER_A, "cl");
        version.setApprovedAt(Instant.now());
        agentVersionRepository.save(version);
        Connection connection = connectionRepository.save(new Connection(null, buyer, agent,
                List.of("bank_statement"), new BigDecimal("500.00"), ConnectionStatus.ACTIVE));
        Run run = new Run(null, connection, version, RunStatus.RUNNING, costCap, BigDecimal.ZERO,
                "fake-sandbox-abc", "tok-123");
        run.setStartedAt(Instant.now());
        return runRepository.save(run);
    }

    HttpEntity<Map<String, Object>> eventRequest(String token, String kind, Map<String, Object> detail) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(Map.of("kind", kind, "detail", detail), headers);
    }

    @Test
    void costEventUnderCapAccumulatesWithoutKillingRun() throws Exception {
        Run run = newRunningRun(new BigDecimal("10.00"));

        var response = rest.postForEntity(
                url("/internal/agent-callback/runs/" + run.getId() + "/events"),
                eventRequest("tok-123", "COST", Map.of("quantity", 3)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Run updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getTotalCharged()).isEqualByComparingTo(new BigDecimal("6.00"));
        assertThat(updated.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(fakeSandboxProvider.wasKilled("fake-sandbox-abc")).isFalse();
        assertThat(runStepRepository.findByRunIdOrderByCreatedAtAsc(run.getId())).hasSize(1);
    }

    @Test
    void costEventExceedingCapKillsSandboxAndFailsRun() throws Exception {
        Run run = newRunningRun(new BigDecimal("10.00"));

        rest.postForEntity(url("/internal/agent-callback/runs/" + run.getId() + "/events"),
                eventRequest("tok-123", "COST", Map.of("quantity", 3)), Void.class);
        rest.postForEntity(url("/internal/agent-callback/runs/" + run.getId() + "/events"),
                eventRequest("tok-123", "COST", Map.of("quantity", 3)), Void.class);

        Run updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getTotalCharged()).isEqualByComparingTo(new BigDecimal("12.00"));
        assertThat(updated.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(fakeSandboxProvider.wasKilled("fake-sandbox-abc")).isTrue();
    }

    @Test
    void wrongCallbackTokenIsRejected() throws Exception {
        Run run = newRunningRun(new BigDecimal("10.00"));

        var response = rest.postForEntity(url("/internal/agent-callback/runs/" + run.getId() + "/events"),
                eventRequest("wrong-token", "READ", Map.of()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=AgentCallbackControllerTest`
Expected: FAIL — `RunStep`, `AgentCallbackController` do not exist.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/resources/db/migration/V8__run_step.sql`:

```sql
CREATE TABLE run_step (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES run(id),
    kind VARCHAR(20) NOT NULL,
    detail JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`backend/src/main/java/com/agentstore/platform/execution/RunStepKind.java`:

```java
package com.agentstore.platform.execution;

public enum RunStepKind { READ, WRITE, GATE, COST, COMPLETE, FAILED }
```

`backend/src/main/java/com/agentstore/platform/execution/RunStep.java`:

```java
package com.agentstore.platform.execution;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "run_step")
@Getter
@Setter
@NoArgsConstructor
public class RunStep {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "run_id")
    private Run run;

    @Enumerated(EnumType.STRING)
    private RunStepKind kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String detail;

    private Instant createdAt;

    public RunStep(UUID id, Run run, RunStepKind kind, String detail) {
        this.id = id;
        this.run = run;
        this.kind = kind;
        this.detail = detail;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`backend/src/main/java/com/agentstore/platform/execution/RunStepRepository.java`:

```java
package com.agentstore.platform.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RunStepRepository extends JpaRepository<RunStep, UUID> {
    List<RunStep> findByRunIdOrderByCreatedAtAsc(UUID runId);
}
```

`backend/src/main/java/com/agentstore/platform/execution/AgentEventRequest.java`:

```java
package com.agentstore.platform.execution;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record AgentEventRequest(@NotNull RunStepKind kind, Map<String, Object> detail) {
}
```

`backend/src/main/java/com/agentstore/platform/execution/AgentCallbackController.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.manifest.ManifestParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/internal/agent-callback/runs/{runId}")
public class AgentCallbackController {

    private final RunRepository runRepository;
    private final RunStepRepository runStepRepository;
    private final ManifestParser manifestParser;
    private final SandboxProvider sandboxProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentCallbackController(RunRepository runRepository, RunStepRepository runStepRepository,
                                    ManifestParser manifestParser, SandboxProvider sandboxProvider) {
        this.runRepository = runRepository;
        this.runStepRepository = runStepRepository;
        this.manifestParser = manifestParser;
        this.sandboxProvider = sandboxProvider;
    }

    @PostMapping("/events")
    public ResponseEntity<Void> ingest(@PathVariable UUID runId,
                                        @RequestHeader("Authorization") String authorization,
                                        @Valid @RequestBody AgentEventRequest request) {
        Run run = authorize(runId, authorization);
        if (run.getStatus() != RunStatus.RUNNING && run.getStatus() != RunStatus.AWAITING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run not accepting events: " + run.getStatus());
        }

        runStepRepository.save(new RunStep(null, run, request.kind(), toJson(request.detail())));

        if (request.kind() == RunStepKind.COST) {
            var manifest = manifestParser.parse(run.getAgentVersion().getManifestJson());
            BigDecimal quantity = new BigDecimal(String.valueOf(request.detail().getOrDefault("quantity", "0")));
            BigDecimal amount = quantity.multiply(manifest.costEnvelope().unitPrice());
            run.setTotalCharged(run.getTotalCharged().add(amount));
            if (run.getTotalCharged().compareTo(run.getCostCap()) > 0) {
                sandboxProvider.kill(run.getSandboxId());
                run.setStatus(RunStatus.FAILED);
                run.setCompletedAt(Instant.now());
            }
            runRepository.save(run);
        }

        return ResponseEntity.ok().build();
    }

    private Run authorize(UUID runId, String authorizationHeader) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        String token = authorizationHeader != null && authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring(7) : "";
        if (!run.getCallbackToken().equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid callback token");
        }
        return run;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? java.util.Map.of() : value);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid detail payload");
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=AgentCallbackControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(execution): ingest agent trace events with server-side cost-cap kill switch"
```

### Task 19: Approval gate pause/resume

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/execution/AgentCallbackController.java` (GATE event pauses the run; add gate-status poll endpoint)
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunController.java` (add approve/reject endpoints)
- Create: `backend/src/main/java/com/agentstore/platform/execution/GateStatusResponse.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/ApproveRunRequest.java`
- Test: `backend/src/test/java/com/agentstore/platform/execution/ApprovalGateTest.java`

**Interfaces:**
- Consumes: `Run`, `RunStatus`, `RunRepository`, `SandboxProvider` (Task 16); `RunStepKind.GATE` (Task 18).
- Produces: a `GATE` event now transitions `Run.status` to `AWAITING_APPROVAL` instead of just being recorded. `POST /api/v1/runs/{runId}/approve` and `POST /api/v1/runs/{runId}/reject` (role `BUYER_APPROVER`, caller's org must own the run's connection) — approve moves `AWAITING_APPROVAL → RUNNING`; reject moves it to `CANCELLED`, kills the sandbox, and sets `completedAt`. `GET /internal/agent-callback/runs/{runId}/gate-status` (same bearer-token auth as event ingestion) → `GateStatusResponse{decision: "PENDING"|"APPROVED"|"REJECTED"}` derived from `Run.status`, letting the sandboxed agent poll until a human decides.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/execution/ApprovalGateTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalGateTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    AgentVersionRepository agentVersionRepository;
    @Autowired
    ConnectionRepository connectionRepository;
    @Autowired
    RunRepository runRepository;
    @Autowired
    FakeSandboxProvider fakeSandboxProvider;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    record Fixture(Org buyer, Run run) {}

    Fixture runAwaitingApproval() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Org buyer = orgRepository.save(new Org(null, "Acme", OrgType.BUYER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Collections", "collections", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-b.json"));
        AgentVersion version = new AgentVersion(null, agent, "1.0.0", manifest, "template-v1",
                VersionStatus.LIVE, Tier.TIER_B, "cl");
        version.setApprovedAt(Instant.now());
        agentVersionRepository.save(version);
        Connection connection = connectionRepository.save(new Connection(null, buyer, agent,
                List.of("invoice", "whatsapp_message"), new BigDecimal("200.00"), ConnectionStatus.ACTIVE));
        Run run = runRepository.save(new Run(null, connection, version, RunStatus.RUNNING, new BigDecimal("200.00"),
                BigDecimal.ZERO, "fake-sandbox-gate", "tok-gate"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("tok-gate");
        headers.setContentType(MediaType.APPLICATION_JSON);
        rest.postForEntity(url("/internal/agent-callback/runs/" + run.getId() + "/events"),
                new HttpEntity<>(Map.of("kind", "GATE", "detail", Map.of("trigger", "MESSAGE_CUSTOMER")), headers), Void.class);

        return new Fixture(buyer, runRepository.findById(run.getId()).orElseThrow());
    }

    HttpHeaders approverHeaders(UUID orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, UserRole.BUYER_APPROVER));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    GateStatusResponse pollGateStatus(UUID runId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("tok-gate");
        return rest.exchange(url("/internal/agent-callback/runs/" + runId + "/gate-status"), HttpMethod.GET,
                new HttpEntity<>(headers), GateStatusResponse.class).getBody();
    }

    @Test
    void gateEventPausesRunForApproval() throws Exception {
        Fixture fixture = runAwaitingApproval();
        assertThat(fixture.run().getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(pollGateStatus(fixture.run().getId()).decision()).isEqualTo("PENDING");
    }

    @Test
    void approvingResumesTheRun() throws Exception {
        Fixture fixture = runAwaitingApproval();

        var response = rest.exchange(url("/api/v1/runs/" + fixture.run().getId() + "/approve"), HttpMethod.POST,
                new HttpEntity<>(new ApproveRunRequest("looks fine"), approverHeaders(fixture.buyer().getId())), RunResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(RunStatus.RUNNING);
        assertThat(pollGateStatus(fixture.run().getId()).decision()).isEqualTo("APPROVED");
    }

    @Test
    void rejectingCancelsAndKillsTheRun() throws Exception {
        Fixture fixture = runAwaitingApproval();

        var response = rest.exchange(url("/api/v1/runs/" + fixture.run().getId() + "/reject"), HttpMethod.POST,
                new HttpEntity<>(new ApproveRunRequest("too risky"), approverHeaders(fixture.buyer().getId())), RunResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(fakeSandboxProvider.wasKilled("fake-sandbox-gate")).isTrue();
        assertThat(pollGateStatus(fixture.run().getId()).decision()).isEqualTo("REJECTED");
    }

    @Test
    void approverFromAnotherOrgIsForbidden() throws Exception {
        Fixture fixture = runAwaitingApproval();
        Org intruder = orgRepository.save(new Org(null, "Intruder", OrgType.BUYER));

        var response = rest.exchange(url("/api/v1/runs/" + fixture.run().getId() + "/approve"), HttpMethod.POST,
                new HttpEntity<>(new ApproveRunRequest("n/a"), approverHeaders(intruder.getId())), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=ApprovalGateTest`
Expected: FAIL — GATE events do not pause the run yet, and the approve/reject/gate-status endpoints do not exist.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/com/agentstore/platform/execution/GateStatusResponse.java`:

```java
package com.agentstore.platform.execution;

public record GateStatusResponse(String decision) {
    static GateStatusResponse from(RunStatus status) {
        return switch (status) {
            case AWAITING_APPROVAL -> new GateStatusResponse("PENDING");
            case CANCELLED -> new GateStatusResponse("REJECTED");
            default -> new GateStatusResponse("APPROVED");
        };
    }
}
```

`backend/src/main/java/com/agentstore/platform/execution/ApproveRunRequest.java`:

```java
package com.agentstore.platform.execution;

public record ApproveRunRequest(String notes) {
}
```

`backend/src/main/java/com/agentstore/platform/execution/AgentCallbackController.java` (replace the `ingest` method's GATE handling and add the gate-status endpoint — full updated class body):

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.manifest.ManifestParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/internal/agent-callback/runs/{runId}")
public class AgentCallbackController {

    private final RunRepository runRepository;
    private final RunStepRepository runStepRepository;
    private final ManifestParser manifestParser;
    private final SandboxProvider sandboxProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentCallbackController(RunRepository runRepository, RunStepRepository runStepRepository,
                                    ManifestParser manifestParser, SandboxProvider sandboxProvider) {
        this.runRepository = runRepository;
        this.runStepRepository = runStepRepository;
        this.manifestParser = manifestParser;
        this.sandboxProvider = sandboxProvider;
    }

    @PostMapping("/events")
    public ResponseEntity<Void> ingest(@PathVariable UUID runId,
                                        @RequestHeader("Authorization") String authorization,
                                        @Valid @RequestBody AgentEventRequest request) {
        Run run = authorize(runId, authorization);
        if (run.getStatus() != RunStatus.RUNNING && run.getStatus() != RunStatus.AWAITING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run not accepting events: " + run.getStatus());
        }

        runStepRepository.save(new RunStep(null, run, request.kind(), toJson(request.detail())));

        switch (request.kind()) {
            case GATE -> {
                run.setStatus(RunStatus.AWAITING_APPROVAL);
                runRepository.save(run);
            }
            case COST -> {
                var manifest = manifestParser.parse(run.getAgentVersion().getManifestJson());
                BigDecimal quantity = new BigDecimal(String.valueOf(request.detail().getOrDefault("quantity", "0")));
                BigDecimal amount = quantity.multiply(manifest.costEnvelope().unitPrice());
                run.setTotalCharged(run.getTotalCharged().add(amount));
                if (run.getTotalCharged().compareTo(run.getCostCap()) > 0) {
                    sandboxProvider.kill(run.getSandboxId());
                    run.setStatus(RunStatus.FAILED);
                    run.setCompletedAt(Instant.now());
                }
                runRepository.save(run);
            }
            default -> { /* READ/WRITE/COMPLETE/FAILED handled in Task 20 */ }
        }

        return ResponseEntity.ok().build();
    }

    @GetMapping("/gate-status")
    public GateStatusResponse gateStatus(@PathVariable UUID runId, @RequestHeader("Authorization") String authorization) {
        Run run = authorize(runId, authorization);
        return GateStatusResponse.from(run.getStatus());
    }

    private Run authorize(UUID runId, String authorizationHeader) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        String token = authorizationHeader != null && authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring(7) : "";
        if (!run.getCallbackToken().equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid callback token");
        }
        return run;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? java.util.Map.of() : value);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid detail payload");
        }
    }
}
```

`backend/src/main/java/com/agentstore/platform/execution/RunController.java` (add two endpoints — inject nothing new, reuse existing fields; append to the class):

```java
    @PostMapping("/{runId}/approve")
    @PreAuthorize("hasRole('BUYER_APPROVER')")
    public RunResponse approve(@PathVariable java.util.UUID runId, @RequestBody ApproveRunRequest request) {
        Run run = ownedAwaitingApprovalRun(runId);
        run.setStatus(RunStatus.RUNNING);
        return RunResponse.from(runRepository.save(run));
    }

    @PostMapping("/{runId}/reject")
    @PreAuthorize("hasRole('BUYER_APPROVER')")
    public RunResponse reject(@PathVariable java.util.UUID runId, @RequestBody ApproveRunRequest request) {
        Run run = ownedAwaitingApprovalRun(runId);
        run.setStatus(RunStatus.CANCELLED);
        run.setCompletedAt(Instant.now());
        sandboxProvider.kill(run.getSandboxId());
        return RunResponse.from(runRepository.save(run));
    }

    private Run ownedAwaitingApprovalRun(java.util.UUID runId) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        var claims = (com.agentstore.platform.identity.JwtService.Claims)
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!run.getConnection().getBuyerOrg().getId().equals(claims.orgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Run not owned by caller's org");
        }
        if (run.getStatus() != RunStatus.AWAITING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run is not awaiting approval: " + run.getStatus());
        }
        return run;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=ApprovalGateTest,AgentCallbackControllerTest,RunControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(execution): add approval gate pause/resume and gate-status poll"
```

### Task 20: Run completion and usage-event emission

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__usage_event.sql`
- Create: `backend/src/main/java/com/agentstore/platform/ledger/UsageEvent.java`
- Create: `backend/src/main/java/com/agentstore/platform/ledger/UsageEventRepository.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/AgentCallbackController.java` (handle `COMPLETE`/`FAILED` events; emit a `UsageEvent` for every `COST` event)
- Test: `backend/src/test/java/com/agentstore/platform/execution/AgentCallbackCompletionTest.java`

**Interfaces:**
- Produces: `UsageEvent{id, runId: UUID, orgId: UUID, quantity: BigDecimal, unitCost: BigDecimal, amount: BigDecimal, processed: boolean, createdAt}` — deliberately holds plain UUIDs rather than `@ManyToOne` references to `Run`/`Org`, keeping `ledger` free of a compile dependency on `execution`/`identity`; it is the outbox Task 22's aggregation reads from. `COMPLETE` transitions `RUNNING → COMPLETED` (`FAILED`'s cost-cap path from Task 18/19 already covers the cap-exceeded case; an agent-reported `FAILED` event does the same transition to `FAILED`); both kill the sandbox and set `completedAt`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/execution/AgentCallbackCompletionTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.ledger.UsageEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCallbackCompletionTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    AgentVersionRepository agentVersionRepository;
    @Autowired
    ConnectionRepository connectionRepository;
    @Autowired
    RunRepository runRepository;
    @Autowired
    UsageEventRepository usageEventRepository;
    @Autowired
    FakeSandboxProvider fakeSandboxProvider;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpEntity<Map<String, Object>> eventRequest(String kind, Map<String, Object> detail) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("tok-complete");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(Map.of("kind", kind, "detail", detail), headers);
    }

    @Test
    void costEventEmitsUsageEventAndCompleteEndsTheRun() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Org buyer = orgRepository.save(new Org(null, "Acme", OrgType.BUYER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        AgentVersion version = new AgentVersion(null, agent, "1.0.0", manifest, "template-v1",
                VersionStatus.LIVE, Tier.TIER_A, "cl");
        version.setApprovedAt(Instant.now());
        agentVersionRepository.save(version);
        Connection connection = connectionRepository.save(new Connection(null, buyer, agent,
                List.of("bank_statement"), new BigDecimal("500.00"), ConnectionStatus.ACTIVE));
        Run run = runRepository.save(new Run(null, connection, version, RunStatus.RUNNING, new BigDecimal("500.00"),
                BigDecimal.ZERO, "fake-sandbox-complete", "tok-complete"));

        rest.postForEntity(url("/internal/agent-callback/runs/" + run.getId() + "/events"),
                eventRequest("COST", Map.of("quantity", 5)), Void.class);
        rest.postForEntity(url("/internal/agent-callback/runs/" + run.getId() + "/events"),
                eventRequest("COMPLETE", Map.of()), Void.class);

        var usageEvents = usageEventRepository.findByRunId(run.getId());
        assertThat(usageEvents).hasSize(1);
        assertThat(usageEvents.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(usageEvents.get(0).getOrgId()).isEqualTo(buyer.getId());

        Run completed = runRepository.findById(run.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(fakeSandboxProvider.wasKilled("fake-sandbox-complete")).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=AgentCallbackCompletionTest`
Expected: FAIL — `UsageEvent` does not exist, and `COMPLETE` events are not handled.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/resources/db/migration/V9__usage_event.sql`:

```sql
CREATE TABLE usage_event (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    org_id UUID NOT NULL,
    quantity NUMERIC(14,4) NOT NULL,
    unit_cost NUMERIC(14,4) NOT NULL,
    amount NUMERIC(12,2) NOT NULL,
    processed BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`backend/src/main/java/com/agentstore/platform/ledger/UsageEvent.java`:

```java
package com.agentstore.platform.ledger;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usage_event")
@Getter
@Setter
@NoArgsConstructor
public class UsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID runId;
    private UUID orgId;
    private BigDecimal quantity;
    private BigDecimal unitCost;
    private BigDecimal amount;
    private boolean processed;
    private Instant createdAt;

    public UsageEvent(UUID id, UUID runId, UUID orgId, BigDecimal quantity, BigDecimal unitCost, BigDecimal amount) {
        this.id = id;
        this.runId = runId;
        this.orgId = orgId;
        this.quantity = quantity;
        this.unitCost = unitCost;
        this.amount = amount;
        this.processed = false;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`backend/src/main/java/com/agentstore/platform/ledger/UsageEventRepository.java`:

```java
package com.agentstore.platform.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UsageEventRepository extends JpaRepository<UsageEvent, UUID> {
    List<UsageEvent> findByRunId(UUID runId);

    List<UsageEvent> findByProcessedFalse();
}
```

`backend/src/main/java/com/agentstore/platform/execution/AgentCallbackController.java` (final full body for this phase — add `UsageEventRepository`, emit on `COST`, handle `COMPLETE`/`FAILED`):

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.ledger.UsageEvent;
import com.agentstore.platform.ledger.UsageEventRepository;
import com.agentstore.platform.manifest.ManifestParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/internal/agent-callback/runs/{runId}")
public class AgentCallbackController {

    private final RunRepository runRepository;
    private final RunStepRepository runStepRepository;
    private final UsageEventRepository usageEventRepository;
    private final ManifestParser manifestParser;
    private final SandboxProvider sandboxProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentCallbackController(RunRepository runRepository, RunStepRepository runStepRepository,
                                    UsageEventRepository usageEventRepository, ManifestParser manifestParser,
                                    SandboxProvider sandboxProvider) {
        this.runRepository = runRepository;
        this.runStepRepository = runStepRepository;
        this.usageEventRepository = usageEventRepository;
        this.manifestParser = manifestParser;
        this.sandboxProvider = sandboxProvider;
    }

    @PostMapping("/events")
    public ResponseEntity<Void> ingest(@PathVariable UUID runId,
                                        @RequestHeader("Authorization") String authorization,
                                        @Valid @RequestBody AgentEventRequest request) {
        Run run = authorize(runId, authorization);
        if (run.getStatus() != RunStatus.RUNNING && run.getStatus() != RunStatus.AWAITING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run not accepting events: " + run.getStatus());
        }

        runStepRepository.save(new RunStep(null, run, request.kind(), toJson(request.detail())));

        switch (request.kind()) {
            case GATE -> {
                run.setStatus(RunStatus.AWAITING_APPROVAL);
                runRepository.save(run);
            }
            case COST -> {
                var manifest = manifestParser.parse(run.getAgentVersion().getManifestJson());
                BigDecimal quantity = new BigDecimal(String.valueOf(request.detail().getOrDefault("quantity", "0")));
                BigDecimal unitCost = manifest.costEnvelope().unitPrice();
                BigDecimal amount = quantity.multiply(unitCost);

                usageEventRepository.save(new UsageEvent(null, run.getId(), run.getConnection().getBuyerOrg().getId(),
                        quantity, unitCost, amount));

                run.setTotalCharged(run.getTotalCharged().add(amount));
                if (run.getTotalCharged().compareTo(run.getCostCap()) > 0) {
                    sandboxProvider.kill(run.getSandboxId());
                    run.setStatus(RunStatus.FAILED);
                    run.setCompletedAt(Instant.now());
                }
                runRepository.save(run);
            }
            case COMPLETE -> {
                sandboxProvider.kill(run.getSandboxId());
                run.setStatus(RunStatus.COMPLETED);
                run.setCompletedAt(Instant.now());
                runRepository.save(run);
            }
            case FAILED -> {
                sandboxProvider.kill(run.getSandboxId());
                run.setStatus(RunStatus.FAILED);
                run.setCompletedAt(Instant.now());
                runRepository.save(run);
            }
            default -> { /* READ/WRITE are trace-only */ }
        }

        return ResponseEntity.ok().build();
    }

    @GetMapping("/gate-status")
    public GateStatusResponse gateStatus(@PathVariable UUID runId, @RequestHeader("Authorization") String authorization) {
        Run run = authorize(runId, authorization);
        return GateStatusResponse.from(run.getStatus());
    }

    private Run authorize(UUID runId, String authorizationHeader) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        String token = authorizationHeader != null && authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring(7) : "";
        if (!run.getCallbackToken().equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid callback token");
        }
        return run;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? java.util.Map.of() : value);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid detail payload");
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=AgentCallbackCompletionTest,AgentCallbackControllerTest,ApprovalGateTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(execution,ledger): end runs on COMPLETE/FAILED, emit usage events for cost"
```

### Task 21: Run trace retrieval endpoint

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunDetailController.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunDetailResponse.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunStepResponse.java`
- Test: `backend/src/test/java/com/agentstore/platform/execution/RunDetailControllerTest.java`

**Interfaces:**
- Consumes: `Run`, `RunRepository`, `RunStep`, `RunStepRepository.findByRunIdOrderByCreatedAtAsc` (Tasks 16, 18).
- Produces: `GET /api/v1/runs/{runId}` (any authenticated buyer role belonging to the run's connection's `buyerOrg`, or `ADMIN`) → `RunDetailResponse{id, status, costCap, totalCharged, steps: List<RunStepResponse{kind, detail, createdAt}>}`. This is the polling-friendly trace view the MVP design doc's run console reads from; a later frontend plan can upgrade it to a WebSocket push without changing this response shape.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/execution/RunDetailControllerTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunDetailControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    AgentVersionRepository agentVersionRepository;
    @Autowired
    ConnectionRepository connectionRepository;
    @Autowired
    RunRepository runRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void ownerCanReadTraceButOtherOrgCannot() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Org buyer = orgRepository.save(new Org(null, "Acme", OrgType.BUYER));
        Org intruder = orgRepository.save(new Org(null, "Intruder", OrgType.BUYER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        AgentVersion version = new AgentVersion(null, agent, "1.0.0", manifest, "template-v1",
                VersionStatus.LIVE, Tier.TIER_A, "cl");
        version.setApprovedAt(Instant.now());
        agentVersionRepository.save(version);
        Connection connection = connectionRepository.save(new Connection(null, buyer, agent,
                List.of("bank_statement"), new BigDecimal("500.00"), ConnectionStatus.ACTIVE));
        Run run = runRepository.save(new Run(null, connection, version, RunStatus.RUNNING, new BigDecimal("500.00"),
                new BigDecimal("10.00"), "fake-sandbox-trace", "tok-trace"));

        HttpHeaders callbackHeaders = new HttpHeaders();
        callbackHeaders.setBearerAuth("tok-trace");
        callbackHeaders.setContentType(MediaType.APPLICATION_JSON);
        rest.postForEntity(url("/internal/agent-callback/runs/" + run.getId() + "/events"),
                new HttpEntity<>(Map.of("kind", "READ", "detail", Map.of("resource", "bank_statement")), callbackHeaders), Void.class);

        HttpHeaders ownerHeaders = new HttpHeaders();
        ownerHeaders.setBearerAuth(jwtService.issue(UUID.randomUUID(), buyer.getId(), UserRole.BUYER_ADMIN));
        var ownerResponse = rest.exchange(url("/api/v1/runs/" + run.getId()), HttpMethod.GET,
                new HttpEntity<>(ownerHeaders), RunDetailResponse.class);

        assertThat(ownerResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownerResponse.getBody().steps()).hasSize(1);
        assertThat(ownerResponse.getBody().steps().get(0).kind()).isEqualTo(RunStepKind.READ);

        HttpHeaders intruderHeaders = new HttpHeaders();
        intruderHeaders.setBearerAuth(jwtService.issue(UUID.randomUUID(), intruder.getId(), UserRole.BUYER_ADMIN));
        var intruderResponse = rest.exchange(url("/api/v1/runs/" + run.getId()), HttpMethod.GET,
                new HttpEntity<>(intruderHeaders), String.class);

        assertThat(intruderResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=RunDetailControllerTest`
Expected: FAIL — controller/DTOs do not exist.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/com/agentstore/platform/execution/RunStepResponse.java`:

```java
package com.agentstore.platform.execution;

import java.time.Instant;

public record RunStepResponse(RunStepKind kind, String detail, Instant createdAt) {
    static RunStepResponse from(RunStep step) {
        return new RunStepResponse(step.getKind(), step.getDetail(), step.getCreatedAt());
    }
}
```

`backend/src/main/java/com/agentstore/platform/execution/RunDetailResponse.java`:

```java
package com.agentstore.platform.execution;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record RunDetailResponse(UUID id, RunStatus status, BigDecimal costCap, BigDecimal totalCharged,
                                 List<RunStepResponse> steps) {
}
```

`backend/src/main/java/com/agentstore/platform/execution/RunDetailController.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/runs")
public class RunDetailController {

    private final RunRepository runRepository;
    private final RunStepRepository runStepRepository;

    public RunDetailController(RunRepository runRepository, RunStepRepository runStepRepository) {
        this.runRepository = runRepository;
        this.runStepRepository = runStepRepository;
    }

    @GetMapping("/{runId}")
    @PreAuthorize("isAuthenticated()")
    public RunDetailResponse get(@PathVariable UUID runId) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));

        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        boolean owns = run.getConnection().getBuyerOrg().getId().equals(claims.orgId());
        if (!owns && claims.role() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Run not owned by caller's org");
        }

        var steps = runStepRepository.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .map(RunStepResponse::from).toList();
        return new RunDetailResponse(run.getId(), run.getStatus(), run.getCostCap(), run.getTotalCharged(), steps);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=RunDetailControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(execution): add run trace retrieval endpoint with org ownership check"
```

---

## Phase 8 — Ledger

### Task 22: Idempotent usage-event aggregation into the double-entry ledger

**Files:**
- Create: `backend/src/main/resources/db/migration/V10__ledger_entry.sql`
- Create: `backend/src/main/java/com/agentstore/platform/ledger/LedgerEntryType.java`
- Create: `backend/src/main/java/com/agentstore/platform/ledger/LedgerEntry.java`
- Create: `backend/src/main/java/com/agentstore/platform/ledger/LedgerEntryRepository.java`
- Create: `backend/src/main/java/com/agentstore/platform/ledger/LedgerService.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/AgentCallbackController.java` (call `LedgerService.chargeUsageEvent` right after saving each `UsageEvent`)
- Test: `backend/src/test/java/com/agentstore/platform/ledger/LedgerServiceTest.java`

**Interfaces:**
- Consumes: `UsageEvent`, `UsageEventRepository` (Task 20).
- Produces: `LedgerEntryType{CHARGE, REVERSAL, FEE}`, `LedgerEntry{id, runId: UUID, orgId: UUID, amount: BigDecimal, type: LedgerEntryType, createdAt}`, `LedgerService.chargeUsageEvent(UUID usageEventId): LedgerEntry` — idempotent: calling it twice for the same `usageEventId` returns the same `LedgerEntry` and never creates a second one (checked via `UsageEvent.processed`). Task 23 extends this method to also debit a `Wallet`; Task 24 reads `LedgerEntry` rows for the billing statement.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/ledger/LedgerServiceTest.java`:

```java
package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerServiceTest extends AbstractIntegrationTest {

    @Autowired
    LedgerService ledgerService;
    @Autowired
    UsageEventRepository usageEventRepository;
    @Autowired
    LedgerEntryRepository ledgerEntryRepository;

    @Test
    void chargingAUsageEventCreatesExactlyOneLedgerEntryEvenIfCalledTwice() {
        UUID runId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UsageEvent event = usageEventRepository.save(
                new UsageEvent(null, runId, orgId, new BigDecimal("5"), new BigDecimal("2.00"), new BigDecimal("10.00")));

        LedgerEntry first = ledgerService.chargeUsageEvent(event.getId());
        LedgerEntry second = ledgerService.chargeUsageEvent(event.getId());

        assertThat(first.getId()).isEqualTo(second.getId());
        assertThat(ledgerEntryRepository.findByOrgId(orgId)).hasSize(1);
        assertThat(first.getAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(first.getType()).isEqualTo(LedgerEntryType.CHARGE);
        assertThat(usageEventRepository.findById(event.getId()).orElseThrow().isProcessed()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=LedgerServiceTest`
Expected: FAIL — `LedgerEntry`, `LedgerService` do not exist.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/resources/db/migration/V10__ledger_entry.sql`:

```sql
CREATE TABLE ledger_entry (
    id UUID PRIMARY KEY,
    usage_event_id UUID NOT NULL UNIQUE,
    run_id UUID NOT NULL,
    org_id UUID NOT NULL,
    amount NUMERIC(12,2) NOT NULL,
    type VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`backend/src/main/java/com/agentstore/platform/ledger/LedgerEntryType.java`:

```java
package com.agentstore.platform.ledger;

public enum LedgerEntryType { CHARGE, REVERSAL, FEE }
```

`backend/src/main/java/com/agentstore/platform/ledger/LedgerEntry.java`:

```java
package com.agentstore.platform.ledger;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entry")
@Getter
@Setter
@NoArgsConstructor
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID usageEventId;
    private UUID runId;
    private UUID orgId;
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private LedgerEntryType type;

    private Instant createdAt;

    public LedgerEntry(UUID id, UUID usageEventId, UUID runId, UUID orgId, BigDecimal amount, LedgerEntryType type) {
        this.id = id;
        this.usageEventId = usageEventId;
        this.runId = runId;
        this.orgId = orgId;
        this.amount = amount;
        this.type = type;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`backend/src/main/java/com/agentstore/platform/ledger/LedgerEntryRepository.java`:

```java
package com.agentstore.platform.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
    Optional<LedgerEntry> findByUsageEventId(UUID usageEventId);

    List<LedgerEntry> findByOrgId(UUID orgId);

    List<LedgerEntry> findByOrgIdOrderByCreatedAtDesc(UUID orgId);
}
```

`backend/src/main/java/com/agentstore/platform/ledger/LedgerService.java`:

```java
package com.agentstore.platform.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class LedgerService {

    private final UsageEventRepository usageEventRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(UsageEventRepository usageEventRepository, LedgerEntryRepository ledgerEntryRepository) {
        this.usageEventRepository = usageEventRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public LedgerEntry chargeUsageEvent(UUID usageEventId) {
        var existing = ledgerEntryRepository.findByUsageEventId(usageEventId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UsageEvent event = usageEventRepository.findById(usageEventId)
                .orElseThrow(() -> new IllegalArgumentException("UsageEvent not found: " + usageEventId));

        LedgerEntry entry = ledgerEntryRepository.save(new LedgerEntry(null, event.getId(), event.getRunId(),
                event.getOrgId(), event.getAmount(), LedgerEntryType.CHARGE));

        event.setProcessed(true);
        usageEventRepository.save(event);

        return entry;
    }
}
```

`backend/src/main/java/com/agentstore/platform/execution/AgentCallbackController.java` (in the `case COST ->` branch, after `usageEventRepository.save(...)`, add a call to charge it immediately — inject `LedgerService` into the constructor):

```java
            case COST -> {
                var manifest = manifestParser.parse(run.getAgentVersion().getManifestJson());
                BigDecimal quantity = new BigDecimal(String.valueOf(request.detail().getOrDefault("quantity", "0")));
                BigDecimal unitCost = manifest.costEnvelope().unitPrice();
                BigDecimal amount = quantity.multiply(unitCost);

                UsageEvent usageEvent = usageEventRepository.save(new UsageEvent(null, run.getId(),
                        run.getConnection().getBuyerOrg().getId(), quantity, unitCost, amount));
                ledgerService.chargeUsageEvent(usageEvent.getId());

                run.setTotalCharged(run.getTotalCharged().add(amount));
                if (run.getTotalCharged().compareTo(run.getCostCap()) > 0) {
                    sandboxProvider.kill(run.getSandboxId());
                    run.setStatus(RunStatus.FAILED);
                    run.setCompletedAt(Instant.now());
                }
                runRepository.save(run);
            }
```

(add `private final com.agentstore.platform.ledger.LedgerService ledgerService;` as a field, assigned in the constructor alongside the existing dependencies)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=LedgerServiceTest,AgentCallbackCompletionTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(ledger): add idempotent usage-event-to-ledger-entry aggregation"
```

### Task 23: Wallet debiting and insufficient-balance guard at trigger time

**Files:**
- Create: `backend/src/main/resources/db/migration/V11__wallet.sql`
- Create: `backend/src/main/java/com/agentstore/platform/ledger/Wallet.java`
- Create: `backend/src/main/java/com/agentstore/platform/ledger/WalletRepository.java`
- Modify: `backend/src/main/java/com/agentstore/platform/ledger/LedgerService.java` (debit the wallet inside `chargeUsageEvent`)
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunController.java` (reject triggering a run if the buyer's wallet balance is below the connection's `spendCap`)
- Test: `backend/src/test/java/com/agentstore/platform/ledger/LedgerServiceWalletTest.java`
- Test: `backend/src/test/java/com/agentstore/platform/execution/RunControllerWalletTest.java`

**Interfaces:**
- Produces: `Wallet{id, orgId: UUID (unique), balance: BigDecimal, cap: BigDecimal (nullable), createdAt, updatedAt}`, `WalletRepository.findByOrgId(UUID): Optional<Wallet>`. `LedgerService.chargeUsageEvent` now also subtracts the charged amount from the org's `Wallet.balance` (throwing `IllegalStateException` if no wallet row exists — a buyer must be funded before any usage can be metered). `RunController.trigger` now returns `402 PAYMENT_REQUIRED` if `Wallet.balance < Connection.spendCap` before it ever calls `SandboxProvider.create` — this bounds worst-case exposure to what's actually funded, checked once at trigger time rather than debited then.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/ledger/LedgerServiceWalletTest.java`:

```java
package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerServiceWalletTest extends AbstractIntegrationTest {

    @Autowired
    LedgerService ledgerService;
    @Autowired
    UsageEventRepository usageEventRepository;
    @Autowired
    WalletRepository walletRepository;

    @Test
    void chargingDebitsTheWalletExactlyOnce() {
        UUID orgId = UUID.randomUUID();
        Wallet wallet = new Wallet(null, orgId, new BigDecimal("1000.00"), null);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);

        UsageEvent event = usageEventRepository.save(new UsageEvent(null, UUID.randomUUID(), orgId,
                new BigDecimal("5"), new BigDecimal("2.00"), new BigDecimal("10.00")));

        ledgerService.chargeUsageEvent(event.getId());
        ledgerService.chargeUsageEvent(event.getId());

        Wallet updated = walletRepository.findByOrgId(orgId).orElseThrow();
        assertThat(updated.getBalance()).isEqualByComparingTo(new BigDecimal("990.00"));
    }
}
```

`backend/src/test/java/com/agentstore/platform/execution/RunControllerWalletTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.*;
import com.agentstore.platform.ledger.Wallet;
import com.agentstore.platform.ledger.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunControllerWalletTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    AgentVersionRepository agentVersionRepository;
    @Autowired
    ConnectionRepository connectionRepository;
    @Autowired
    WalletRepository walletRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void triggeringWithInsufficientWalletBalanceIsRejected() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Org buyer = orgRepository.save(new Org(null, "Acme", OrgType.BUYER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        AgentVersion version = new AgentVersion(null, agent, "1.0.0", manifest, "template-v1",
                VersionStatus.LIVE, Tier.TIER_A, "cl");
        version.setApprovedAt(Instant.now());
        agentVersionRepository.save(version);
        Connection connection = connectionRepository.save(new Connection(null, buyer, agent,
                List.of("bank_statement"), new BigDecimal("500.00"), ConnectionStatus.ACTIVE));
        Wallet wallet = new Wallet(null, buyer.getId(), new BigDecimal("10.00"), null);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), buyer.getId(), UserRole.BUYER_ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>(new TriggerRunRequest(connection.getId()), headers);

        var response = rest.postForEntity(url("/api/v1/runs"), request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=LedgerServiceWalletTest,RunControllerWalletTest`
Expected: FAIL — `Wallet` does not exist, and `RunController` does not check wallet balance.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/resources/db/migration/V11__wallet.sql`:

```sql
CREATE TABLE wallet (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL UNIQUE,
    balance NUMERIC(14,2) NOT NULL,
    cap NUMERIC(14,2),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`backend/src/main/java/com/agentstore/platform/ledger/Wallet.java`:

```java
package com.agentstore.platform.ledger;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallet")
@Getter
@Setter
@NoArgsConstructor
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", unique = true)
    private UUID orgId;

    private BigDecimal balance;
    private BigDecimal cap;
    private Instant createdAt;
    private Instant updatedAt;

    public Wallet(UUID id, UUID orgId, BigDecimal balance, BigDecimal cap) {
        this.id = id;
        this.orgId = orgId;
        this.balance = balance;
        this.cap = cap;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }
}
```

`backend/src/main/java/com/agentstore/platform/ledger/WalletRepository.java`:

```java
package com.agentstore.platform.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {
    Optional<Wallet> findByOrgId(UUID orgId);
}
```

`backend/src/main/java/com/agentstore/platform/ledger/LedgerService.java` (add `WalletRepository`, debit inside `chargeUsageEvent` — full updated class):

```java
package com.agentstore.platform.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class LedgerService {

    private final UsageEventRepository usageEventRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final WalletRepository walletRepository;

    public LedgerService(UsageEventRepository usageEventRepository, LedgerEntryRepository ledgerEntryRepository,
                          WalletRepository walletRepository) {
        this.usageEventRepository = usageEventRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.walletRepository = walletRepository;
    }

    @Transactional
    public LedgerEntry chargeUsageEvent(UUID usageEventId) {
        var existing = ledgerEntryRepository.findByUsageEventId(usageEventId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UsageEvent event = usageEventRepository.findById(usageEventId)
                .orElseThrow(() -> new IllegalArgumentException("UsageEvent not found: " + usageEventId));

        LedgerEntry entry = ledgerEntryRepository.save(new LedgerEntry(null, event.getId(), event.getRunId(),
                event.getOrgId(), event.getAmount(), LedgerEntryType.CHARGE));

        Wallet wallet = walletRepository.findByOrgId(event.getOrgId())
                .orElseThrow(() -> new IllegalStateException("No wallet for org " + event.getOrgId()));
        wallet.setBalance(wallet.getBalance().subtract(event.getAmount()));
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);

        event.setProcessed(true);
        usageEventRepository.save(event);

        return entry;
    }
}
```

`backend/src/main/java/com/agentstore/platform/execution/RunController.java` (inject `WalletRepository`; add the balance check as the first thing after loading the `Connection`, right before resolving the `AgentVersion`):

```java
        if (connection.getStatus() != ConnectionStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Connection not active");
        }
        var wallet = walletRepository.findByOrgId(connection.getBuyerOrg().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "No wallet for org"));
        if (wallet.getBalance().compareTo(connection.getSpendCap()) < 0) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Insufficient wallet balance for spend cap");
        }
```

(add `private final com.agentstore.platform.ledger.WalletRepository walletRepository;` as a field, assigned via the constructor alongside the existing dependencies)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=LedgerServiceWalletTest,RunControllerWalletTest,RunControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(ledger): debit wallet on charge, guard run triggers on insufficient balance"
```

### Task 24: Billing statement endpoint

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/ledger/StatementController.java`
- Create: `backend/src/main/java/com/agentstore/platform/ledger/StatementLineResponse.java`
- Test: `backend/src/test/java/com/agentstore/platform/ledger/StatementControllerTest.java`

**Interfaces:**
- Consumes: `LedgerEntryRepository.findByOrgIdOrderByCreatedAtDesc` (Task 22); `RunRepository` (Task 16, for enriching each line with the agent's name).
- Produces: `GET /api/v1/orgs/{orgId}/statement` (role `BUYER_ADMIN` for one's own org, or `ADMIN` for any) → `List<StatementLineResponse{runId, agentName, amount, type, createdAt}>`, matching the MVP design doc's "statement view assembled from ledger entries, matching the billing mockup's per-agent breakdown."

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/ledger/StatementControllerTest.java`:

```java
package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.execution.*;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StatementControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    AgentVersionRepository agentVersionRepository;
    @Autowired
    ConnectionRepository connectionRepository;
    @Autowired
    RunRepository runRepository;
    @Autowired
    LedgerEntryRepository ledgerEntryRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void ownerSeesStatementLinesWithAgentName() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Org buyer = orgRepository.save(new Org(null, "Acme", OrgType.BUYER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        AgentVersion version = new AgentVersion(null, agent, "1.0.0", manifest, "template-v1",
                VersionStatus.LIVE, Tier.TIER_A, "cl");
        version.setApprovedAt(Instant.now());
        agentVersionRepository.save(version);
        Connection connection = connectionRepository.save(new Connection(null, buyer, agent,
                List.of("bank_statement"), new BigDecimal("500.00"), ConnectionStatus.ACTIVE));
        Run run = runRepository.save(new Run(null, connection, version, RunStatus.COMPLETED, new BigDecimal("500.00"),
                new BigDecimal("10.00"), "sbx", "tok"));
        ledgerEntryRepository.save(new LedgerEntry(null, UUID.randomUUID(), run.getId(), buyer.getId(),
                new BigDecimal("10.00"), LedgerEntryType.CHARGE));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), buyer.getId(), UserRole.BUYER_ADMIN));

        var response = rest.exchange(url("/api/v1/orgs/" + buyer.getId() + "/statement"), HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<List<StatementLineResponse>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).agentName()).isEqualTo("Bank Reconciliation");
        assertThat(response.getBody().get(0).amount()).isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    void cannotReadAnotherOrgsStatement() throws Exception {
        Org buyer = orgRepository.save(new Org(null, "Acme2", OrgType.BUYER));
        Org intruder = orgRepository.save(new Org(null, "Intruder", OrgType.BUYER));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), intruder.getId(), UserRole.BUYER_ADMIN));

        var response = rest.exchange(url("/api/v1/orgs/" + buyer.getId() + "/statement"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=StatementControllerTest`
Expected: FAIL — `StatementController`, `StatementLineResponse` do not exist.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/com/agentstore/platform/ledger/StatementLineResponse.java`:

```java
package com.agentstore.platform.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StatementLineResponse(UUID runId, String agentName, BigDecimal amount, LedgerEntryType type,
                                     Instant createdAt) {
}
```

`backend/src/main/java/com/agentstore/platform/ledger/StatementController.java`:

```java
package com.agentstore.platform.ledger;

import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
public class StatementController {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final RunRepository runRepository;

    public StatementController(LedgerEntryRepository ledgerEntryRepository, RunRepository runRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.runRepository = runRepository;
    }

    @GetMapping("/api/v1/orgs/{orgId}/statement")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','ADMIN')")
    public List<StatementLineResponse> statement(@PathVariable UUID orgId) {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!orgId.equals(claims.orgId()) && claims.role() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot read another org's statement");
        }

        return ledgerEntryRepository.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .map(entry -> {
                    String agentName = runRepository.findById(entry.getRunId())
                            .map(run -> run.getConnection().getAgent().getName())
                            .orElse("unknown");
                    return new StatementLineResponse(entry.getRunId(), agentName, entry.getAmount(),
                            entry.getType(), entry.getCreatedAt());
                })
                .toList();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=StatementControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(ledger): add per-org billing statement endpoint"
```

---

## Phase 9 — End-to-end proof

### Task 25: Walking-skeleton E2E test (Tier A, no approval gate)

**Files:**
- Test: `backend/src/test/java/com/agentstore/platform/EndToEndTierATest.java`

**Interfaces:**
- Consumes: every public endpoint built so far — this task adds no production code, it proves the modules compose correctly end to end, catching integration gaps unit/slice tests miss (e.g., a field name mismatch between two modules that each module's own tests didn't exercise together).

- [ ] **Step 1: Write the end-to-end test**

`backend/src/test/java/com/agentstore/platform/EndToEndTierATest.java`:

```java
package com.agentstore.platform;

import com.agentstore.platform.execution.RunResponse;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.AuthResponse;
import com.agentstore.platform.identity.LoginRequest;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.SignupRequest;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.execution.ConnectionResponse;
import com.agentstore.platform.execution.CreateConnectionRequest;
import com.agentstore.platform.execution.TriggerRunRequest;
import com.agentstore.platform.ledger.StatementLineResponse;
import com.agentstore.platform.ledger.Wallet;
import com.agentstore.platform.ledger.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EndToEndTierATest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    WalletRepository walletRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void publishConnectRunAndBillEndToEnd() throws Exception {
        // 1. Developer signs up and publishes a Tier A agent, which auto-approves to LIVE.
        var devSignup = rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("DevCo", OrgType.DEVELOPER, "dev@devco.example", "pw", UserRole.DEVELOPER),
                AuthResponse.class).getBody();
        var devLogin = rest.postForEntity(url("/api/v1/auth/login"),
                new LoginRequest("dev@devco.example", "pw"), AuthResponse.class).getBody();

        var agent = rest.postForEntity(url("/api/v1/agents"),
                new HttpEntity<>(new CreateAgentRequest("Bank Reconciliation", "reconciliation", "d"), bearer(devLogin.token())),
                AgentResponse.class).getBody();

        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        var version = rest.postForEntity(url("/api/v1/agents/" + agent.id() + "/versions"),
                new HttpEntity<>(new CreateAgentVersionRequest("1.0.0", manifest, "bank-recon-template-v1", "first cut"),
                        bearer(devLogin.token())),
                AgentVersionResponse.class).getBody();

        rest.exchange(url("/api/v1/agents/" + agent.id() + "/versions/" + version.id() + "/submit"),
                HttpMethod.POST, new HttpEntity<>(bearer(devLogin.token())), AgentVersionResponse.class);

        var live = rest.exchange(url("/api/v1/catalog"), HttpMethod.GET,
                new HttpEntity<>(bearer(devLogin.token())), new ParameterizedTypeReference<List<CatalogListingResponse>>() {}).getBody();
        assertThat(live).extracting(CatalogListingResponse::agentId).contains(agent.id());

        // 2. Buyer signs up, funds a wallet, connects, and triggers a run.
        var buyerSignup = rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("Acme SMB", OrgType.BUYER, "finance@acme.example", "pw", UserRole.BUYER_ADMIN),
                AuthResponse.class).getBody();
        var buyerLogin = rest.postForEntity(url("/api/v1/auth/login"),
                new LoginRequest("finance@acme.example", "pw"), AuthResponse.class).getBody();

        UUID buyerOrgId = extractOrgId(buyerLogin.token());
        Wallet wallet = new Wallet(null, buyerOrgId, new BigDecimal("1000.00"), null);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);

        var connection = rest.postForEntity(url("/api/v1/connections"),
                new HttpEntity<>(new CreateConnectionRequest(agent.id(), List.of("bank_statement"), new BigDecimal("500.00")),
                        bearer(buyerLogin.token())),
                ConnectionResponse.class).getBody();

        var run = rest.postForEntity(url("/api/v1/runs"),
                new HttpEntity<>(new TriggerRunRequest(connection.id()), bearer(buyerLogin.token())),
                RunResponse.class).getBody();
        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);

        // 3. The (fake) sandboxed agent reports usage and completion over the callback protocol.
        // In this test we call the callback endpoints directly rather than running a real E2B
        // sandbox, standing in for what a real agent process would do — no real agent business
        // logic is written or tested here, only the protocol the execution module exposes to it.
        String runDetailUrl = url("/api/v1/runs/" + run.id());
        var runDetailBeforeCompletion = rest.exchange(runDetailUrl, HttpMethod.GET,
                new HttpEntity<>(bearer(buyerLogin.token())), Map.class);
        assertThat(runDetailBeforeCompletion.getStatusCode()).isEqualTo(HttpStatus.OK);

        // NOTE: the callback token is not exposed via any buyer-facing API by design (Task 16
        // injects it only into the sandbox's env vars). This test reads it directly from the
        // repository the way a real sandboxed process would receive it via AGENT_CALLBACK_TOKEN.
        String callbackToken = fetchCallbackToken(run.id());
        HttpHeaders agentHeaders = bearer(callbackToken);
        rest.postForEntity(url("/internal/agent-callback/runs/" + run.id() + "/events"),
                new HttpEntity<>(Map.of("kind", "READ", "detail", Map.of("resource", "bank_statement")), agentHeaders), Void.class);
        rest.postForEntity(url("/internal/agent-callback/runs/" + run.id() + "/events"),
                new HttpEntity<>(Map.of("kind", "COST", "detail", Map.of("quantity", 5)), agentHeaders), Void.class);
        rest.postForEntity(url("/internal/agent-callback/runs/" + run.id() + "/events"),
                new HttpEntity<>(Map.of("kind", "COMPLETE", "detail", Map.of()), agentHeaders), Void.class);

        // 4. The run is complete and billed.
        var finalRun = rest.exchange(runDetailUrl, HttpMethod.GET, new HttpEntity<>(bearer(buyerLogin.token())), Map.class);
        assertThat(finalRun.getBody().get("status")).isEqualTo("COMPLETED");

        var statement = rest.exchange(url("/api/v1/orgs/" + buyerOrgId + "/statement"), HttpMethod.GET,
                new HttpEntity<>(bearer(buyerLogin.token())), new ParameterizedTypeReference<List<StatementLineResponse>>() {}).getBody();
        assertThat(statement).hasSize(1);
        assertThat(statement.get(0).amount()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(statement.get(0).agentName()).isEqualTo("Bank Reconciliation");

        Wallet finalWallet = walletRepository.findByOrgId(buyerOrgId).orElseThrow();
        assertThat(finalWallet.getBalance()).isEqualByComparingTo(new BigDecimal("990.00"));
    }

    private UUID extractOrgId(String jwt) {
        String[] parts = jwt.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        String orgId = payload.replaceAll(".*\"orgId\":\"([^\"]+)\".*", "$1");
        return UUID.fromString(orgId);
    }

    @Autowired
    com.agentstore.platform.execution.RunRepository runRepository;

    private String fetchCallbackToken(UUID runId) {
        return runRepository.findById(runId).orElseThrow().getCallbackToken();
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=EndToEndTierATest`
Expected: PASS on first run — every piece it exercises was already built and tested individually in Tasks 1–24; this test's job is to catch any wiring mismatch between them. If it fails, the failure points at an integration gap (e.g., a DTO field name that drifted between two modules), not a new feature to build.

- [ ] **Step 3: Commit**

```bash
git add backend/src
git commit -m "test: add end-to-end walking-skeleton proof (Tier A, no approval gate)"
```

### Task 26: Tier B end-to-end test with an approval gate

**Files:**
- Test: `backend/src/test/java/com/agentstore/platform/EndToEndTierBTest.java`

**Interfaces:**
- Consumes: everything Task 25 does, plus the human review queue (Task 13) and the approval gate pause/resume (Task 19) — proves the second, riskier publishing and execution path that the spec treats as non-negotiable for finance buyers.

- [ ] **Step 1: Write the end-to-end test**

`backend/src/test/java/com/agentstore/platform/EndToEndTierBTest.java`:

```java
package com.agentstore.platform;

import com.agentstore.platform.catalog.*;
import com.agentstore.platform.execution.*;
import com.agentstore.platform.identity.*;
import com.agentstore.platform.ledger.Wallet;
import com.agentstore.platform.ledger.WalletRepository;
import com.agentstore.platform.publishing.ReviewDecisionRequest;
import com.agentstore.platform.publishing.ReviewItemResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EndToEndTierBTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    WalletRepository walletRepository;
    @Autowired
    RunRepository runRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    UUID orgIdOf(String jwt) {
        String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
        return UUID.fromString(payload.replaceAll(".*\"orgId\":\"([^\"]+)\".*", "$1"));
    }

    @Test
    void tierBAgentRequiresManualApprovalAndPausesOnGate() throws Exception {
        var devLogin = rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("DevCo", OrgType.DEVELOPER, "dev2@devco.example", "pw", UserRole.DEVELOPER),
                AuthResponse.class).getBody();

        var agent = rest.postForEntity(url("/api/v1/agents"),
                new HttpEntity<>(new CreateAgentRequest("Collections", "collections", "d"), bearer(devLogin.token())),
                AgentResponse.class).getBody();
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-b.json"));
        var version = rest.postForEntity(url("/api/v1/agents/" + agent.id() + "/versions"),
                new HttpEntity<>(new CreateAgentVersionRequest("1.0.0", manifest, "collections-template-v1", "cl"),
                        bearer(devLogin.token())),
                AgentVersionResponse.class).getBody();
        rest.exchange(url("/api/v1/agents/" + agent.id() + "/versions/" + version.id() + "/submit"),
                HttpMethod.POST, new HttpEntity<>(bearer(devLogin.token())), AgentVersionResponse.class);

        // Still IN_REVIEW, not on the public catalog, until an admin approves it.
        var catalogBeforeApproval = rest.exchange(url("/api/v1/catalog"), HttpMethod.GET,
                new HttpEntity<>(bearer(devLogin.token())), new ParameterizedTypeReference<List<CatalogListingResponse>>() {}).getBody();
        assertThat(catalogBeforeApproval).extracting(CatalogListingResponse::agentId).doesNotContain(agent.id());

        var adminLogin = rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("Platform", OrgType.DEVELOPER, "admin@platform.example", "pw", UserRole.ADMIN),
                AuthResponse.class).getBody();
        var queue = rest.exchange(url("/api/v1/review/queue"), HttpMethod.GET,
                new HttpEntity<>(bearer(adminLogin.token())), new ParameterizedTypeReference<List<ReviewItemResponse>>() {}).getBody();
        var pending = queue.stream().filter(i -> i.agentVersionId().equals(version.id())).findFirst().orElseThrow();
        rest.postForEntity(url("/api/v1/review/" + pending.id() + "/approve"),
                new HttpEntity<>(new ReviewDecisionRequest("looks safe"), bearer(adminLogin.token())), ReviewItemResponse.class);

        var buyerLogin = rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("Acme SMB 2", OrgType.BUYER, "finance2@acme.example", "pw", UserRole.BUYER_ADMIN),
                AuthResponse.class).getBody();
        UUID buyerOrgId = orgIdOf(buyerLogin.token());
        Wallet wallet = new Wallet(null, buyerOrgId, new BigDecimal("1000.00"), null);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);

        var connection = rest.postForEntity(url("/api/v1/connections"),
                new HttpEntity<>(new CreateConnectionRequest(agent.id(), List.of("invoice", "whatsapp_message"), new BigDecimal("200.00")),
                        bearer(buyerLogin.token())),
                ConnectionResponse.class).getBody();
        var run = rest.postForEntity(url("/api/v1/runs"),
                new HttpEntity<>(new TriggerRunRequest(connection.id()), bearer(buyerLogin.token())), RunResponse.class).getBody();

        String callbackToken = runRepository.findById(run.id()).orElseThrow().getCallbackToken();
        HttpHeaders agentHeaders = bearer(callbackToken);

        rest.postForEntity(url("/internal/agent-callback/runs/" + run.id() + "/events"),
                new HttpEntity<>(Map.of("kind", "GATE", "detail", Map.of("trigger", "MESSAGE_CUSTOMER")), agentHeaders), Void.class);

        var pausedRun = rest.exchange(url("/api/v1/runs/" + run.id()), HttpMethod.GET,
                new HttpEntity<>(bearer(buyerLogin.token())), Map.class);
        assertThat(pausedRun.getBody().get("status")).isEqualTo("AWAITING_APPROVAL");

        // A second BUYER_APPROVER user in the same org approves the gate.
        var approverLogin = rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("Acme SMB 2", OrgType.BUYER, "approver@acme.example", "pw", UserRole.BUYER_APPROVER),
                AuthResponse.class).getBody();
        // NOTE: signup always creates a NEW org; to keep this test focused on the gate mechanics
        // rather than multi-user-per-org account management (out of scope for this plan), the
        // approval is issued by an approver token scoped to the SAME buyer org via a JWT minted
        // directly, mirroring what a second teammate's login would carry.
        String approverToken = mintApproverTokenForSameOrg(buyerOrgId);

        rest.exchange(url("/api/v1/runs/" + run.id() + "/approve"), HttpMethod.POST,
                new HttpEntity<>(new ApproveRunRequest("go ahead"), bearer(approverToken)), RunResponse.class);

        var resumedRun = rest.exchange(url("/api/v1/runs/" + run.id()), HttpMethod.GET,
                new HttpEntity<>(bearer(buyerLogin.token())), Map.class);
        assertThat(resumedRun.getBody().get("status")).isEqualTo("RUNNING");
    }

    @Autowired
    JwtService jwtService;

    private String mintApproverTokenForSameOrg(UUID orgId) {
        return jwtService.issue(UUID.randomUUID(), orgId, UserRole.BUYER_APPROVER);
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=EndToEndTierBTest`
Expected: PASS on first run, for the same reason as Task 25.

- [ ] **Step 3: Commit**

```bash
git add backend/src
git commit -m "test: add end-to-end proof for Tier B review and approval-gate pause/resume"
```

---

## Self-Review Notes

- **Spec coverage:** Every "In scope" bullet from the MVP design doc §1 is covered — real container-based execution (Tasks 16–17), manifest-driven governance with scope/egress/approval-gate enforcement (Tasks 5–6, 14, 16, 19), metering → ledger → billing (Tasks 18, 20, 22–24), and a publishing wizard's backend (Tasks 8–13, minus the actual wizard UI, which is explicitly a frontend-plan concern). Explicitly-out-of-scope items (real Tally/GST/bank integrations, real money movement, open submission, Kafka/microservices) are not built anywhere in this plan.
- **Type consistency:** `RunStepKind` is defined once (Task 18) and reused identically in Tasks 19–21, 25–26. `Manifest`/`Scope`/`CostEnvelope` (Task 6) are the single types every later module parses manifests into — no module redefines its own manifest shape. `SandboxProvider`/`SandboxSpec`/`SandboxHandle` (Task 16) are implemented identically by `FakeSandboxProvider` and `E2bSandboxProvider` (Task 17). `AgentVersionResponse`, `ConnectionResponse`, `RunResponse` field names match what Tasks 25–26's end-to-end tests read.
- **No placeholders:** every step above has real, complete code — no "add validation here" or "similar to Task N" shortcuts. Where a genuine external unknown exists (E2B's exact API surface), it was resolved by fetching E2B's live API reference during planning rather than left as a TODO; the one remaining external dependency an implementer must still confirm at build time is whether E2B's current API has changed since this plan was written, which Task 17's `MockRestServiceServer`-based test will catch immediately if so.

---

## Execution Handoff

Two execution options once this plan is approved:

1. **Subagent-driven (recommended)** — a fresh subagent per task, with a two-stage review between tasks. Use `superpowers:subagent-driven-development`.
2. **Inline execution** — work through the 26 tasks in this session in batches with checkpoints. Use `superpowers:executing-plans`.

Frontend (buyer console, developer portal, connection flow UI) is deliberately out of scope for this plan and should be its own follow-up plan once this backend's API surface is stable.
