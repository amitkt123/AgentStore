# WS13 — Identity, Workspaces and Teams Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let one person belong to several orgs (a buyer workspace and a developer studio), sign up by picking a side with their full name, invite teammates with roles and titles, stay signed in with refresh tokens, verify their email, reset a forgotten password, and sign in with Google.

**Architecture:** A new `org_membership` table replaces the single `app_user.org_id/role` pair. The JWT keeps its current shape (`sub`, `orgId`, `role`) and carries the one *active* membership, so every existing `@PreAuthorize` check and `JwtService.Claims` reader keeps working unchanged. All identity rules live in `IdentityService` (signup, which membership a login lands in, adding orgs) and small focused services (`InvitationService`, `RefreshTokenService`, `UserTokenService`, `GoogleSignInService`). One-time secrets (invites, refresh, email and reset tokens) are 32 random bytes, stored only as SHA-256 hashes via a shared `TokenHasher`. Email goes through an `EmailSender` interface that later workstreams (WS14) reuse.

**Tech Stack:** Java 21, Spring Boot 3.3.4, Spring Security 6 (stateless JWT via `jjwt` 0.12.6), `spring-boot-starter-mail`, `spring-security-oauth2-jose` (Nimbus, for Google ID tokens), Postgres 16 + Flyway, Testcontainers. Frontend: Next.js 14 (signup form and seed script only).

**Spec:** `docs/superpowers/specs/2026-09-23-agent-store-developer-platform-and-sandbox-design.md` §5 **WS13** (read it first), plus §10 rows 1, 2 and 4.

**Prerequisite:** the WS0 plan (`2026-09-23-ws0-execution-defect-fixes.md`) is merged. This plan touches `EndToEndTierATest`/`EndToEndTierBTest` only at their signup lines, which WS0 does not change.

## Global Constraints

- Flyway: WS0 uses `V12`–`V16`. **This plan uses `V17`–`V21`.** Planned order after it: WS15 `V22`–`V25`, WS14 `V26`–`V29`, WS16 `V30`–`V31`. If another plan merges first, renumber to the next free numbers.
- The JWT claim set stays exactly `sub` (userId), `orgId`, `role`. `JwtService.issue(UUID userId, UUID orgId, UserRole role)` and `JwtService.Claims` are unchanged.
- Roles stay the existing four: `BUYER_ADMIN`, `BUYER_APPROVER`, `DEVELOPER`, `ADMIN`. Assignable per org type: BUYER → `BUYER_ADMIN`, `BUYER_APPROVER`; DEVELOPER → `DEVELOPER`. `ADMIN` is never assignable through the API. It is granted only at signup to emails listed in `agentstore.identity.admin-emails`.
- Signup no longer accepts a role. The side decides it: BUYER → `BUYER_ADMIN` of a new org, DEVELOPER → `DEVELOPER` of a new org.
- Emails are stored and compared lowercase (`String.toLowerCase(Locale.ROOT)` after `trim()`).
- One-time tokens: 32 bytes from `SecureRandom`, base64url without padding, stored as lowercase hex SHA-256 in a `CHAR(64) UNIQUE` column. Raw tokens only ever appear in an email or a response body, never in a log line of the SMTP sender or a DB row.
- Lifetimes: invitation 7 days, email verification 24 hours, password reset 1 hour, refresh token 30 days (`agentstore.identity.refresh-token-ttl`, default `P30D`). Access token lifetime stays `agentstore.jwt.expiration-minutes: 60` until the new frontend handles refresh.
- Links in emails use `agentstore.web.base-url` (default `http://localhost:3000`): `/invite/{token}`, `/verify-email/{token}`, `/reset-password/{token}`.
- Email mode: `agentstore.email.mode` = `log` (default; logs recipient and subject only, never the body) or `smtp`. Tests use a `@Primary` `RecordingEmailSender`.
- Error codes: duplicate email → 409; expired, used or revoked invitation/token → 410 for invitations, 400 for verify/reset tokens; unknown org membership on switch → 403.
- Money, run and catalog code are out of scope. Do not touch `execution/`, `ledger/`, `catalog/` or `publishing/` except the two E2E tests named in Tasks 1 and 3.
- Commit messages are conventional-commit style (`feat(identity): …`). **Never add any Claude/Anthropic/AI attribution or `Co-Authored-By` trailer** (user's global instruction).
- **Deferred, not dropped:** `org.gstin`/`billing_address` move to WS10, where tax invoices first read them. `sso_required` and workspace SSO move to M3. Revoking live access tokens on member removal is left to the 60-minute expiry, except for gate decisions, which WS15 checks against the database.

## Review Focus

1. **An email typed with different case** ("Priya@Meridian.com" at signup, "priya@meridian.com" at login) must sign in, and a second signup differing only in case must get 409, not a second account. [Task 1]
2. **A refresh token used again after it was rotated** (a stolen copy) must revoke the whole token family, so the thief's and the owner's sessions both end. [Task 4]
3. **Removing or demoting the last `BUYER_ADMIN` of a workspace** must be refused with 409, or the workspace can never be managed again. [Task 3]
4. **An invitation for an email that already has an account, accepted without signing in**, must be refused (409), never silently attach a membership. Accepting while signed in as a *different* email → 403. [Task 3]
5. **Forgot-password for an unknown email** must answer the same 202 and send nothing. An older reset link must stop working once a newer one is requested. [Task 5]

---

## File Structure

```
backend/
  pom.xml                                                    (modify: spring-boot-starter-mail, spring-security-oauth2-jose)
  src/main/resources/application.yml                         (modify: agentstore.identity/email/web/google)
  src/main/resources/db/migration/
    V17__org_membership.sql                                  (create)
    V18__invitation.sql                                      (create)
    V19__refresh_token.sql                                   (create)
    V20__user_token.sql                                      (create)
    V21__user_identity.sql                                   (create)
  src/main/java/com/agentstore/platform/
    common/TokenHasher.java                                  (create)
    common/EmailMessage.java, EmailSender.java,
           LoggingEmailSender.java, SmtpEmailSender.java     (create)
    identity/
      User.java                    (modify: drop org/role, add fullName, emailVerifiedAt, lastOrgId)
      UserRepository.java          (modify: findByEmailIgnoreCase, existsByEmailIgnoreCase)
      OrgMembershipId.java, OrgMembership.java,
      OrgMembershipRepository.java (create)
      OrgRoles.java                (create: assignable roles, who manages members)
      SignupSide.java              (create)
      SignupRequest.java           (modify: fullName, orgName, email, password, side)
      LoginRequest.java            (modify: + remember)
      AuthResponse.java            (modify: + refreshToken)
      IdentityService.java         (create)
      AuthController.java          (modify: uses IdentityService; refresh, logout, switch-org, email, password, google)
      MeController.java, MeResponse.java                     (create)
      Invitation.java, InvitationRepository.java,
      InvitationService.java, InvitationController.java,
      MembersController.java                                 (create)
      RefreshToken.java, RefreshTokenRepository.java,
      RefreshTokenService.java                               (create)
      UserToken.java, UserTokenPurpose.java, UserTokenRepository.java,
      UserTokenService.java                                  (create)
      GoogleIdentity.java, GoogleIdTokenVerifier.java,
      NimbusGoogleIdTokenVerifier.java, UserIdentity.java,
      UserIdentityRepository.java, GoogleSignInService.java  (create)
      SecurityConfig.java          (modify: permit /api/v1/invitations/**)
  src/test/resources/application-test.yml                    (modify: admin-emails, email mode)
  src/test/java/com/agentstore/platform/
    common/TokenHasherTest.java, common/RecordingEmailSender.java           (create)
    identity/IdentityFixtures.java                                          (create)
    identity/AuthControllerTest.java, UserRepositoryTest.java              (rewrite)
    identity/MeControllerTest.java, InvitationFlowTest.java,
    identity/MembersControllerTest.java, RefreshTokenTest.java,
    identity/EmailAndPasswordTest.java, GoogleSignInTest.java,
    identity/FakeGoogleIdTokenVerifier.java                                (create)
    EndToEndTierATest.java, EndToEndTierBTest.java                         (modify: signup lines; approver via invite)
frontend/
  src/app/(auth)/signup/page.tsx, actions.ts                 (modify: full name + side)
  e2e/seed.ts                                                (modify: new signup body; move approver via org_membership)
```

All backend commands run from `backend/`, frontend commands from `frontend/`.

---

### Task 1: Memberships, signup by side, and login into a membership

**Files:**
- Create: `backend/src/main/resources/db/migration/V17__org_membership.sql`
- Create: `identity/OrgMembershipId.java`, `identity/OrgMembership.java`, `identity/OrgMembershipRepository.java`, `identity/OrgRoles.java`, `identity/SignupSide.java`, `identity/IdentityService.java`
- Modify: `identity/User.java`, `identity/UserRepository.java`, `identity/SignupRequest.java`, `identity/AuthController.java`, `application.yml`, `src/test/resources/application-test.yml`
- Test: rewrite `identity/AuthControllerTest.java`, `identity/UserRepositoryTest.java`; create `identity/IdentityFixtures.java`; modify signup lines in `EndToEndTierATest.java`, `EndToEndTierBTest.java`

**Interfaces:**
- Produces:
  - `User(UUID id, String email, String passwordHash, String fullName)`, with getters/setters `getFullName`, `getEmailVerifiedAt(): Instant`, `getLastOrgId(): UUID`. `User` no longer has `getOrg()` or `getRole()`.
  - `OrgMembership(User user, Org org, UserRole role, String title)`, `getId(): OrgMembershipId`, `getUser()`, `getOrg()`, `getRole()`, `getTitle()`, `setRole`, `setTitle`, `getCreatedAt()`.
  - `OrgMembershipId(UUID userId, UUID orgId)` with `getUserId()`, `getOrgId()`.
  - `OrgMembershipRepository`: `findByIdUserIdOrderByCreatedAtAsc(UUID)`, `findByIdOrgIdOrderByCreatedAtAsc(UUID)`, `countByIdOrgIdAndRole(UUID, UserRole)`, `findById(OrgMembershipId)`.
  - `enum SignupSide { BUYER, DEVELOPER }` with `OrgType orgType()`.
  - `OrgRoles.assignable(OrgType): Set<UserRole>`, `OrgRoles.canManageMembers(UserRole): boolean`, `OrgRoles.adminRoleOf(OrgType): UserRole`.
  - `IdentityService`: `static String normalizeEmail(String)`, `OrgMembership signup(SignupRequest)`, `Optional<OrgMembership> landingMembership(User)`, `OrgMembership addOrg(User, SignupSide, String orgName)`, `String accessTokenFor(OrgMembership)`, `boolean passwordMatches(User, String rawPassword)`, `String hashPassword(String raw)`.
  - `UserRepository.findByEmailIgnoreCase(String): Optional<User>`, `existsByEmailIgnoreCase(String): boolean`.
  - Test fixture `IdentityFixtures` (`@Component`): `OrgMembership buyerAdmin(String email)`, `OrgMembership member(Org org, String email, UserRole role, String title)`, `String tokenFor(OrgMembership)`.

- [ ] **Step 1: Write the failing tests**

Replace `backend/src/test/java/com/agentstore/platform/identity/AuthControllerTest.java`:

```java
package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    UserRepository userRepository;
    @Autowired
    OrgMembershipRepository membershipRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    String unique(String local) {
        return local + "-" + UUID.randomUUID() + "@acme.example";
    }

    JwtService.Claims claimsOf(AuthResponse response) {
        return jwtService.parse(response.token()).orElseThrow();
    }

    @Test
    void buyerSignupCreatesAWorkspaceAndMakesTheUserItsAdmin() {
        String email = unique("priya");
        var response = rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("Priya Shah", "Meridian Traders", email, "s3cret-pw", SignupSide.BUYER),
                AuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var claims = claimsOf(response.getBody());
        assertThat(claims.role()).isEqualTo(UserRole.BUYER_ADMIN);
        User user = userRepository.findById(claims.userId()).orElseThrow();
        assertThat(user.getFullName()).isEqualTo("Priya Shah");
        assertThat(user.getLastOrgId()).isEqualTo(claims.orgId());
        assertThat(membershipRepository.findByIdUserIdOrderByCreatedAtAsc(user.getId()))
                .singleElement().satisfies(m -> {
                    assertThat(m.getOrg().getType()).isEqualTo(OrgType.BUYER);
                    assertThat(m.getOrg().getName()).isEqualTo("Meridian Traders");
                });
    }

    @Test
    void developerSignupGivesTheDeveloperRole() {
        var response = rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("Asha Kulkarni", "Ledgerline Labs", unique("asha"), "s3cret-pw", SignupSide.DEVELOPER),
                AuthResponse.class);

        assertThat(claimsOf(response.getBody()).role()).isEqualTo(UserRole.DEVELOPER);
    }

    @Test
    void listedAdminEmailsBecomePlatformAdmins() {
        // application-test.yml lists admin-auth-test@platform.example (EndToEndTierBTest uses admin@platform.example;
        // the test database is shared across classes, so each test class needs its own address)
        var response = rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("Karya Admin", "Karya", "Admin-Auth-Test@Platform.example", "s3cret-pw",
                        SignupSide.DEVELOPER),
                AuthResponse.class);

        assertThat(claimsOf(response.getBody()).role()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void signupEmailIsCaseInsensitiveAndUnique() {
        String email = unique("Case.Test");
        rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("A", "Org A", email, "s3cret-pw", SignupSide.BUYER), AuthResponse.class);

        var duplicate = rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("B", "Org B", email.toUpperCase(), "s3cret-pw", SignupSide.BUYER), String.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var login = rest.postForEntity(url("/api/v1/auth/login"),
                new LoginRequest(email.toLowerCase(), "s3cret-pw", false), AuthResponse.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void loginWithWrongPasswordIsRejected() {
        String email = unique("wrongpw");
        rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("A", "Org", email, "correct-pw", SignupSide.BUYER), AuthResponse.class);

        var login = rest.postForEntity(url("/api/v1/auth/login"),
                new LoginRequest(email, "incorrect-pw", false), AuthResponse.class);

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void signupRequiresASide() {
        var response = rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("A", "Org", unique("noside"), "s3cret-pw", null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
```

`LoginRequest` gains a third component in this task (`remember`, used in Task 4), so the tests already pass `false`.

Replace `backend/src/test/java/com/agentstore/platform/identity/UserRepositoryTest.java`:

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
    @Autowired
    OrgMembershipRepository membershipRepository;

    @Test
    void membershipLinksAUserToAnOrgWithARoleAndTitle() {
        Org org = orgRepository.save(new Org(null, "Acme SMB", OrgType.BUYER));
        User user = userRepository.save(new User(null, "finance-repo@acme.example", "hashed-pw", "Arjun Mehta"));
        membershipRepository.save(new OrgMembership(user, org, UserRole.BUYER_APPROVER, "Accounts manager"));

        var found = userRepository.findByEmailIgnoreCase("Finance-Repo@Acme.example");
        assertThat(found).isPresent();
        assertThat(membershipRepository.findByIdUserIdOrderByCreatedAtAsc(found.get().getId()))
                .singleElement().satisfies(m -> {
                    assertThat(m.getOrg().getName()).isEqualTo("Acme SMB");
                    assertThat(m.getRole()).isEqualTo(UserRole.BUYER_APPROVER);
                    assertThat(m.getTitle()).isEqualTo("Accounts manager");
                });
    }
}
```

Create `backend/src/test/java/com/agentstore/platform/identity/IdentityFixtures.java`:

```java
package com.agentstore.platform.identity;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** Creates real users and memberships directly, for tests that need several people in one org. */
@Component
public class IdentityFixtures {

    private final OrgRepository orgRepository;
    private final UserRepository userRepository;
    private final OrgMembershipRepository membershipRepository;
    private final IdentityService identityService;

    public IdentityFixtures(OrgRepository orgRepository, UserRepository userRepository,
                            OrgMembershipRepository membershipRepository, IdentityService identityService) {
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.identityService = identityService;
    }

    public OrgMembership buyerAdmin(String email) {
        Org org = orgRepository.save(new Org(null, "Org " + UUID.randomUUID(), OrgType.BUYER));
        return member(org, email, UserRole.BUYER_ADMIN, "Finance lead");
    }

    public OrgMembership member(Org org, String email, UserRole role, String title) {
        User user = userRepository.findByEmailIgnoreCase(email).orElseGet(() -> userRepository.save(
                new User(null, IdentityService.normalizeEmail(email), identityService.hashPassword("s3cret-pw"),
                        "Person " + email)));
        return membershipRepository.save(new OrgMembership(user, org, role, title));
    }

    public String tokenFor(OrgMembership membership) {
        return identityService.accessTokenFor(membership);
    }
}
```

In `EndToEndTierATest.java`, replace the import `com.agentstore.platform.identity.SignupRequest;` block's siblings as needed (add `import com.agentstore.platform.identity.SignupSide;`, remove unused `OrgType`/`UserRole` imports if the compiler flags them) and change the two signup calls:

```java
                new SignupRequest("Dev One", "DevCo", "dev@devco.example", "pw", SignupSide.DEVELOPER),
```
```java
                new SignupRequest("Finance One", "Acme SMB", "finance-e2e-a@acme.example", "pw", SignupSide.BUYER),
```

and the two `new LoginRequest(email, "pw")` calls to `new LoginRequest(email, "pw", false)`.

In `EndToEndTierBTest.java` (its imports use `com.agentstore.platform.identity.*`, so no import changes):

```java
                new SignupRequest("Dev Two", "DevCo", "dev2@devco.example", "pw", SignupSide.DEVELOPER),
```
```java
                new SignupRequest("Karya Admin", "Platform", "admin@platform.example", "pw", SignupSide.DEVELOPER),
```
```java
                new SignupRequest("Finance Two", "Acme SMB 2", "finance2@acme.example", "pw", SignupSide.BUYER),
```

and delete the unused `var approverLogin = rest.postForEntity(... "approver@acme.example" ...)` statement (four lines). The minted approver token stays until Task 3 replaces it with a real invitation.

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `mvn -q test -Dtest='AuthControllerTest,UserRepositoryTest'`
Expected: compilation failure (`SignupSide`, `OrgMembership`, `findByEmailIgnoreCase` not found).

- [ ] **Step 3: Migration**

`backend/src/main/resources/db/migration/V17__org_membership.sql`:

```sql
ALTER TABLE app_user ADD COLUMN full_name VARCHAR(255);
ALTER TABLE app_user ADD COLUMN email_verified_at TIMESTAMPTZ;
ALTER TABLE app_user ADD COLUMN last_org_id UUID REFERENCES org(id);
ALTER TABLE app_user ALTER COLUMN password_hash DROP NOT NULL;   -- Google-only users (Task 6)

UPDATE app_user SET email = lower(trim(email));
CREATE UNIQUE INDEX app_user_email_lower_idx ON app_user (lower(email));

CREATE TABLE org_membership (
    user_id    UUID         NOT NULL REFERENCES app_user(id),
    org_id     UUID         NOT NULL REFERENCES org(id),
    role       VARCHAR(30)  NOT NULL,
    title      VARCHAR(100),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, org_id)
);
CREATE INDEX org_membership_org_idx ON org_membership (org_id);

INSERT INTO org_membership (user_id, org_id, role, created_at)
SELECT id, org_id, role, created_at FROM app_user;
UPDATE app_user SET last_org_id = org_id;

-- Expand phase: the legacy columns stay, nullable, so the previous build can still boot during a rollback.
-- A later contract migration drops them once nothing reads them.
ALTER TABLE app_user ALTER COLUMN org_id DROP NOT NULL;
ALTER TABLE app_user ALTER COLUMN role DROP NOT NULL;
```

- [ ] **Step 4: Entities, repositories, roles**

Replace `identity/User.java`:

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

    @Column(nullable = false, unique = true)
    private String email;

    /** Null for users who only ever signed in with Google. */
    @Column(name = "password_hash")
    private String passwordHash;

    private String fullName;
    private Instant emailVerifiedAt;

    /** The org a login lands in. Updated on signup, switch-org and accepting an invitation. */
    private UUID lastOrgId;

    private Instant createdAt;

    public User(UUID id, String email, String passwordHash, String fullName) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

Replace `identity/UserRepository.java`:

```java
package com.agentstore.platform.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
```

`identity/OrgMembershipId.java`:

```java
package com.agentstore.platform.identity;

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
public class OrgMembershipId implements Serializable {

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "org_id")
    private UUID orgId;
}
```

`identity/OrgMembership.java`:

```java
package com.agentstore.platform.identity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "org_membership")
@Getter
@Setter
@NoArgsConstructor
public class OrgMembership {

    @EmbeddedId
    private OrgMembershipId id;

    @MapsId("userId")
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @MapsId("orgId")
    @ManyToOne(optional = false)
    @JoinColumn(name = "org_id")
    private Org org;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    private String title;
    private Instant createdAt;

    public OrgMembership(User user, Org org, UserRole role, String title) {
        this.id = new OrgMembershipId(user.getId(), org.getId());
        this.user = user;
        this.org = org;
        this.role = role;
        this.title = title;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`identity/OrgMembershipRepository.java`:

```java
package com.agentstore.platform.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrgMembershipRepository extends JpaRepository<OrgMembership, OrgMembershipId> {
    List<OrgMembership> findByIdUserIdOrderByCreatedAtAsc(UUID userId);

    List<OrgMembership> findByIdOrgIdOrderByCreatedAtAsc(UUID orgId);

    long countByIdOrgIdAndRole(UUID orgId, UserRole role);
}
```

`identity/SignupSide.java`:

```java
package com.agentstore.platform.identity;

/** "I run finance" or "I build agents" on the sign-up screen. */
public enum SignupSide {
    BUYER, DEVELOPER;

    public OrgType orgType() {
        return this == BUYER ? OrgType.BUYER : OrgType.DEVELOPER;
    }
}
```

`identity/OrgRoles.java`:

```java
package com.agentstore.platform.identity;

import java.util.EnumSet;
import java.util.Set;

/** Which roles exist in which kind of org, and who may manage members. ADMIN is never assignable here. */
public final class OrgRoles {

    private OrgRoles() {
    }

    public static Set<UserRole> assignable(OrgType type) {
        return type == OrgType.BUYER
                ? EnumSet.of(UserRole.BUYER_ADMIN, UserRole.BUYER_APPROVER)
                : EnumSet.of(UserRole.DEVELOPER);
    }

    public static boolean canManageMembers(UserRole role) {
        return role == UserRole.BUYER_ADMIN || role == UserRole.DEVELOPER;
    }

    /** The role that must always have at least one holder in an org of this type. */
    public static UserRole adminRoleOf(OrgType type) {
        return type == OrgType.BUYER ? UserRole.BUYER_ADMIN : UserRole.DEVELOPER;
    }
}
```

- [ ] **Step 5: Requests and `IdentityService`**

Replace `identity/SignupRequest.java`:

```java
package com.agentstore.platform.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Size(max = 255) String orgName,
        @Email @NotBlank String email,
        @NotBlank String password,
        @NotNull SignupSide side) {
}
```

Replace `identity/LoginRequest.java`:

```java
package com.agentstore.platform.identity;

import jakarta.validation.constraints.NotBlank;

/** {@code remember} asks for a refresh token ("Keep me signed in on this device"); a missing field reads as false. */
public record LoginRequest(@NotBlank String email, @NotBlank String password, boolean remember) {
}
```

`identity/IdentityService.java`:

```java
package com.agentstore.platform.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Signup, which membership a login lands in, and adding orgs to an existing person. */
@Service
public class IdentityService {

    private final OrgRepository orgRepository;
    private final UserRepository userRepository;
    private final OrgMembershipRepository membershipRepository;
    private final JwtService jwtService;
    private final Set<String> adminEmails;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public IdentityService(OrgRepository orgRepository, UserRepository userRepository,
                           OrgMembershipRepository membershipRepository, JwtService jwtService,
                           @Value("${agentstore.identity.admin-emails:}") List<String> adminEmails) {
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.jwtService = jwtService;
        this.adminEmails = adminEmails.stream().filter(e -> !e.isBlank()).map(IdentityService::normalizeEmail)
                .collect(Collectors.toSet());
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public OrgMembership signup(SignupRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
        }
        User user;
        try {
            user = userRepository.saveAndFlush(new User(null, email, hashPassword(request.password()),
                    request.fullName().trim()));
        } catch (DataIntegrityViolationException e) {
            // Two signups for the same email raced past the exists check.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
        }
        return addOrg(user, request.side(), request.orgName());
    }

    /** Creates an org of the given side with this user as its first member, and makes it the landing org. */
    @Transactional
    public OrgMembership addOrg(User user, SignupSide side, String orgName) {
        Org org = orgRepository.save(new Org(null, orgName.trim(), side.orgType()));
        UserRole role = side == SignupSide.DEVELOPER && adminEmails.contains(user.getEmail())
                ? UserRole.ADMIN
                : OrgRoles.adminRoleOf(side.orgType());
        OrgMembership membership = membershipRepository.save(new OrgMembership(user, org, role, null));
        user.setLastOrgId(org.getId());
        userRepository.save(user);
        return membership;
    }

    /** The last org the user worked in if they are still a member, otherwise their oldest membership. */
    public Optional<OrgMembership> landingMembership(User user) {
        List<OrgMembership> memberships = membershipRepository.findByIdUserIdOrderByCreatedAtAsc(user.getId());
        return memberships.stream()
                .filter(m -> m.getId().getOrgId().equals(user.getLastOrgId()))
                .findFirst()
                .or(() -> memberships.stream().findFirst());
    }

    public String accessTokenFor(OrgMembership membership) {
        return jwtService.issue(membership.getId().getUserId(), membership.getId().getOrgId(), membership.getRole());
    }

    public boolean passwordMatches(User user, String rawPassword) {
        return user.getPasswordHash() != null && passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    public String hashPassword(String raw) {
        return passwordEncoder.encode(raw);
    }
}
```

Replace `identity/AuthController.java`:

```java
package com.agentstore.platform.identity;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final IdentityService identityService;

    public AuthController(UserRepository userRepository, IdentityService identityService) {
        this.userRepository = userRepository;
        this.identityService = identityService;
    }

    @PostMapping("/signup")
    public AuthResponse signup(@Valid @RequestBody SignupRequest request) {
        OrgMembership membership = identityService.signup(request);
        return new AuthResponse(identityService.accessTokenFor(membership));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        User user = userRepository.findByEmailIgnoreCase(IdentityService.normalizeEmail(request.email()))
                .filter(u -> identityService.passwordMatches(u, request.password()))
                .orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        OrgMembership membership = identityService.landingMembership(user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You are not a member of any workspace"));
        return ResponseEntity.ok(new AuthResponse(identityService.accessTokenFor(membership)));
    }
}
```

- [ ] **Step 6: Config**

In `backend/src/main/resources/application.yml`, under `agentstore:` add:

```yaml
  identity:
    admin-emails: ${ADMIN_EMAILS:}
```

In `backend/src/test/resources/application-test.yml`, under `agentstore:` add:

```yaml
  identity:
    admin-emails: admin@platform.example,admin-auth-test@platform.example
```

- [ ] **Step 7: Run the identity and end-to-end tests**

Run: `mvn -q test -Dtest='AuthControllerTest,UserRepositoryTest,SecurityConfigTest,JwtServiceTest,EndToEnd*Test,PlatformApplicationTest'`
Expected: all PASS. (`PlatformApplicationTest` proves `ddl-auto: validate` accepts the new mapping.)

- [ ] **Step 8: Run the whole backend suite**

Run: `mvn -q test`
Expected: all PASS. Tests that mint tokens with random user ids are unaffected, because nothing in this task reads memberships during authorization.

- [ ] **Step 9: Commit**

```bash
git add backend/src
git commit -m "feat(identity): memberships so one person can belong to several orgs; signup picks a side"
```

---

### Task 2: Who am I, switch org, add the other side

**Files:**
- Create: `identity/MeController.java`, `identity/MeResponse.java`
- Modify: `identity/AuthController.java` (switch-org)
- Test: create `identity/MeControllerTest.java`

**Interfaces:**
- Consumes: `IdentityService.landingMembership/addOrg/accessTokenFor`, `OrgMembershipRepository` (Task 1).
- Produces:
  - `GET /api/v1/me` → `MeResponse(UUID userId, String email, String fullName, boolean emailVerified, UUID activeOrgId, List<MeResponse.Membership> memberships)` with `Membership(UUID orgId, String orgName, OrgType orgType, UserRole role, String title)`. 404 if the token's user does not exist.
  - `POST /api/v1/auth/switch-org` `{orgId}` → `AuthResponse`, 403 if not a member.
  - `POST /api/v1/me/orgs` `{side, orgName}` → `AuthResponse` for the new org.
  - `static JwtService.Claims MeController.currentClaims()` for other identity controllers.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/agentstore/platform/identity/MeControllerTest.java`:

```java
package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MeControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    IdentityFixtures identity;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    String email() {
        return "me-" + UUID.randomUUID() + "@acme.example";
    }

    @Test
    void meListsEveryMembershipAndTheActiveOrg() {
        OrgMembership buyer = identity.buyerAdmin(email());

        var me = rest.exchange(url("/api/v1/me"), HttpMethod.GET, new HttpEntity<>(bearer(identity.tokenFor(buyer))),
                MeResponse.class).getBody();

        assertThat(me.activeOrgId()).isEqualTo(buyer.getOrg().getId());
        assertThat(me.memberships()).singleElement().satisfies(m -> {
            assertThat(m.role()).isEqualTo(UserRole.BUYER_ADMIN);
            assertThat(m.title()).isEqualTo("Finance lead");
        });
    }

    @Test
    void addingTheOtherSideCreatesADeveloperOrgAndReturnsATokenForIt() {
        OrgMembership buyer = identity.buyerAdmin(email());

        var response = rest.exchange(url("/api/v1/me/orgs"), HttpMethod.POST,
                new HttpEntity<>(Map.of("side", "DEVELOPER", "orgName", "Priya's studio"),
                        bearer(identity.tokenFor(buyer))), AuthResponse.class);

        var claims = jwtService.parse(response.getBody().token()).orElseThrow();
        assertThat(claims.role()).isEqualTo(UserRole.DEVELOPER);
        assertThat(claims.userId()).isEqualTo(buyer.getUser().getId());
        var me = rest.exchange(url("/api/v1/me"), HttpMethod.GET,
                new HttpEntity<>(bearer(response.getBody().token())), MeResponse.class).getBody();
        assertThat(me.memberships()).hasSize(2);
        assertThat(me.activeOrgId()).isEqualTo(claims.orgId());
    }

    @Test
    void switchingToAMembershipIssuesATokenForThatOrgAndLoginLandsThereNextTime() {
        OrgMembership buyer = identity.buyerAdmin(email());
        String devToken = rest.exchange(url("/api/v1/me/orgs"), HttpMethod.POST,
                new HttpEntity<>(Map.of("side", "DEVELOPER", "orgName", "Studio"), bearer(identity.tokenFor(buyer))),
                AuthResponse.class).getBody().token();

        var switched = rest.exchange(url("/api/v1/auth/switch-org"), HttpMethod.POST,
                new HttpEntity<>(Map.of("orgId", buyer.getOrg().getId()), bearer(devToken)), AuthResponse.class);
        assertThat(jwtService.parse(switched.getBody().token()).orElseThrow().orgId())
                .isEqualTo(buyer.getOrg().getId());

        var login = rest.postForEntity(url("/api/v1/auth/login"),
                new LoginRequest(buyer.getUser().getEmail(), "s3cret-pw", false), AuthResponse.class);
        assertThat(jwtService.parse(login.getBody().token()).orElseThrow().orgId())
                .isEqualTo(buyer.getOrg().getId());
    }

    @Test
    void switchingToAnOrgYouDoNotBelongToIsForbidden() {
        OrgMembership mine = identity.buyerAdmin(email());
        OrgMembership theirs = identity.buyerAdmin(email());

        var response = rest.exchange(url("/api/v1/auth/switch-org"), HttpMethod.POST,
                new HttpEntity<>(Map.of("orgId", theirs.getOrg().getId()), bearer(identity.tokenFor(mine))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void switchOrgWithoutATokenIsUnauthorized() {
        var response = rest.postForEntity(url("/api/v1/auth/switch-org"),
                Map.of("orgId", UUID.randomUUID()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `mvn -q test -Dtest=MeControllerTest`
Expected: compilation failure (`MeResponse` not found).

- [ ] **Step 3: Implement**

`identity/MeResponse.java`:

```java
package com.agentstore.platform.identity;

import java.util.List;
import java.util.UUID;

public record MeResponse(UUID userId, String email, String fullName, boolean emailVerified, UUID activeOrgId,
                         List<Membership> memberships) {

    public record Membership(UUID orgId, String orgName, OrgType orgType, UserRole role, String title) {
        static Membership from(OrgMembership m) {
            return new Membership(m.getOrg().getId(), m.getOrg().getName(), m.getOrg().getType(), m.getRole(),
                    m.getTitle());
        }
    }
}
```

`identity/MeController.java`:

```java
package com.agentstore.platform.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserRepository userRepository;
    private final OrgMembershipRepository membershipRepository;
    private final IdentityService identityService;

    public MeController(UserRepository userRepository, OrgMembershipRepository membershipRepository,
                        IdentityService identityService) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.identityService = identityService;
    }

    public static JwtService.Claims currentClaims() {
        return (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public MeResponse me() {
        var claims = currentClaims();
        User user = currentUser(claims);
        var memberships = membershipRepository.findByIdUserIdOrderByCreatedAtAsc(user.getId()).stream()
                .map(MeResponse.Membership::from).toList();
        return new MeResponse(user.getId(), user.getEmail(), user.getFullName(), user.getEmailVerifiedAt() != null,
                claims.orgId(), memberships);
    }

    public record AddOrgRequest(@NotNull SignupSide side, @NotBlank @Size(max = 255) String orgName) {}

    @PostMapping("/orgs")
    @PreAuthorize("isAuthenticated()")
    public AuthResponse addOrg(@Valid @RequestBody AddOrgRequest request) {
        User user = currentUser(currentClaims());
        return new AuthResponse(identityService.accessTokenFor(
                identityService.addOrg(user, request.side(), request.orgName())));
    }

    private User currentUser(JwtService.Claims claims) {
        return userRepository.findById(claims.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
```

In `AuthController.java` add the dependency `OrgMembershipRepository membershipRepository` (constructor + field) and this endpoint (imports `jakarta.validation.constraints.NotNull`, `org.springframework.security.access.prepost.PreAuthorize`, `java.util.UUID`):

```java
    public record SwitchOrgRequest(@NotNull UUID orgId) {}

    @PostMapping("/switch-org")
    @PreAuthorize("isAuthenticated()")
    public AuthResponse switchOrg(@Valid @RequestBody SwitchOrgRequest request) {
        var claims = MeController.currentClaims();
        OrgMembership membership = membershipRepository.findById(new OrgMembershipId(claims.userId(), request.orgId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of that org"));
        User user = membership.getUser();
        user.setLastOrgId(request.orgId());
        userRepository.save(user);
        return new AuthResponse(identityService.accessTokenFor(membership));
    }
```

`/api/v1/auth/**` is `permitAll` in `SecurityConfig`. An anonymous caller still fails `@PreAuthorize("isAuthenticated()")`, and Spring Security answers 401 through the configured entry point. The last test above pins that.

- [ ] **Step 4: Run the tests**

Run: `mvn -q test -Dtest='MeControllerTest,AuthControllerTest'`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(identity): /me, switch between memberships, and add the other side later"
```

---

### Task 3: Invite teammates and manage members

**Files:**
- Modify: `backend/pom.xml` (add `spring-boot-starter-mail`)
- Create: `backend/src/main/resources/db/migration/V18__invitation.sql`
- Create: `common/TokenHasher.java`, `common/EmailMessage.java`, `common/EmailSender.java`, `common/LoggingEmailSender.java`, `common/SmtpEmailSender.java`
- Create: `identity/Invitation.java`, `identity/InvitationRepository.java`, `identity/InvitationService.java`, `identity/InvitationController.java`, `identity/MembersController.java`
- Modify: `identity/SecurityConfig.java`, `application.yml`, `src/test/resources/application-test.yml`
- Test: create `common/TokenHasherTest.java`, `common/RecordingEmailSender.java`, `identity/InvitationFlowTest.java`, `identity/MembersControllerTest.java`; modify `EndToEndTierBTest.java`

**Interfaces:**
- Consumes: `IdentityService`, `OrgMembershipRepository`, `OrgRoles`, `MeController.currentClaims()` (Tasks 1–2).
- Produces:
  - `TokenHasher.newToken(): String`, `TokenHasher.sha256Hex(String): String`.
  - `EmailSender.send(EmailMessage)`, `record EmailMessage(String to, String subject, String text)`.
  - Test helper `RecordingEmailSender` (`@Primary @Component`): `Optional<EmailMessage> lastTo(String email)`, `static String tokenFromLink(EmailMessage, String path)` (the token after `/path/` in the body).
  - `POST /api/v1/orgs/{orgId}/invitations` `{email, role, title}` → 201 `InvitationService.View(UUID id, String email, UserRole role, String title, Instant expiresAt)`. `GET` lists pending ones. `DELETE /{id}` revokes.
  - `GET /api/v1/invitations/{token}` (public) → `InvitationService.Preview(String orgName, OrgType orgType, String email, UserRole role, String invitedBy)`. `POST /api/v1/invitations/{token}/accept` `{fullName?, password?}` → `AuthResponse` for the inviting org.
  - `GET /api/v1/orgs/{orgId}/members` → `List<MembersController.Member(UUID userId, String fullName, String email, UserRole role, String title)>`. `PATCH /members/{userId}` `{role?, title?}`. `DELETE /members/{userId}`.
  - Config: `agentstore.web.base-url`, `agentstore.email.mode`, `agentstore.email.from`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/agentstore/platform/common/TokenHasherTest.java`:

```java
package com.agentstore.platform.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenHasherTest {

    @Test
    void tokensAreLongRandomAndUrlSafe() {
        String a = TokenHasher.newToken();
        String b = TokenHasher.newToken();
        assertThat(a).hasSize(43).matches("[A-Za-z0-9_-]+").isNotEqualTo(b);
    }

    @Test
    void hashIsStableLowercaseHexSha256() {
        assertThat(TokenHasher.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
```

`backend/src/test/java/com/agentstore/platform/common/RecordingEmailSender.java`:

```java
package com.agentstore.platform.common;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Captures outgoing email in tests. Recipients are unique per test, so tests look up by recipient. */
@Component
@Primary
public class RecordingEmailSender implements EmailSender {

    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(EmailMessage message) {
        sent.add(message);
    }

    public Optional<EmailMessage> lastTo(String email) {
        String to = email.trim().toLowerCase();
        for (int i = sent.size() - 1; i >= 0; i--) {
            if (sent.get(i).to().equals(to)) return Optional.of(sent.get(i));
        }
        return Optional.empty();
    }

    public long countTo(String email) {
        String to = email.trim().toLowerCase();
        return sent.stream().filter(m -> m.to().equals(to)).count();
    }

    public static String tokenFromLink(EmailMessage message, String path) {
        Matcher m = Pattern.compile("/" + path + "/([A-Za-z0-9_-]+)").matcher(message.text());
        if (!m.find()) throw new AssertionError("No /" + path + "/ link in: " + message.text());
        return m.group(1);
    }
}
```

`backend/src/test/java/com/agentstore/platform/identity/InvitationFlowTest.java`:

```java
package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.common.RecordingEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InvitationFlowTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    IdentityFixtures identity;
    @Autowired
    RecordingEmailSender emails;
    @Autowired
    UserRepository userRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    String email(String who) {
        return who + "-" + UUID.randomUUID() + "@acme.example";
    }

    String invite(OrgMembership admin, String email, String role) {
        var response = rest.exchange(url("/api/v1/orgs/" + admin.getOrg().getId() + "/invitations"), HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "role", role, "title", "Accounts manager"),
                        bearer(identity.tokenFor(admin))), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return RecordingEmailSender.tokenFromLink(emails.lastTo(email).orElseThrow(), "invite");
    }

    @Test
    void aNewPersonAcceptsAndLandsInTheInvitingOrgWithTheInvitedRole() {
        OrgMembership admin = identity.buyerAdmin(email("priya"));
        String arjun = email("arjun");
        String token = invite(admin, arjun, "BUYER_APPROVER");

        var preview = rest.getForEntity(url("/api/v1/invitations/" + token), Map.class).getBody();
        assertThat(preview.get("role")).isEqualTo("BUYER_APPROVER");
        assertThat(preview.get("email")).isEqualTo(arjun);

        var accepted = rest.postForEntity(url("/api/v1/invitations/" + token + "/accept"),
                Map.of("fullName", "Arjun Mehta", "password", "s3cret-pw"), AuthResponse.class);

        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        var claims = jwtService.parse(accepted.getBody().token()).orElseThrow();
        assertThat(claims.orgId()).isEqualTo(admin.getOrg().getId());
        assertThat(claims.role()).isEqualTo(UserRole.BUYER_APPROVER);
        // The invite link was delivered to that inbox, so accepting it proves the address.
        assertThat(userRepository.findById(claims.userId()).orElseThrow().getEmailVerifiedAt()).isNotNull();
    }

    @Test
    void anInvitationCanBeUsedOnlyOnce() {
        OrgMembership admin = identity.buyerAdmin(email("priya"));
        String token = invite(admin, email("once"), "BUYER_APPROVER");
        rest.postForEntity(url("/api/v1/invitations/" + token + "/accept"),
                Map.of("fullName", "Once", "password", "s3cret-pw"), AuthResponse.class);

        var again = rest.postForEntity(url("/api/v1/invitations/" + token + "/accept"),
                Map.of("fullName", "Twice", "password", "s3cret-pw"), String.class);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void anExistingAccountMustBeSignedInToAccept() {
        OrgMembership admin = identity.buyerAdmin(email("priya"));
        OrgMembership existing = identity.buyerAdmin(email("existing"));
        String token = invite(admin, existing.getUser().getEmail(), "BUYER_APPROVER");

        var anonymous = rest.postForEntity(url("/api/v1/invitations/" + token + "/accept"),
                Map.of("fullName", "Hijack", "password", "other-pw"), String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var signedIn = rest.exchange(url("/api/v1/invitations/" + token + "/accept"), HttpMethod.POST,
                new HttpEntity<>(Map.of(), bearer(identity.tokenFor(existing))), AuthResponse.class);
        assertThat(signedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jwtService.parse(signedIn.getBody().token()).orElseThrow().orgId())
                .isEqualTo(admin.getOrg().getId());
    }

    @Test
    void acceptingAsADifferentSignedInPersonIsForbidden() {
        OrgMembership admin = identity.buyerAdmin(email("priya"));
        OrgMembership someoneElse = identity.buyerAdmin(email("else"));
        String token = invite(admin, email("intended"), "BUYER_APPROVER");

        var response = rest.exchange(url("/api/v1/invitations/" + token + "/accept"), HttpMethod.POST,
                new HttpEntity<>(Map.of(), bearer(identity.tokenFor(someoneElse))), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void buyersCannotInviteDeveloperOrAdminRoles() {
        OrgMembership admin = identity.buyerAdmin(email("priya"));

        for (String role : new String[]{"DEVELOPER", "ADMIN"}) {
            var response = rest.exchange(url("/api/v1/orgs/" + admin.getOrg().getId() + "/invitations"),
                    HttpMethod.POST, new HttpEntity<>(Map.of("email", email("x"), "role", role),
                            bearer(identity.tokenFor(admin))), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void approversCannotInvite() {
        OrgMembership admin = identity.buyerAdmin(email("priya"));
        OrgMembership approver = identity.member(admin.getOrg(), email("appr"), UserRole.BUYER_APPROVER, null);

        var response = rest.exchange(url("/api/v1/orgs/" + admin.getOrg().getId() + "/invitations"),
                HttpMethod.POST, new HttpEntity<>(Map.of("email", email("x"), "role", "BUYER_APPROVER"),
                        bearer(identity.tokenFor(approver))), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aRevokedInvitationIsGone() {
        OrgMembership admin = identity.buyerAdmin(email("priya"));
        String invited = email("revoked");
        String token = invite(admin, invited, "BUYER_APPROVER");
        var pending = rest.exchange(url("/api/v1/orgs/" + admin.getOrg().getId() + "/invitations"), HttpMethod.GET,
                new HttpEntity<>(bearer(identity.tokenFor(admin))), Map[].class).getBody();
        Object id = java.util.Arrays.stream(pending).filter(i -> invited.equals(i.get("email"))).findFirst()
                .orElseThrow().get("id");

        rest.exchange(url("/api/v1/orgs/" + admin.getOrg().getId() + "/invitations/" + id), HttpMethod.DELETE,
                new HttpEntity<>(bearer(identity.tokenFor(admin))), Void.class);

        assertThat(rest.getForEntity(url("/api/v1/invitations/" + token), String.class).getStatusCode())
                .isEqualTo(HttpStatus.GONE);
    }
}
```

`backend/src/test/java/com/agentstore/platform/identity/MembersControllerTest.java`:

```java
package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MembersControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    IdentityFixtures identity;
    @Autowired
    OrgMembershipRepository membershipRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    String email() {
        return "member-" + UUID.randomUUID() + "@acme.example";
    }

    String members(OrgMembership m) {
        return url("/api/v1/orgs/" + m.getOrg().getId() + "/members");
    }

    @Test
    void anyMemberCanListTheTeamWithNamesAndTitles() {
        OrgMembership admin = identity.buyerAdmin(email());
        OrgMembership approver = identity.member(admin.getOrg(), email(), UserRole.BUYER_APPROVER, "Accounts manager");

        var list = rest.exchange(members(admin), HttpMethod.GET, new HttpEntity<>(bearer(identity.tokenFor(approver))),
                Map[].class).getBody();

        assertThat(list).hasSize(2);
        assertThat(list).anySatisfy(m -> assertThat(m.get("title")).isEqualTo("Accounts manager"));
    }

    @Test
    void outsidersCannotListTheTeam() {
        OrgMembership admin = identity.buyerAdmin(email());
        OrgMembership outsider = identity.buyerAdmin(email());

        var response = rest.exchange(members(admin), HttpMethod.GET,
                new HttpEntity<>(bearer(identity.tokenFor(outsider))), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminCanChangeARoleAndTitle() {
        OrgMembership admin = identity.buyerAdmin(email());
        OrgMembership approver = identity.member(admin.getOrg(), email(), UserRole.BUYER_APPROVER, null);

        rest.exchange(members(admin) + "/" + approver.getUser().getId(), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("role", "BUYER_ADMIN", "title", "Controller"), bearer(identity.tokenFor(admin))),
                Void.class);

        OrgMembership after = membershipRepository.findById(approver.getId()).orElseThrow();
        assertThat(after.getRole()).isEqualTo(UserRole.BUYER_ADMIN);
        assertThat(after.getTitle()).isEqualTo("Controller");
    }

    @Test
    void theLastAdminCannotBeDemotedOrRemoved() {
        OrgMembership admin = identity.buyerAdmin(email());
        String self = members(admin) + "/" + admin.getUser().getId();

        var demote = rest.exchange(self, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("role", "BUYER_APPROVER"), bearer(identity.tokenFor(admin))), String.class);
        var remove = rest.exchange(self, HttpMethod.DELETE, new HttpEntity<>(bearer(identity.tokenFor(admin))),
                String.class);

        assertThat(demote.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(remove.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void removingAMemberDeletesTheMembershipOnly() {
        OrgMembership admin = identity.buyerAdmin(email());
        OrgMembership approver = identity.member(admin.getOrg(), email(), UserRole.BUYER_APPROVER, null);

        var response = rest.exchange(members(admin) + "/" + approver.getUser().getId(), HttpMethod.DELETE,
                new HttpEntity<>(bearer(identity.tokenFor(admin))), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(membershipRepository.findById(approver.getId())).isEmpty();
    }
}
```

Finally, in `EndToEndTierBTest.java`, replace the minted approver token with a real teammate. Add fields:

```java
    @Autowired
    com.agentstore.platform.common.RecordingEmailSender emails;
```

Replace the line `String approverToken = mintApproverTokenForSameOrg(buyerOrgId);` and the comment block above it (the `// NOTE: signup always creates a NEW org …` lines) with:

```java
        // A second person joins the same workspace as an approver through a real invitation.
        rest.exchange(url("/api/v1/orgs/" + buyerOrgId + "/invitations"), HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "approver@acme.example", "role", "BUYER_APPROVER"),
                        bearer(buyerLogin.token())), Void.class);
        String inviteToken = com.agentstore.platform.common.RecordingEmailSender.tokenFromLink(
                emails.lastTo("approver@acme.example").orElseThrow(), "invite");
        String approverToken = rest.postForEntity(url("/api/v1/invitations/" + inviteToken + "/accept"),
                Map.of("fullName", "Arjun Mehta", "password", "pw"), AuthResponse.class).getBody().token();
```

Delete the `@Autowired JwtService jwtService;` field and the `mintApproverTokenForSameOrg` method at the bottom of the class.

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `mvn -q test -Dtest='TokenHasherTest,InvitationFlowTest,MembersControllerTest'`
Expected: compilation failure (`TokenHasher`, `EmailSender` not found).

- [ ] **Step 3: Dependency, migration and config**

In `backend/pom.xml` add inside `<dependencies>`:

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-mail</artifactId>
    </dependency>
```

`backend/src/main/resources/db/migration/V18__invitation.sql`:

```sql
CREATE TABLE invitation (
    id                 UUID         PRIMARY KEY,
    org_id             UUID         NOT NULL REFERENCES org(id),
    email              VARCHAR(255) NOT NULL,
    role               VARCHAR(30)  NOT NULL,
    title              VARCHAR(100),
    token_hash         CHAR(64)     NOT NULL UNIQUE,
    invited_by_user_id UUID         REFERENCES app_user(id),
    expires_at         TIMESTAMPTZ  NOT NULL,
    accepted_at        TIMESTAMPTZ,
    revoked_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX invitation_org_idx ON invitation (org_id);
```

In `application.yml` under `agentstore:` add:

```yaml
  web:
    base-url: ${WEB_BASE_URL:http://localhost:3000}
  email:
    mode: ${EMAIL_MODE:log}          # log | smtp
    from: ${EMAIL_FROM:Karya <no-reply@karya.example>}
```

and at the top level, under `spring:`, add (read only when `agentstore.email.mode=smtp`):

```yaml
  mail:
    host: ${SMTP_HOST:localhost}
    port: ${SMTP_PORT:587}
    username: ${SMTP_USERNAME:}
    password: ${SMTP_PASSWORD:}
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true
```

In `application-test.yml` under `agentstore:` add:

```yaml
  email:
    mode: log
```

- [ ] **Step 4: Common helpers**

`common/TokenHasher.java`:

```java
package com.agentstore.platform.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** One-time secrets: 32 random bytes handed to a person once, stored only as a SHA-256 hash. */
public final class TokenHasher {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenHasher() {
    }

    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`common/EmailMessage.java`:

```java
package com.agentstore.platform.common;

/** A plain-text email. {@code to} is always a normalized (lowercase) address. */
public record EmailMessage(String to, String subject, String text) {
}
```

`common/EmailSender.java`:

```java
package com.agentstore.platform.common;

public interface EmailSender {
    void send(EmailMessage message);
}
```

`common/LoggingEmailSender.java`:

```java
package com.agentstore.platform.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Local development: logs who would get what, never the body (bodies carry one-time links). */
@Component
@ConditionalOnProperty(name = "agentstore.email.mode", havingValue = "log", matchIfMissing = true)
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(EmailMessage message) {
        log.info("Email (not sent, email.mode=log) to={} subject={}", message.to(), message.subject());
    }
}
```

`common/SmtpEmailSender.java`:

```java
package com.agentstore.platform.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Works with any SMTP relay, including Amazon SES's SMTP interface. */
@Component
@ConditionalOnProperty(name = "agentstore.email.mode", havingValue = "smtp")
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender, @Value("${agentstore.email.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(EmailMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.text());
        mailSender.send(mail);
    }
}
```

- [ ] **Step 5: Invitations**

`identity/Invitation.java`:

```java
package com.agentstore.platform.identity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invitation")
@Getter
@Setter
@NoArgsConstructor
public class Invitation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "org_id")
    private Org org;

    private String email;

    @Enumerated(EnumType.STRING)
    private UserRole role;

    private String title;
    private String tokenHash;

    @ManyToOne
    @JoinColumn(name = "invited_by_user_id")
    private User invitedBy;

    private Instant expiresAt;
    private Instant acceptedAt;
    private Instant revokedAt;
    private Instant createdAt;

    public boolean isUsable(Instant now) {
        return acceptedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`identity/InvitationRepository.java`:

```java
package com.agentstore.platform.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {
    Optional<Invitation> findByTokenHash(String tokenHash);

    List<Invitation> findByOrgIdAndAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAtDesc(UUID orgId);
}
```

`identity/InvitationService.java`:

```java
package com.agentstore.platform.identity;

import com.agentstore.platform.common.EmailMessage;
import com.agentstore.platform.common.EmailSender;
import com.agentstore.platform.common.TokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class InvitationService {

    static final Duration INVITATION_TTL = Duration.ofDays(7);

    private final InvitationRepository invitationRepository;
    private final OrgRepository orgRepository;
    private final UserRepository userRepository;
    private final OrgMembershipRepository membershipRepository;
    private final IdentityService identityService;
    private final EmailSender emailSender;
    private final String webBaseUrl;

    public InvitationService(InvitationRepository invitationRepository, OrgRepository orgRepository,
                             UserRepository userRepository, OrgMembershipRepository membershipRepository,
                             IdentityService identityService, EmailSender emailSender,
                             @Value("${agentstore.web.base-url}") String webBaseUrl) {
        this.invitationRepository = invitationRepository;
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.identityService = identityService;
        this.emailSender = emailSender;
        this.webBaseUrl = webBaseUrl;
    }

    public record View(UUID id, String email, UserRole role, String title, Instant expiresAt) {
        static View from(Invitation i) {
            return new View(i.getId(), i.getEmail(), i.getRole(), i.getTitle(), i.getExpiresAt());
        }
    }

    public record Preview(String orgName, OrgType orgType, String email, UserRole role, String invitedBy) {}

    @Transactional
    public View invite(UUID orgId, UUID inviterUserId, String rawEmail, UserRole role, String title) {
        Org org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
        if (!OrgRoles.assignable(org.getType()).contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role " + role + " is not available here");
        }
        String email = IdentityService.normalizeEmail(rawEmail);
        boolean alreadyMember = userRepository.findByEmailIgnoreCase(email)
                .flatMap(u -> membershipRepository.findById(new OrgMembershipId(u.getId(), orgId)))
                .isPresent();
        if (alreadyMember) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already a member of this workspace");
        }
        User inviter = userRepository.findById(inviterUserId).orElse(null);
        String rawToken = TokenHasher.newToken();

        Invitation invitation = new Invitation();
        invitation.setOrg(org);
        invitation.setEmail(email);
        invitation.setRole(role);
        invitation.setTitle(title);
        invitation.setTokenHash(TokenHasher.sha256Hex(rawToken));
        invitation.setInvitedBy(inviter);
        invitation.setExpiresAt(Instant.now().plus(INVITATION_TTL));
        invitation = invitationRepository.save(invitation);

        String inviterName = inviter != null && inviter.getFullName() != null ? inviter.getFullName() : "A teammate";
        emailSender.send(new EmailMessage(email, inviterName + " invited you to " + org.getName() + " on Karya",
                inviterName + " invited you to join " + org.getName() + " on Karya.\n\n"
                        + "Accept the invitation: " + webBaseUrl + "/invite/" + rawToken + "\n\n"
                        + "The link works once and expires in 7 days."));
        return View.from(invitation);
    }

    public List<View> pending(UUID orgId) {
        Instant now = Instant.now();
        return invitationRepository.findByOrgIdAndAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAtDesc(orgId).stream()
                .filter(i -> i.getExpiresAt().isAfter(now))
                .map(View::from).toList();
    }

    @Transactional
    public void revoke(UUID orgId, UUID invitationId) {
        Invitation invitation = invitationRepository.findById(invitationId)
                .filter(i -> i.getOrg().getId().equals(orgId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found"));
        if (invitation.getRevokedAt() == null && invitation.getAcceptedAt() == null) {
            invitation.setRevokedAt(Instant.now());
            invitationRepository.save(invitation);
        }
    }

    public Preview preview(String rawToken) {
        Invitation i = usable(rawToken);
        String inviter = i.getInvitedBy() != null ? i.getInvitedBy().getFullName() : null;
        return new Preview(i.getOrg().getName(), i.getOrg().getType(), i.getEmail(), i.getRole(), inviter);
    }

    /**
     * Accepts an invitation. {@code signedInUserId} is null for an anonymous caller. A brand-new person needs a
     * name and password; someone who already has an account must be signed in as that account.
     */
    @Transactional
    public OrgMembership accept(String rawToken, UUID signedInUserId, String fullName, String password) {
        Invitation invitation = usable(rawToken);
        User user = userRepository.findByEmailIgnoreCase(invitation.getEmail()).orElse(null);

        if (signedInUserId != null) {
            if (user == null || !user.getId().equals(signedInUserId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "This invitation was sent to a different email address");
            }
        } else if (user != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An account with this email exists. Sign in, then accept the invitation.");
        } else {
            if (fullName == null || fullName.isBlank() || password == null || password.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Full name and password are required");
            }
            user = new User(null, invitation.getEmail(), identityService.hashPassword(password), fullName.trim());
            user.setEmailVerifiedAt(Instant.now());   // the link reached this inbox
            user = userRepository.save(user);
        }

        final User member = user;
        OrgMembership membership = membershipRepository
                .findById(new OrgMembershipId(member.getId(), invitation.getOrg().getId()))
                .orElseGet(() -> membershipRepository.save(new OrgMembership(member, invitation.getOrg(),
                        invitation.getRole(), invitation.getTitle())));
        invitation.setAcceptedAt(Instant.now());
        invitationRepository.save(invitation);
        user.setLastOrgId(invitation.getOrg().getId());
        userRepository.save(user);
        return membership;
    }

    private Invitation usable(String rawToken) {
        return invitationRepository.findByTokenHash(TokenHasher.sha256Hex(rawToken))
                .filter(i -> i.isUsable(Instant.now()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE,
                        "This invitation has expired or was already used"));
    }
}
```

`identity/InvitationController.java`:

```java
package com.agentstore.platform.identity;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Public endpoints behind the emailed /invite/{token} link. */
@RestController
@RequestMapping("/api/v1/invitations/{token}")
public class InvitationController {

    private final InvitationService invitationService;
    private final IdentityService identityService;

    public InvitationController(InvitationService invitationService, IdentityService identityService) {
        this.invitationService = invitationService;
        this.identityService = identityService;
    }

    @GetMapping
    public InvitationService.Preview preview(@PathVariable String token) {
        return invitationService.preview(token);
    }

    public record AcceptRequest(String fullName, String password) {}

    @PostMapping("/accept")
    public AuthResponse accept(@PathVariable String token, @RequestBody(required = false) AcceptRequest request) {
        AcceptRequest body = request == null ? new AcceptRequest(null, null) : request;
        OrgMembership membership = invitationService.accept(token, signedInUserId(), body.fullName(), body.password());
        return new AuthResponse(identityService.accessTokenFor(membership));
    }

    private static UUID signedInUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof JwtService.Claims claims ? claims.userId() : null;
    }
}
```

- [ ] **Step 6: Members and org-scoped invitation management**

`identity/MembersController.java`:

```java
package com.agentstore.platform.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}")
public class MembersController {

    private final OrgMembershipRepository membershipRepository;
    private final OrgRepository orgRepository;
    private final UserRepository userRepository;
    private final InvitationService invitationService;

    public MembersController(OrgMembershipRepository membershipRepository, OrgRepository orgRepository,
                             UserRepository userRepository, InvitationService invitationService) {
        this.membershipRepository = membershipRepository;
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.invitationService = invitationService;
    }

    public record Member(UUID userId, String fullName, String email, UserRole role, String title) {
        static Member from(OrgMembership m) {
            return new Member(m.getUser().getId(), m.getUser().getFullName(), m.getUser().getEmail(), m.getRole(),
                    m.getTitle());
        }
    }

    public record InviteRequest(@Email @NotBlank String email, @NotNull UserRole role, @Size(max = 100) String title) {}

    public record UpdateMemberRequest(UserRole role, @Size(max = 100) String title) {}

    @GetMapping("/members")
    @PreAuthorize("isAuthenticated()")
    public List<Member> members(@PathVariable UUID orgId) {
        requireSameOrg(orgId);
        return membershipRepository.findByIdOrgIdOrderByCreatedAtAsc(orgId).stream().map(Member::from).toList();
    }

    @PatchMapping("/members/{userId}")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public Member update(@PathVariable UUID orgId, @PathVariable UUID userId,
                         @Valid @RequestBody UpdateMemberRequest request) {
        requireManager(orgId);
        OrgMembership membership = membershipRepository.findById(new OrgMembershipId(userId, orgId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
        if (request.role() != null && request.role() != membership.getRole()) {
            if (!OrgRoles.assignable(membership.getOrg().getType()).contains(request.role())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role not available here");
            }
            requireAnotherAdminRemains(membership);
            membership.setRole(request.role());
        }
        if (request.title() != null) {
            membership.setTitle(request.title().isBlank() ? null : request.title().trim());
        }
        return Member.from(membershipRepository.save(membership));
    }

    @DeleteMapping("/members/{userId}")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public ResponseEntity<Void> remove(@PathVariable UUID orgId, @PathVariable UUID userId) {
        requireManager(orgId);
        OrgMembership membership = membershipRepository.findById(new OrgMembershipId(userId, orgId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
        requireAnotherAdminRemains(membership);
        membershipRepository.delete(membership);
        User user = membership.getUser();
        if (orgId.equals(user.getLastOrgId())) {
            user.setLastOrgId(null);
            userRepository.save(user);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/invitations")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<InvitationService.View> invite(@PathVariable UUID orgId,
                                                         @Valid @RequestBody InviteRequest request) {
        var claims = requireManager(orgId);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                invitationService.invite(orgId, claims.userId(), request.email(), request.role(), request.title()));
    }

    @GetMapping("/invitations")
    @PreAuthorize("isAuthenticated()")
    public List<InvitationService.View> pendingInvitations(@PathVariable UUID orgId) {
        requireManager(orgId);
        return invitationService.pending(orgId);
    }

    @DeleteMapping("/invitations/{invitationId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> revoke(@PathVariable UUID orgId, @PathVariable UUID invitationId) {
        requireManager(orgId);
        invitationService.revoke(orgId, invitationId);
        return ResponseEntity.noContent().build();
    }

    private JwtService.Claims requireSameOrg(UUID orgId) {
        var claims = MeController.currentClaims();
        if (!claims.orgId().equals(orgId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your workspace");
        }
        return claims;
    }

    private JwtService.Claims requireManager(UUID orgId) {
        var claims = requireSameOrg(orgId);
        if (!OrgRoles.canManageMembers(claims.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only workspace admins can manage members");
        }
        return claims;
    }

    /**
     * Refuses to leave an org without anyone holding its admin role. Locks the org row first so two admins
     * removing each other at the same moment cannot both pass the count. Both callers are @Transactional.
     */
    private void requireAnotherAdminRemains(OrgMembership membership) {
        orgRepository.findByIdForUpdate(membership.getId().getOrgId());
        UserRole adminRole = OrgRoles.adminRoleOf(membership.getOrg().getType());
        if (membership.getRole() == adminRole
                && membershipRepository.countByIdOrgIdAndRole(membership.getId().getOrgId(), adminRole) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A workspace needs at least one admin");
        }
    }
}
```

`requireAnotherAdminRemains` locks the org row before counting. Add the lock query to `OrgRepository`:

```java
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from Org o where o.id = :id")
    java.util.Optional<Org> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") java.util.UUID id);
```

- [ ] **Step 7: Open the public invitation endpoints**

In `SecurityConfig.filterChain`, change the first matcher to:

```java
                    .requestMatchers("/api/v1/auth/**", "/api/v1/invitations/**", "/actuator/**", "/internal/**").permitAll()
```

The JWT filter still runs on these paths, so a signed-in person who accepts is recognised.

- [ ] **Step 8: Run the tests**

Run: `mvn -q test -Dtest='TokenHasherTest,InvitationFlowTest,MembersControllerTest,EndToEndTierBTest,SecurityConfigTest'`
Expected: all PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/pom.xml backend/src
git commit -m "feat(identity): invite teammates with a role and title, and manage workspace members"
```

---

### Task 4: Keep me signed in (rotating refresh tokens) and log out

**Files:**
- Create: `backend/src/main/resources/db/migration/V19__refresh_token.sql`
- Create: `identity/RefreshToken.java`, `identity/RefreshTokenRepository.java`, `identity/RefreshTokenService.java`
- Modify: `identity/AuthResponse.java`, `identity/AuthController.java`, `identity/MeController.java`, `identity/InvitationController.java`, `application.yml`
- Test: create `identity/RefreshTokenTest.java`

**Interfaces:**
- Consumes: `TokenHasher` (Task 3), `IdentityService.landingMembership/accessTokenFor` (Task 1).
- Produces:
  - `AuthResponse(String token, String refreshToken)`, with `static AuthResponse access(String token)` for responses without a refresh token.
  - `RefreshTokenService.issue(UUID userId): String` (raw token, new family), `rotate(String raw): Rotation(UUID userId, String newRawToken)` (throws 401), `revokeFamilyOf(String raw)`, `revokeAllFor(UUID userId)`.
  - `POST /api/v1/auth/refresh` `{refreshToken}` → `AuthResponse` with both tokens. `POST /api/v1/auth/logout` `{refreshToken}` → 204.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/agentstore/platform/identity/RefreshTokenTest.java`:

```java
package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    IdentityFixtures identity;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    AuthResponse login(String email, boolean remember) {
        return rest.postForEntity(url("/api/v1/auth/login"), new LoginRequest(email, "s3cret-pw", remember),
                AuthResponse.class).getBody();
    }

    String email() {
        return "refresh-" + UUID.randomUUID() + "@acme.example";
    }

    @Test
    void loginWithoutRememberGivesNoRefreshToken() {
        String email = identity.buyerAdmin(email()).getUser().getEmail();
        assertThat(login(email, false).refreshToken()).isNull();
    }

    @Test
    void refreshRotatesTheTokenAndIssuesANewAccessToken() {
        String email = identity.buyerAdmin(email()).getUser().getEmail();
        AuthResponse first = login(email, true);

        var refreshed = rest.postForEntity(url("/api/v1/auth/refresh"),
                Map.of("refreshToken", first.refreshToken()), AuthResponse.class);

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody().token()).isNotBlank();
        assertThat(refreshed.getBody().refreshToken()).isNotBlank().isNotEqualTo(first.refreshToken());
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeFamily() {
        String email = identity.buyerAdmin(email()).getUser().getEmail();
        String original = login(email, true).refreshToken();
        String successor = rest.postForEntity(url("/api/v1/auth/refresh"), Map.of("refreshToken", original),
                AuthResponse.class).getBody().refreshToken();

        var replay = rest.postForEntity(url("/api/v1/auth/refresh"), Map.of("refreshToken", original), String.class);
        var legit = rest.postForEntity(url("/api/v1/auth/refresh"), Map.of("refreshToken", successor), String.class);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(legit.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutEndsTheFamily() {
        String email = identity.buyerAdmin(email()).getUser().getEmail();
        String token = login(email, true).refreshToken();

        var logout = rest.postForEntity(url("/api/v1/auth/logout"), Map.of("refreshToken", token), Void.class);
        var refresh = rest.postForEntity(url("/api/v1/auth/refresh"), Map.of("refreshToken", token), String.class);

        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anUnknownRefreshTokenIsUnauthorized() {
        var response = rest.postForEntity(url("/api/v1/auth/refresh"), Map.of("refreshToken", "nope"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `mvn -q test -Dtest=RefreshTokenTest`
Expected: compilation failure (`AuthResponse.refreshToken()` not found).

- [ ] **Step 3: Migration, entity, repository**

`backend/src/main/resources/db/migration/V19__refresh_token.sql`:

```sql
CREATE TABLE refresh_token (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES app_user(id),
    family_id   UUID        NOT NULL,
    token_hash  CHAR(64)    NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX refresh_token_family_idx ON refresh_token (family_id);
CREATE INDEX refresh_token_user_idx ON refresh_token (user_id);
```

`identity/RefreshToken.java`:

```java
package com.agentstore.platform.identity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_token")
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID userId;
    private UUID familyId;
    private String tokenHash;
    private Instant expiresAt;
    private Instant revokedAt;
    private Instant createdAt;

    public RefreshToken(UUID userId, UUID familyId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`identity/RefreshTokenRepository.java`:

```java
package com.agentstore.platform.identity;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :hash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("hash") String hash);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
```

- [ ] **Step 4: Service**

`identity/RefreshTokenService.java`:

```java
package com.agentstore.platform.identity;

import com.agentstore.platform.common.TokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Rotating refresh tokens. Each use replaces the token with a new one in the same family. Presenting a token
 * that was already replaced means a copy leaked, so the whole family is revoked.
 */
@Service
public class RefreshTokenService {

    private final RefreshTokenRepository repository;
    private final Duration ttl;

    public RefreshTokenService(RefreshTokenRepository repository,
                               @Value("${agentstore.identity.refresh-token-ttl:P30D}") Duration ttl) {
        this.repository = repository;
        this.ttl = ttl;
    }

    public record Rotation(UUID userId, String newRawToken) {}

    @Transactional
    public String issue(UUID userId) {
        return issueInFamily(userId, UUID.randomUUID());
    }

    /** Commits the reuse revocation even though the caller then gets a 401. */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Rotation rotate(String raw) {
        Instant now = Instant.now();
        RefreshToken current = repository.findByTokenHashForUpdate(TokenHasher.sha256Hex(raw))
                .orElseThrow(RefreshTokenService::unauthorized);
        if (current.getRevokedAt() != null) {
            repository.revokeFamily(current.getFamilyId(), now);
            throw unauthorized();
        }
        if (!current.getExpiresAt().isAfter(now)) {
            throw unauthorized();
        }
        current.setRevokedAt(now);
        repository.save(current);
        return new Rotation(current.getUserId(), issueInFamily(current.getUserId(), current.getFamilyId()));
    }

    @Transactional
    public void revokeFamilyOf(String raw) {
        repository.findByTokenHashForUpdate(TokenHasher.sha256Hex(raw))
                .ifPresent(t -> repository.revokeFamily(t.getFamilyId(), Instant.now()));
    }

    @Transactional
    public void revokeAllFor(UUID userId) {
        repository.revokeAllForUser(userId, Instant.now());
    }

    private String issueInFamily(UUID userId, UUID familyId) {
        String raw = TokenHasher.newToken();
        repository.save(new RefreshToken(userId, familyId, TokenHasher.sha256Hex(raw), Instant.now().plus(ttl)));
        return raw;
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session expired, sign in again");
    }
}
```

- [ ] **Step 5: Response shape and endpoints**

Replace `identity/AuthResponse.java`:

```java
package com.agentstore.platform.identity;

/** {@code refreshToken} is present only when the person asked to stay signed in, and on refresh. */
public record AuthResponse(String token, String refreshToken) {
    public static AuthResponse access(String token) {
        return new AuthResponse(token, null);
    }
}
```

Replace every `new AuthResponse(x)` in `AuthController`, `MeController` and `InvitationController` with `AuthResponse.access(x)`.

In `AuthController` add the dependency `RefreshTokenService refreshTokenService` (constructor + field). In `login`, replace the final `return` with:

```java
        String access = identityService.accessTokenFor(membership);
        String refresh = request.remember() ? refreshTokenService.issue(user.getId()) : null;
        return ResponseEntity.ok(new AuthResponse(access, refresh));
```

Add:

```java
    public record RefreshRequest(@jakarta.validation.constraints.NotBlank String refreshToken) {}

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        var rotation = refreshTokenService.rotate(request.refreshToken());
        User user = userRepository.findById(rotation.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session expired"));
        OrgMembership membership = identityService.landingMembership(user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You are not a member of any workspace"));
        return new AuthResponse(identityService.accessTokenFor(membership), rotation.newRawToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        refreshTokenService.revokeFamilyOf(request.refreshToken());
        return ResponseEntity.noContent().build();
    }
```

In `application.yml` under `agentstore.identity` add `refresh-token-ttl: P30D`.

Two browser tabs refreshing with the same token at the same moment look like reuse, and both get signed out. The Next.js proxy must serialize refreshes per session. That is recorded for the frontend plan; the backend behaviour is intentional.

- [ ] **Step 6: Run the tests**

Run: `mvn -q test -Dtest='RefreshTokenTest,AuthControllerTest,MeControllerTest,InvitationFlowTest,EndToEnd*Test'`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat(identity): keep me signed in with rotating refresh tokens and reuse detection"
```

---

### Task 5: Verify email and reset a forgotten password

**Files:**
- Create: `backend/src/main/resources/db/migration/V20__user_token.sql`
- Create: `identity/UserTokenPurpose.java`, `identity/UserToken.java`, `identity/UserTokenRepository.java`, `identity/UserTokenService.java`
- Modify: `identity/AuthController.java`
- Test: create `identity/EmailAndPasswordTest.java`

**Interfaces:**
- Consumes: `TokenHasher`, `EmailSender` (Task 3), `RefreshTokenService.revokeAllFor` (Task 4), `IdentityService.hashPassword` (Task 1).
- Produces:
  - `enum UserTokenPurpose { EMAIL_VERIFY, PASSWORD_RESET }`.
  - `UserTokenService.sendVerification(User)`, `verifyEmail(String raw)`, `sendPasswordReset(String email)` (silent for unknown emails), `resetPassword(String raw, String newPassword)`.
  - Endpoints: `POST /api/v1/auth/email/verify {token}` → 204; `POST /api/v1/auth/email/resend` (authenticated) → 202; `POST /api/v1/auth/password/forgot {email}` → 202; `POST /api/v1/auth/password/reset {token, password}` → 204. Invalid, used or expired tokens → 400.
  - Signup now sends a verification email.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/agentstore/platform/identity/EmailAndPasswordTest.java`:

```java
package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.common.RecordingEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EmailAndPasswordTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    RecordingEmailSender emails;
    @Autowired
    UserRepository userRepository;
    @Autowired
    IdentityFixtures identity;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    String email() {
        return "pw-" + UUID.randomUUID() + "@acme.example";
    }

    @Test
    void signupSendsAVerificationLinkThatVerifiesTheEmailOnce() {
        String email = email();
        rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("Priya", "Org", email, "s3cret-pw", SignupSide.BUYER), AuthResponse.class);
        String token = RecordingEmailSender.tokenFromLink(emails.lastTo(email).orElseThrow(), "verify-email");

        var first = rest.postForEntity(url("/api/v1/auth/email/verify"), Map.of("token", token), Void.class);
        var second = rest.postForEntity(url("/api/v1/auth/email/verify"), Map.of("token", token), String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(userRepository.findByEmailIgnoreCase(email).orElseThrow().getEmailVerifiedAt()).isNotNull();
    }

    @Test
    void forgotPasswordForAnUnknownEmailLooksTheSameAndSendsNothing() {
        String nobody = email();

        var response = rest.postForEntity(url("/api/v1/auth/password/forgot"), Map.of("email", nobody), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(emails.lastTo(nobody)).isEmpty();
    }

    @Test
    void resetSetsANewPasswordAndEndsRememberedSessions() {
        String email = identity.buyerAdmin(email()).getUser().getEmail();
        String refresh = rest.postForEntity(url("/api/v1/auth/login"), new LoginRequest(email, "s3cret-pw", true),
                AuthResponse.class).getBody().refreshToken();
        rest.postForEntity(url("/api/v1/auth/password/forgot"), Map.of("email", email.toUpperCase()), Void.class);
        String token = RecordingEmailSender.tokenFromLink(emails.lastTo(email).orElseThrow(), "reset-password");

        var reset = rest.postForEntity(url("/api/v1/auth/password/reset"),
                Map.of("token", token, "password", "new-s3cret-pw"), Void.class);

        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.postForEntity(url("/api/v1/auth/login"), new LoginRequest(email, "new-s3cret-pw", false),
                AuthResponse.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.postForEntity(url("/api/v1/auth/refresh"), Map.of("refreshToken", refresh), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anOlderResetLinkStopsWorkingWhenANewOneIsRequested() {
        String email = identity.buyerAdmin(email()).getUser().getEmail();
        rest.postForEntity(url("/api/v1/auth/password/forgot"), Map.of("email", email), Void.class);
        String older = RecordingEmailSender.tokenFromLink(emails.lastTo(email).orElseThrow(), "reset-password");
        rest.postForEntity(url("/api/v1/auth/password/forgot"), Map.of("email", email), Void.class);

        var response = rest.postForEntity(url("/api/v1/auth/password/reset"),
                Map.of("token", older, "password", "new-s3cret-pw"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aResetPasswordMustBeAtLeastEightCharacters() {
        String email = identity.buyerAdmin(email()).getUser().getEmail();
        rest.postForEntity(url("/api/v1/auth/password/forgot"), Map.of("email", email), Void.class);
        String token = RecordingEmailSender.tokenFromLink(emails.lastTo(email).orElseThrow(), "reset-password");

        var response = rest.postForEntity(url("/api/v1/auth/password/reset"),
                Map.of("token", token, "password", "short"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `mvn -q test -Dtest=EmailAndPasswordTest`
Expected: FAIL (404 on `/api/v1/auth/email/verify`, and no verification email recorded).

- [ ] **Step 3: Migration, entity, repository**

`backend/src/main/resources/db/migration/V20__user_token.sql`:

```sql
CREATE TABLE user_token (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES app_user(id),
    purpose     VARCHAR(20) NOT NULL,
    token_hash  CHAR(64)    NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX user_token_user_purpose_idx ON user_token (user_id, purpose);
```

`identity/UserTokenPurpose.java`:

```java
package com.agentstore.platform.identity;

public enum UserTokenPurpose { EMAIL_VERIFY, PASSWORD_RESET }
```

`identity/UserToken.java`:

```java
package com.agentstore.platform.identity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_token")
@Getter
@Setter
@NoArgsConstructor
public class UserToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID userId;

    @Enumerated(EnumType.STRING)
    private UserTokenPurpose purpose;

    private String tokenHash;
    private Instant expiresAt;
    private Instant usedAt;
    private Instant createdAt;

    public UserToken(UUID userId, UserTokenPurpose purpose, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.purpose = purpose;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`identity/UserTokenRepository.java`:

```java
package com.agentstore.platform.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserTokenRepository extends JpaRepository<UserToken, UUID> {
    Optional<UserToken> findByTokenHashAndPurpose(String tokenHash, UserTokenPurpose purpose);

    /** Retires earlier unused tokens so only the newest emailed link works. */
    @Modifying
    @Query("update UserToken t set t.usedAt = :now "
            + "where t.userId = :userId and t.purpose = :purpose and t.usedAt is null")
    int retireUnused(@Param("userId") UUID userId, @Param("purpose") UserTokenPurpose purpose,
                     @Param("now") Instant now);
}
```

- [ ] **Step 4: Service**

`identity/UserTokenService.java`:

```java
package com.agentstore.platform.identity;

import com.agentstore.platform.common.EmailMessage;
import com.agentstore.platform.common.EmailSender;
import com.agentstore.platform.common.TokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;

@Service
public class UserTokenService {

    static final Duration VERIFY_TTL = Duration.ofHours(24);
    static final Duration RESET_TTL = Duration.ofHours(1);

    private final UserTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final IdentityService identityService;
    private final EmailSender emailSender;
    private final String webBaseUrl;

    public UserTokenService(UserTokenRepository tokenRepository, UserRepository userRepository,
                            RefreshTokenService refreshTokenService, IdentityService identityService,
                            EmailSender emailSender, @Value("${agentstore.web.base-url}") String webBaseUrl) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
        this.identityService = identityService;
        this.emailSender = emailSender;
        this.webBaseUrl = webBaseUrl;
    }

    @Transactional
    public void sendVerification(User user) {
        if (user.getEmailVerifiedAt() != null) return;
        String raw = issue(user, UserTokenPurpose.EMAIL_VERIFY, VERIFY_TTL);
        emailSender.send(new EmailMessage(user.getEmail(), "Confirm your email for Karya",
                "Confirm this address to finish setting up Karya: " + webBaseUrl + "/verify-email/" + raw
                        + "\n\nThe link expires in 24 hours."));
    }

    @Transactional
    public void verifyEmail(String raw) {
        UserToken token = consume(raw, UserTokenPurpose.EMAIL_VERIFY);
        User user = userRepository.findById(token.getUserId()).orElseThrow(UserTokenService::invalid);
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(Instant.now());
            userRepository.save(user);
        }
    }

    /** Always silent: the caller cannot tell whether the email has an account. */
    @Transactional
    public void sendPasswordReset(String email) {
        userRepository.findByEmailIgnoreCase(IdentityService.normalizeEmail(email)).ifPresent(user -> {
            String raw = issue(user, UserTokenPurpose.PASSWORD_RESET, RESET_TTL);
            emailSender.send(new EmailMessage(user.getEmail(), "Reset your Karya password",
                    "Someone asked to reset the password for this Karya account. If it was you: "
                            + webBaseUrl + "/reset-password/" + raw
                            + "\n\nThe link works once and expires in 1 hour. If it wasn't you, ignore this email."));
        });
    }

    @Transactional
    public void resetPassword(String raw, String newPassword) {
        UserToken token = consume(raw, UserTokenPurpose.PASSWORD_RESET);
        User user = userRepository.findById(token.getUserId()).orElseThrow(UserTokenService::invalid);
        user.setPasswordHash(identityService.hashPassword(newPassword));
        // Receiving the reset link proves the inbox, so the address counts as verified too.
        if (user.getEmailVerifiedAt() == null) user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);
        refreshTokenService.revokeAllFor(user.getId());
    }

    private String issue(User user, UserTokenPurpose purpose, Duration ttl) {
        Instant now = Instant.now();
        tokenRepository.retireUnused(user.getId(), purpose, now);
        String raw = TokenHasher.newToken();
        tokenRepository.save(new UserToken(user.getId(), purpose, TokenHasher.sha256Hex(raw), now.plus(ttl)));
        return raw;
    }

    private UserToken consume(String raw, UserTokenPurpose purpose) {
        Instant now = Instant.now();
        UserToken token = tokenRepository.findByTokenHashAndPurpose(TokenHasher.sha256Hex(raw), purpose)
                .filter(t -> t.getUsedAt() == null && t.getExpiresAt().isAfter(now))
                .orElseThrow(UserTokenService::invalid);
        token.setUsedAt(now);
        tokenRepository.save(token);
        return token;
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "This link is invalid or has expired");
    }
}
```

- [ ] **Step 5: Endpoints and the signup email**

In `AuthController` add the dependency `UserTokenService userTokenService` (constructor + field). Change `signup` to send the email after creating the account:

```java
    @PostMapping("/signup")
    public AuthResponse signup(@Valid @RequestBody SignupRequest request) {
        OrgMembership membership = identityService.signup(request);
        userTokenService.sendVerification(membership.getUser());
        return AuthResponse.access(identityService.accessTokenFor(membership));
    }
```

Add (imports `jakarta.validation.constraints.Email`, `jakarta.validation.constraints.Size`):

```java
    public record TokenRequest(@jakarta.validation.constraints.NotBlank String token) {}

    public record ForgotPasswordRequest(@jakarta.validation.constraints.NotBlank @Email String email) {}

    public record ResetPasswordRequest(@jakarta.validation.constraints.NotBlank String token,
                                       @jakarta.validation.constraints.NotBlank @Size(min = 8, max = 200) String password) {}

    @PostMapping("/email/verify")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody TokenRequest request) {
        userTokenService.verifyEmail(request.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/email/resend")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> resendVerification() {
        userRepository.findById(MeController.currentClaims().userId()).ifPresent(userTokenService::sendVerification);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password/forgot")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        userTokenService.sendPasswordReset(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password/reset")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        userTokenService.resetPassword(request.token(), request.password());
        return ResponseEntity.noContent().build();
    }
```

`/password/forgot` has no rate limit yet. WS12 adds per-IP limits (bucket4j) to `/api/v1/auth/**`.

- [ ] **Step 6: Run the tests**

Run: `mvn -q test -Dtest='EmailAndPasswordTest,AuthControllerTest,RefreshTokenTest'`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat(identity): email verification and forgot-password with single-use, hashed links"
```

---

### Task 6: Continue with Google

**Files:**
- Modify: `backend/pom.xml` (add `spring-security-oauth2-jose`), `application.yml`
- Create: `backend/src/main/resources/db/migration/V21__user_identity.sql`
- Create: `identity/GoogleIdentity.java`, `identity/GoogleIdTokenVerifier.java`, `identity/NimbusGoogleIdTokenVerifier.java`, `identity/UserIdentity.java`, `identity/UserIdentityRepository.java`, `identity/GoogleSignInService.java`
- Modify: `identity/AuthController.java`
- Test: create `identity/FakeGoogleIdTokenVerifier.java`, `identity/GoogleSignInTest.java`

**Interfaces:**
- Consumes: `IdentityService.addOrg/landingMembership/accessTokenFor`, `RefreshTokenService.issue` (Tasks 1, 4).
- Produces:
  - `record GoogleIdentity(String subject, String email, boolean emailVerified, String name)`.
  - `GoogleIdTokenVerifier.verify(String idToken): Optional<GoogleIdentity>`.
  - `POST /api/v1/auth/google` `{idToken, side?, orgName?, remember}` → `AuthResponse`. 401 bad token or unverified Google email. 409 `ACCOUNT_EXISTS_UNVERIFIED` when a password account with that email has not verified its email. 422 `SIGNUP_DETAILS_REQUIRED` for a new person who has not picked a side and org name yet. 503 when `agentstore.google.client-id` is not set.

This follows the spec's intent (WS13) with one change: instead of a server-side redirect flow (`/auth/google/start` + `/callback`), the web app gets an ID token from Google Identity Services and posts it here. That keeps the backend stateless with no session or state cookie. Task 8 records this in the spec.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/agentstore/platform/identity/FakeGoogleIdTokenVerifier.java`:

```java
package com.agentstore.platform.identity;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Tests register a fake ID token string and the identity Google would vouch for. */
@Component
@Primary
public class FakeGoogleIdTokenVerifier implements GoogleIdTokenVerifier {

    private final Map<String, GoogleIdentity> tokens = new ConcurrentHashMap<>();

    public void register(String idToken, GoogleIdentity identity) {
        tokens.put(idToken, identity);
    }

    @Override
    public Optional<GoogleIdentity> verify(String idToken) {
        return Optional.ofNullable(tokens.get(idToken));
    }
}
```

`backend/src/test/java/com/agentstore/platform/identity/GoogleSignInTest.java`:

```java
package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleSignInTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    FakeGoogleIdTokenVerifier google;
    @Autowired
    JwtService jwtService;
    @Autowired
    IdentityFixtures identity;
    @Autowired
    UserRepository userRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    String registered(String email, boolean verified) {
        String idToken = "id-" + UUID.randomUUID();
        google.register(idToken, new GoogleIdentity("sub-" + UUID.randomUUID(), email, verified, "Priya Shah"));
        return idToken;
    }

    Map<String, Object> body(String idToken, String side, String orgName) {
        Map<String, Object> body = new HashMap<>();
        body.put("idToken", idToken);
        if (side != null) body.put("side", side);
        if (orgName != null) body.put("orgName", orgName);
        return body;
    }

    String email() {
        return "google-" + UUID.randomUUID() + "@acme.example";
    }

    @Test
    void aNewPersonMustPickASideThenGetsAnAccountWithAVerifiedEmail() {
        String email = email();
        String idToken = registered(email, true);

        var first = rest.postForEntity(url("/api/v1/auth/google"), body(idToken, null, null), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(first.getBody().get("code")).isEqualTo("SIGNUP_DETAILS_REQUIRED");

        var second = rest.postForEntity(url("/api/v1/auth/google"), body(idToken, "BUYER", "Meridian"),
                AuthResponse.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jwtService.parse(second.getBody().token()).orElseThrow().role()).isEqualTo(UserRole.BUYER_ADMIN);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.getEmailVerifiedAt()).isNotNull();
        assertThat(user.getFullName()).isEqualTo("Priya Shah");
    }

    @Test
    void theSameGoogleAccountSignsBackIntoTheSameUser() {
        String idToken = registered(email(), true);
        var created = rest.postForEntity(url("/api/v1/auth/google"), body(idToken, "BUYER", "Org"), AuthResponse.class);

        var again = rest.postForEntity(url("/api/v1/auth/google"), body(idToken, null, null), AuthResponse.class);

        assertThat(jwtService.parse(again.getBody().token()).orElseThrow().userId())
                .isEqualTo(jwtService.parse(created.getBody().token()).orElseThrow().userId());
    }

    @Test
    void aVerifiedPasswordAccountIsLinkedByEmail() {
        OrgMembership existing = identity.buyerAdmin(email());
        User user = existing.getUser();
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);

        var response = rest.postForEntity(url("/api/v1/auth/google"),
                body(registered(user.getEmail(), true), null, null), AuthResponse.class);

        assertThat(jwtService.parse(response.getBody().token()).orElseThrow().userId()).isEqualTo(user.getId());
    }

    @Test
    void anUnverifiedPasswordAccountIsNeverTakenOverByGoogle() {
        OrgMembership existing = identity.buyerAdmin(email());   // emailVerifiedAt is null

        var response = rest.postForEntity(url("/api/v1/auth/google"),
                body(registered(existing.getUser().getEmail(), true), "BUYER", "Other"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("ACCOUNT_EXISTS_UNVERIFIED");
    }

    @Test
    void googleAccountsWithUnverifiedEmailsAreRejected() {
        var response = rest.postForEntity(url("/api/v1/auth/google"),
                body(registered(email(), false), "BUYER", "Org"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anInvalidIdTokenIsRejected() {
        var response = rest.postForEntity(url("/api/v1/auth/google"), body("forged", "BUYER", "Org"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `mvn -q test -Dtest=GoogleSignInTest`
Expected: compilation failure (`GoogleIdTokenVerifier` not found).

- [ ] **Step 3: Dependency, migration, config**

In `backend/pom.xml` add:

```xml
    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-oauth2-jose</artifactId>
    </dependency>
```

`backend/src/main/resources/db/migration/V21__user_identity.sql`:

```sql
CREATE TABLE user_identity (
    provider   VARCHAR(20)  NOT NULL,
    subject    VARCHAR(255) NOT NULL,
    user_id    UUID         NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, subject)
);
CREATE INDEX user_identity_user_idx ON user_identity (user_id);
```

In `application.yml` under `agentstore:` add:

```yaml
  google:
    client-id: ${GOOGLE_CLIENT_ID:}
```

- [ ] **Step 4: Verifier, identity link, service**

`identity/GoogleIdentity.java`:

```java
package com.agentstore.platform.identity;

public record GoogleIdentity(String subject, String email, boolean emailVerified, String name) {
}
```

`identity/GoogleIdTokenVerifier.java`:

```java
package com.agentstore.platform.identity;

import java.util.Optional;

/** Checks a Google ID token's signature, issuer, audience and expiry. Empty means "do not trust it". */
public interface GoogleIdTokenVerifier {
    Optional<GoogleIdentity> verify(String idToken);
}
```

`identity/NimbusGoogleIdTokenVerifier.java`:

```java
package com.agentstore.platform.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

@Component
public class NimbusGoogleIdTokenVerifier implements GoogleIdTokenVerifier {

    private static final String JWKS = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private final String clientId;
    private final JwtDecoder decoder;

    public NimbusGoogleIdTokenVerifier(@Value("${agentstore.google.client-id:}") String clientId) {
        this.clientId = clientId;
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(JWKS).build();
        OAuth2TokenValidator<Jwt> issuer = jwt -> ISSUERS.contains(String.valueOf(jwt.getClaims().get("iss")))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong issuer", null));
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(clientId)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong audience", null));
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), issuer, audience));
        this.decoder = nimbus;
    }

    @Override
    public Optional<GoogleIdentity> verify(String idToken) {
        if (clientId.isBlank()) {
            return Optional.empty();
        }
        try {
            Jwt jwt = decoder.decode(idToken);
            Object verified = jwt.getClaims().get("email_verified");
            return Optional.of(new GoogleIdentity(jwt.getSubject(), jwt.getClaimAsString("email"),
                    Boolean.TRUE.equals(verified) || "true".equals(String.valueOf(verified)),
                    jwt.getClaimAsString("name")));
        } catch (JwtException e) {
            return Optional.empty();
        }
    }

    public boolean isConfigured() {
        return !clientId.isBlank();
    }
}
```

`identity/UserIdentity.java`:

```java
package com.agentstore.platform.identity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_identity")
@IdClass(UserIdentity.Key.class)
@Getter
@NoArgsConstructor
public class UserIdentity {

    @Id
    private String provider;

    @Id
    private String subject;

    private UUID userId;
    private Instant createdAt;

    public UserIdentity(String provider, String subject, UUID userId) {
        this.provider = provider;
        this.subject = subject;
        this.userId = userId;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private String provider;
        private String subject;
    }
}
```

`identity/UserIdentityRepository.java`:

```java
package com.agentstore.platform.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UserIdentity.Key> {
    Optional<UserIdentity> findByProviderAndSubject(String provider, String subject);
}
```

`identity/GoogleSignInService.java`:

```java
package com.agentstore.platform.identity;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Service
public class GoogleSignInService {

    static final String PROVIDER = "GOOGLE";

    private final GoogleIdTokenVerifier verifier;
    private final UserRepository userRepository;
    private final UserIdentityRepository identityRepository;
    private final IdentityService identityService;

    public GoogleSignInService(GoogleIdTokenVerifier verifier, UserRepository userRepository,
                               UserIdentityRepository identityRepository, IdentityService identityService) {
        this.verifier = verifier;
        this.userRepository = userRepository;
        this.identityRepository = identityRepository;
        this.identityService = identityService;
    }

    /** Raised as 409/422 with a stable {@code code} the web app can act on. */
    public static class SignInProblem extends RuntimeException {
        public final HttpStatus status;
        public final String code;

        SignInProblem(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    @Transactional
    public OrgMembership signIn(String idToken, SignupSide side, String orgName) {
        GoogleIdentity google = verifier.verify(idToken)
                .filter(GoogleIdentity::emailVerified)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google sign-in failed"));

        User user = identityRepository.findByProviderAndSubject(PROVIDER, google.subject())
                .flatMap(link -> userRepository.findById(link.getUserId()))
                .orElse(null);

        if (user == null) {
            String email = IdentityService.normalizeEmail(google.email());
            User existing = userRepository.findByEmailIgnoreCase(email).orElse(null);
            if (existing != null) {
                if (existing.getEmailVerifiedAt() == null) {
                    throw new SignInProblem(HttpStatus.CONFLICT, "ACCOUNT_EXISTS_UNVERIFIED",
                            "Sign in with your password and confirm your email first");
                }
                user = existing;
            } else {
                if (side == null || orgName == null || orgName.isBlank()) {
                    throw new SignInProblem(HttpStatus.UNPROCESSABLE_ENTITY, "SIGNUP_DETAILS_REQUIRED",
                            "Pick what you're here to do and name your workspace");
                }
                User created = new User(null, email, null, google.name() != null ? google.name() : email);
                created.setEmailVerifiedAt(Instant.now());
                user = userRepository.save(created);
                identityRepository.save(new UserIdentity(PROVIDER, google.subject(), user.getId()));
                return identityService.addOrg(user, side, orgName);
            }
            identityRepository.save(new UserIdentity(PROVIDER, google.subject(), user.getId()));
        }

        final User signedIn = user;
        return identityService.landingMembership(signedIn)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You are not a member of any workspace"));
    }
}
```

- [ ] **Step 5: Endpoint**

In `AuthController` add dependencies `GoogleSignInService googleSignInService` and `GoogleIdTokenVerifier googleVerifier` (constructor + fields), and (imports `java.util.Map`):

```java
    public record GoogleSignInRequest(@jakarta.validation.constraints.NotBlank String idToken, SignupSide side,
                                      String orgName, boolean remember) {}

    @PostMapping("/google")
    public ResponseEntity<?> google(@Valid @RequestBody GoogleSignInRequest request) {
        if (googleVerifier instanceof NimbusGoogleIdTokenVerifier nimbus && !nimbus.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        try {
            OrgMembership membership = googleSignInService.signIn(request.idToken(), request.side(), request.orgName());
            String refresh = request.remember() ? refreshTokenService.issue(membership.getUser().getId()) : null;
            return ResponseEntity.ok(new AuthResponse(identityService.accessTokenFor(membership), refresh));
        } catch (GoogleSignInService.SignInProblem problem) {
            return ResponseEntity.status(problem.status)
                    .body(Map.of("code", problem.code, "message", problem.getMessage()));
        }
    }
```

`SignInProblem` is thrown from inside the `@Transactional` service method, which rolls that transaction back before the controller maps it, so nothing half-created remains.

- [ ] **Step 6: Run the tests**

Run: `mvn -q test -Dtest='GoogleSignInTest,AuthControllerTest,PlatformApplicationTest'`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/pom.xml backend/src
git commit -m "feat(identity): continue with Google via verified ID tokens, never taking over unverified accounts"
```

---

### Task 7: Frontend signup form and seed script

**Files:**
- Modify: `frontend/src/app/(auth)/signup/page.tsx`, `frontend/src/app/(auth)/signup/actions.ts`, `frontend/e2e/seed.ts`

**Interfaces:**
- Consumes: `POST /api/v1/auth/signup` `{fullName, orgName, email, password, side}` (Task 1), `org_membership` table (Task 1).
- Produces: a signup form that asks for full name and side ("I run finance" / "I build agents"). The seed puts the approver into the buyer workspace by moving their membership.

This task keeps the current app working against the new API. The full redesigned sign-in screen (Google button, "Keep me signed in", forgot password) belongs to the frontend plan for the new UI.

- [ ] **Step 1: Update the signup action**

In `frontend/src/app/(auth)/signup/actions.ts`, replace the three `const … = String(formData.get(…))` lines and the `fetch` body with:

```ts
  const fullName = String(formData.get("fullName") ?? "");
  const orgName = String(formData.get("orgName") ?? "");
  const email = String(formData.get("email") ?? "");
  const password = String(formData.get("password") ?? "");
  const side = formData.get("side") === "DEVELOPER" ? "DEVELOPER" : "BUYER";

  const res = await fetch(`${process.env.BACKEND_URL}/api/v1/auth/signup`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fullName, orgName, email, password, side }),
  });
  if (res.status === 409) return { error: "An account with this email already exists — sign in instead" };
```

(keep the existing `if (!res.ok) return { error: … }` line after it).

- [ ] **Step 2: Update the signup form**

In `frontend/src/app/(auth)/signup/page.tsx`, change the heading to `Create your account` and replace the `orgName` input line with:

```tsx
        <fieldset className="grid grid-cols-2 gap-2">
          <legend className="text-sm text-ink2 mb-2">Pick what you&apos;re here to do. You can add the other later.</legend>
          <label className="border border-line rounded-lg px-3 py-2 text-sm">
            <input type="radio" name="side" value="BUYER" defaultChecked className="mr-2" />I run finance
          </label>
          <label className="border border-line rounded-lg px-3 py-2 text-sm">
            <input type="radio" name="side" value="DEVELOPER" className="mr-2" />I build agents
          </label>
        </fieldset>
        <input name="fullName" required placeholder="Full name" className="border border-line rounded-lg px-3 py-2 w-full" />
        <input name="orgName" required placeholder="Company, or your name or studio" className="border border-line rounded-lg px-3 py-2 w-full" />
```

- [ ] **Step 3: Update the seed script**

In `frontend/e2e/seed.ts`, change the `signup` helper signature and body:

```ts
async function signup(fullName: string, orgName: string, side: "BUYER" | "DEVELOPER", email: string, password: string) {
  const res = await fetch(`${BACKEND_URL}/api/v1/auth/signup`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fullName, orgName, side, email, password }),
  });
```

(the error handling below stays; also treat `res.status === 409` as the duplicate-email case in its first `if`). Update the three calls:

```ts
  const dev = await signup("E2E Developer", "E2E Dev Co", "DEVELOPER", "e2e-dev@example.com", "e2e-password");
  await signup("E2E Buyer Admin", "E2E Buyer Co", "BUYER", "e2e-buyer@example.com", "e2e-password");
  await signup("E2E Approver", "E2E Buyer Approver Org (temp)", "BUYER", "e2e-approver@example.com", "e2e-password");
```

and replace the SQL that moved the approver (`update app_user set org_id = …` and `delete from org …`) with:

```ts
  // Memberships, not app_user.org_id, decide which workspace a person belongs to (WS13).
  await pg.query(
    `update org_membership set org_id = $1, role = 'BUYER_APPROVER', title = 'Accounts manager'
       where org_id = $2 and user_id = (select id from app_user where email = $3)`,
    [buyerOrgId, approverOrgId, "e2e-approver@example.com"],
  );
  await pg.query(`update app_user set last_org_id = $1 where email = $2`, [buyerOrgId, "e2e-approver@example.com"]);
  await pg.query(`delete from org where id = $1`, [approverOrgId]);
```

Update the comment above the approver signup to say the seed moves the approver's *membership* into the buyer org.

- [ ] **Step 4: Type-check and unit tests**

Run (from `frontend/`): `npx tsc --noEmit && npm test`
Expected: all PASS.

- [ ] **Step 5: Seed and E2E against a fresh stack**

With Postgres and the backend running on a fresh database (see `frontend/README.md`), run: `npx tsx e2e/seed.ts && npx playwright test`
Expected: "Seed complete." and the core-loop E2E passes, signing in as `e2e-approver@example.com`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/\(auth\)/signup frontend/e2e/seed.ts
git commit -m "feat(frontend): signup asks for full name and side; seed uses memberships"
```

---

### Task 8: Record the plan's decisions in the spec and verify everything

**Files:**
- Modify: `docs/superpowers/specs/2026-09-23-agent-store-developer-platform-and-sandbox-design.md` (WS13 section)

- [ ] **Step 1: Update WS13 in the spec**

In §5 WS13 of the spec:
- In the `org_membership` DDL comment, change the role list to `-- BUYER_ADMIN, BUYER_APPROVER, DEVELOPER, ADMIN (ADMIN only via agentstore.identity.admin-emails)`.
- Replace the two Google bullets `GET /auth/google/start`, `GET /auth/google/callback …` with: `POST /auth/google {idToken, side?, orgName?, remember}`: the web app gets the ID token from Google Identity Services; the backend checks signature, issuer, audience and `email_verified`. 409 `ACCOUNT_EXISTS_UNVERIFIED`, 422 `SIGNUP_DETAILS_REQUIRED`.
- Under the data model, add a line: `org.gstin`, `org.billing_address` are added by WS10 (first reader); `org.sso_required` arrives with workspace SSO in M3.
- Add under APIs: *Access tokens stay 60 minutes until the new frontend refreshes them; member removal takes effect at the next token, and gate decisions re-check membership in the database (WS15).*

- [ ] **Step 2: Full backend suite**

Run (from `backend/`): `mvn -q test`
Expected: all PASS.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-09-23-agent-store-developer-platform-and-sandbox-design.md
git commit -m "docs(spec): record WS13 implementation decisions"
```
