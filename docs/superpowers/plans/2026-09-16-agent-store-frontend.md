# Agent Store Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Next.js/TypeScript frontend for the Agent Store MVP — buyer console (catalog, agent detail, connect flow, run console, billing), developer console (dashboard, submission wizard, review status, manage), and an admin review queue — wired against the real backend from `docs/superpowers/plans/2026-09-16-agent-store-backend-platform.md`, plus a small set of additive backend read endpoints this plan adds first.

**Architecture:** One Next.js 14+ App Router application, route groups by role (`(auth)`, `(buyer)`, `(developer)`, `(admin)`). All backend calls happen server-side only — Server Components, Server Actions, and two Route Handlers that proxy the two polling consumers — never directly from browser JS, so the backend needs no CORS config. JWT lives in an httpOnly cookie. Manifest data flows through one shared `PermissionStatementDoc` component rendered in three modes.

**Tech Stack:** Next.js 14+ (App Router), TypeScript, Tailwind CSS (theme tokens ported from the mockups), TanStack Query (polling only), Vitest + Testing Library (component/unit tests), Playwright (one E2E core-loop test).

**Spec:** `docs/superpowers/specs/2026-09-16-agent-store-frontend-design.md` (approved design this plan implements — read both; this plan argues from the spec). Visual reference: `fe-mockups/karya_agent_store_mockup.html`, `fe-mockups/karya_connect_flow.html`, `fe-mockups/karya_developer_console.html`.

## Global Constraints

- Backend parity only — no eval upload, no red-team/staged-rollout stages, no Tally connector or connection test step, no wallet top-up/GST line items, no developer payouts (spec §1).
- Phase 0 assumes the 26-task backend plan is already fully implemented. Every Phase 0 task only *adds* new controller endpoints/DTOs over entities and repositories that plan already created — it never modifies existing endpoints' behavior, except where a task explicitly says "modify."
- All backend HTTP calls happen server-side (Server Components, Server Actions, Route Handlers under `app/api/**`) via `lib/backend.ts`, which reads the JWT from the httpOnly cookie via `next/headers`. Client Components never call the Spring backend directly — the two exceptions (run polling, approvals polling) call same-origin Next.js Route Handlers, which then call the backend server-side.
- JWT cookie name is `karya_session`, httpOnly, `sameSite: "lax"`, path `/`. Never stored in localStorage or read by client JS.
- The JWT's `sub` claim carries `userId` (not a claim literally named `userId` — verified against the backend's actual `JwtService.issue`, which calls `.subject(userId.toString())`), plus custom claims `orgId` and `role`.
- The frontend's `Manifest` TypeScript type mirrors the backend's parsed `Manifest` Java record exactly (Task 6 of the backend plan): `{name, version, scopes: Scope[], networkAllowlist: string[], costEnvelope: CostEnvelope, approvalGates: ApprovalGateDecl[], dataClasses: string[]}`. This is distinct from the raw manifest JSON wire format submitted by developers (`ManifestDraft`, with nested `network: {allowlist}` and an extra `modelRequirements` object — see Task 21), which the backend's `ManifestParser` flattens into the `Manifest` shape above on read.
- Server Component pages that are pure data-fetch-and-render (no branching/derived logic) delegate rendering to a co-located sync presentational component (e.g. `page.tsx` fetches, `CatalogList.tsx` renders) so the presentational piece stays unit-testable; the thin `page.tsx` wrapper itself is verified by the Phase 6 E2E test and a manual dev-server check, not a dedicated unit test.
- Money values are IN currency `number`s on the wire (backend's `BigDecimal` fields serialize as JSON numbers via Jackson) — formatted for display with `lib/format.ts`'s `formatInr`, never manually.
- No dark-mode-specific component logic — the mockups' light/dark CSS custom properties are ported once into `globals.css` via `prefers-color-scheme` and Tailwind references them as color tokens; no task re-implements this per-component.

---

## Phase 0 — Backend API additions

These six tasks live in `backend/` (the existing Spring Boot project from the backend plan) and must land before the frontend tasks that depend on them (noted per task). Each follows the backend plan's existing conventions exactly: constructor-injected `@RestController`s, `@PreAuthorize` role checks, `ResponseStatusException` for error cases, `SecurityContextHolder`-derived `JwtService.Claims` for ownership checks, and Java records with a static `from(...)` factory for response DTOs.

### Task 1: Unified agent version detail endpoint

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/catalog/AgentVersionDetailResponse.java`
- Modify: `backend/src/main/java/com/agentstore/platform/catalog/CatalogController.java` (add one `@GetMapping`)
- Test: `backend/src/test/java/com/agentstore/platform/catalog/AgentVersionDetailControllerTest.java`

**Interfaces:**
- Consumes: `AgentRepository`, `AgentVersionRepository` (Task 7 of the backend plan), `ManifestParser` (Task 6 of the backend plan).
- Produces: `GET /api/v1/agents/{agentId}/versions/{versionId}` (any authenticated user) → `AgentVersionDetailResponse{id, agentId, agentName, versionNumber, status, manifest: Manifest}`. Visible if the version's `status == LIVE`, OR the caller is `DEVELOPER` and owns the agent, OR the caller is `ADMIN`; otherwise `403`. `404` if the agent or version doesn't exist, or the version doesn't belong to the agent. This is the single manifest-detail endpoint both the buyer agent-detail page and the developer manage/wizard-review pages use — frontend Tasks 14, 15, 21, 23.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/catalog/AgentVersionDetailControllerTest.java`:

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
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentVersionDetailControllerTest extends AbstractIntegrationTest {

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

    HttpHeaders headers(UUID orgId, UserRole role) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, role));
        return h;
    }

    @Test
    void anyBuyerCanReadALiveVersion() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        AgentVersion version = new AgentVersion(null, agent, "1.0.0", manifest, "template-v1",
                VersionStatus.LIVE, Tier.TIER_A, "cl");
        version.setApprovedAt(Instant.now());
        version = agentVersionRepository.save(version);

        Org buyer = orgRepository.save(new Org(null, "BuyerCo", OrgType.BUYER));
        var response = rest.exchange(
                url("/api/v1/agents/" + agent.getId() + "/versions/" + version.getId()),
                HttpMethod.GET, new HttpEntity<>(headers(buyer.getId(), UserRole.BUYER_ADMIN)),
                AgentVersionDetailResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().manifest().name()).isEqualTo("Bank Reconciliation Agent");
        assertThat(response.getBody().status()).isEqualTo(VersionStatus.LIVE);
    }

    @Test
    void buyerCannotReadADraftVersion() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo2", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Unfinished", "x", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        AgentVersion draft = agentVersionRepository.save(new AgentVersion(null, agent, "0.1.0", manifest,
                "template-x", VersionStatus.DRAFT, null, "cl"));

        Org buyer = orgRepository.save(new Org(null, "BuyerCo2", OrgType.BUYER));
        var response = rest.exchange(
                url("/api/v1/agents/" + agent.getId() + "/versions/" + draft.getId()),
                HttpMethod.GET, new HttpEntity<>(headers(buyer.getId(), UserRole.BUYER_ADMIN)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void owningDeveloperCanReadTheirOwnDraft() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo3", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "In Progress", "x", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        AgentVersion draft = agentVersionRepository.save(new AgentVersion(null, agent, "0.1.0", manifest,
                "template-x", VersionStatus.DRAFT, null, "cl"));

        var response = rest.exchange(
                url("/api/v1/agents/" + agent.getId() + "/versions/" + draft.getId()),
                HttpMethod.GET, new HttpEntity<>(headers(developer.getId(), UserRole.DEVELOPER)),
                AgentVersionDetailResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=AgentVersionDetailControllerTest`
Expected: FAIL — `AgentVersionDetailResponse` and the endpoint do not exist.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/com/agentstore/platform/catalog/AgentVersionDetailResponse.java`:

```java
package com.agentstore.platform.catalog;

import com.agentstore.platform.manifest.Manifest;

import java.util.UUID;

public record AgentVersionDetailResponse(UUID id, UUID agentId, String agentName, String versionNumber,
                                          VersionStatus status, Manifest manifest) {
    static AgentVersionDetailResponse from(AgentVersion version, Manifest manifest) {
        return new AgentVersionDetailResponse(version.getId(), version.getAgent().getId(),
                version.getAgent().getName(), version.getVersionNumber(), version.getStatus(), manifest);
    }
}
```

Add to `backend/src/main/java/com/agentstore/platform/catalog/CatalogController.java` (inject `ManifestParser` in the constructor alongside existing dependencies, add the method):

```java
    private final com.agentstore.platform.manifest.ManifestParser manifestParser;
    // add to constructor: this.manifestParser = manifestParser;

    @GetMapping("/api/v1/agents/{agentId}/versions/{versionId}")
    public AgentVersionDetailResponse detail(@PathVariable java.util.UUID agentId, @PathVariable java.util.UUID versionId) {
        AgentVersion version = agentVersionRepository.findById(versionId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Version not found"));
        if (!version.getAgent().getId().equals(agentId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Version not found for this agent");
        }

        var claims = (com.agentstore.platform.identity.JwtService.Claims)
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        boolean isLive = version.getStatus() == VersionStatus.LIVE;
        boolean isOwningDeveloper = claims.role() == com.agentstore.platform.identity.UserRole.DEVELOPER
                && version.getAgent().getDeveloperOrg().getId().equals(claims.orgId());
        boolean isAdmin = claims.role() == com.agentstore.platform.identity.UserRole.ADMIN;
        if (!isLive && !isOwningDeveloper && !isAdmin) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Version not visible to caller");
        }

        var manifest = manifestParser.parse(version.getManifestJson());
        return AgentVersionDetailResponse.from(version, manifest);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=AgentVersionDetailControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(catalog): add unified agent version detail endpoint with visibility rules"
```

---

### Task 2: Developer's own agents endpoint

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/catalog/MyAgentsResponse.java`
- Create: `backend/src/main/java/com/agentstore/platform/catalog/MyAgentsController.java`
- Modify: `backend/src/main/java/com/agentstore/platform/catalog/AgentRepository.java` (add `findByDeveloperOrgId`)
- Test: `backend/src/test/java/com/agentstore/platform/catalog/MyAgentsControllerTest.java`

**Interfaces:**
- Consumes: `AgentRepository`, `AgentVersionRepository` (Task 7 of the backend plan).
- Produces: `GET /api/v1/agents/mine` (role `DEVELOPER`) → `List<MyAgentsResponse>` where `MyAgentsResponse{agentId, agentName, category, versions: List<AgentVersionResponse>}`, scoped to the caller's org, versions of every status included (unlike the public catalog). Frontend Tasks 19, 23.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/catalog/MyAgentsControllerTest.java`:

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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MyAgentsControllerTest extends AbstractIntegrationTest {

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
    void listsOnlyTheCallersOwnAgentsAcrossAllStatuses() {
        Org mine = orgRepository.save(new Org(null, "MyDevCo", OrgType.DEVELOPER));
        Org other = orgRepository.save(new Org(null, "OtherDevCo", OrgType.DEVELOPER));
        Agent myAgent = agentRepository.save(new Agent(null, mine, "My Agent", "reconciliation", "d"));
        agentRepository.save(new Agent(null, other, "Other Agent", "x", "d"));

        agentVersionRepository.save(new AgentVersion(null, myAgent, "0.1.0", "{}", "t", VersionStatus.DRAFT, null, "cl"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), mine.getId(), UserRole.DEVELOPER));

        var response = rest.exchange(url("/api/v1/agents/mine"), HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<List<MyAgentsResponse>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).agentName()).isEqualTo("My Agent");
        assertThat(response.getBody().get(0).versions()).hasSize(1);
        assertThat(response.getBody().get(0).versions().get(0).status()).isEqualTo(VersionStatus.DRAFT);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=MyAgentsControllerTest`
Expected: FAIL — controller/DTO/repository method do not exist.

- [ ] **Step 3: Write minimal implementation**

Add to `backend/src/main/java/com/agentstore/platform/catalog/AgentRepository.java`:

```java
    java.util.List<Agent> findByDeveloperOrgId(java.util.UUID developerOrgId);
```

`backend/src/main/java/com/agentstore/platform/catalog/MyAgentsResponse.java`:

```java
package com.agentstore.platform.catalog;

import java.util.List;
import java.util.UUID;

public record MyAgentsResponse(UUID agentId, String agentName, String category, List<AgentVersionResponse> versions) {
}
```

`backend/src/main/java/com/agentstore/platform/catalog/MyAgentsController.java`:

```java
package com.agentstore.platform.catalog;

import com.agentstore.platform.identity.JwtService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class MyAgentsController {

    private final AgentRepository agentRepository;
    private final AgentVersionRepository agentVersionRepository;

    public MyAgentsController(AgentRepository agentRepository, AgentVersionRepository agentVersionRepository) {
        this.agentRepository = agentRepository;
        this.agentVersionRepository = agentVersionRepository;
    }

    @GetMapping("/api/v1/agents/mine")
    @PreAuthorize("hasRole('DEVELOPER')")
    public List<MyAgentsResponse> mine() {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return agentRepository.findByDeveloperOrgId(claims.orgId()).stream()
                .map(agent -> new MyAgentsResponse(agent.getId(), agent.getName(), agent.getCategory(),
                        versionsFor(agent.getId())))
                .toList();
    }

    private List<AgentVersionResponse> versionsFor(java.util.UUID agentId) {
        return java.util.Arrays.stream(VersionStatus.values())
                .flatMap(status -> agentVersionRepository.findByAgentIdAndStatus(agentId, status).stream())
                .map(AgentVersionResponse::from)
                .toList();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=MyAgentsControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(catalog): add developer's own agents+versions listing endpoint"
```

---

### Task 3: Buyer's own connections endpoint

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/execution/ConnectionSummaryResponse.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/ConnectionController.java` (add one `@GetMapping`)
- Modify: `backend/src/main/java/com/agentstore/platform/execution/ConnectionRepository.java` (add `findByBuyerOrgId`)
- Test: `backend/src/test/java/com/agentstore/platform/execution/ConnectionSummaryControllerTest.java`

**Interfaces:**
- Consumes: `ConnectionRepository`, `Connection` (Task 14 of the backend plan).
- Produces: `GET /api/v1/connections/mine` (role `BUYER_ADMIN` or `BUYER_APPROVER`) → `List<ConnectionSummaryResponse>` where `ConnectionSummaryResponse{id, agentId, agentName, grantedScopes, spendCap, status}`, scoped to the caller's org. Frontend Task 16 (to check for an existing connection before offering "Connect") and Task 17 (to resolve a connection before triggering a run).

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/execution/ConnectionSummaryControllerTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionSummaryControllerTest extends AbstractIntegrationTest {

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
    ConnectionRepository connectionRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void listsOnlyTheCallersOwnConnectionsWithAgentName() {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        Org myBuyer = orgRepository.save(new Org(null, "MyBuyerCo", OrgType.BUYER));
        Org otherBuyer = orgRepository.save(new Org(null, "OtherBuyerCo", OrgType.BUYER));

        connectionRepository.save(new Connection(null, myBuyer, agent, List.of("bank_statement"),
                new BigDecimal("500.00"), ConnectionStatus.ACTIVE));
        connectionRepository.save(new Connection(null, otherBuyer, agent, List.of("bank_statement"),
                new BigDecimal("500.00"), ConnectionStatus.ACTIVE));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), myBuyer.getId(), UserRole.BUYER_ADMIN));

        var response = rest.exchange(url("/api/v1/connections/mine"), HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<List<ConnectionSummaryResponse>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).agentName()).isEqualTo("Bank Reconciliation");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=ConnectionSummaryControllerTest`
Expected: FAIL — DTO/endpoint/repository method do not exist.

- [ ] **Step 3: Write minimal implementation**

Add to `backend/src/main/java/com/agentstore/platform/execution/ConnectionRepository.java`:

```java
    java.util.List<Connection> findByBuyerOrgId(java.util.UUID buyerOrgId);
```

`backend/src/main/java/com/agentstore/platform/execution/ConnectionSummaryResponse.java`:

```java
package com.agentstore.platform.execution;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ConnectionSummaryResponse(UUID id, UUID agentId, String agentName, List<String> grantedScopes,
                                         BigDecimal spendCap, ConnectionStatus status) {
    static ConnectionSummaryResponse from(Connection c) {
        return new ConnectionSummaryResponse(c.getId(), c.getAgent().getId(), c.getAgent().getName(),
                c.getGrantedScopes(), c.getSpendCap(), c.getStatus());
    }
}
```

Add to `backend/src/main/java/com/agentstore/platform/execution/ConnectionController.java` (append method, uses fields already injected in that class):

```java
    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public java.util.List<ConnectionSummaryResponse> mine() {
        var claims = (com.agentstore.platform.identity.JwtService.Claims)
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return connectionRepository.findByBuyerOrgId(claims.orgId()).stream()
                .map(ConnectionSummaryResponse::from)
                .toList();
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=ConnectionSummaryControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(execution): add buyer's own connections listing endpoint"
```

---

### Task 4: Buyer's own runs endpoint

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunSummaryResponse.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunDetailController.java` (add one `@GetMapping`)
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunRepository.java` (add `findByConnectionBuyerOrgIdOrderByStartedAtDesc` and the status-filtered variant)
- Test: `backend/src/test/java/com/agentstore/platform/execution/RunSummaryControllerTest.java`

**Interfaces:**
- Consumes: `RunRepository`, `Run` (Task 16 of the backend plan).
- Produces: `GET /api/v1/runs/mine` and `GET /api/v1/runs/mine?status=AWAITING_APPROVAL` (any authenticated buyer role) → `List<RunSummaryResponse>` where `RunSummaryResponse{id, agentName, status, totalCharged, startedAt}`, scoped to the caller's org, newest first. Frontend Task 17 (runs list + approvals nav badge polling).

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/execution/RunSummaryControllerTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunSummaryControllerTest extends AbstractIntegrationTest {

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
    void filtersToTheCallersOrgAndOptionalStatus() {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        AgentVersion version = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0", "{}", "t",
                VersionStatus.LIVE, Tier.TIER_A, "cl"));
        Org buyer = orgRepository.save(new Org(null, "BuyerCo", OrgType.BUYER));
        Connection connection = connectionRepository.save(new Connection(null, buyer, agent, List.of("bank_statement"),
                new BigDecimal("500.00"), ConnectionStatus.ACTIVE));

        runRepository.save(new Run(null, connection, version, RunStatus.RUNNING, new BigDecimal("500.00"),
                BigDecimal.ZERO, "sandbox-1", "token-1"));
        runRepository.save(new Run(null, connection, version, RunStatus.AWAITING_APPROVAL, new BigDecimal("500.00"),
                new BigDecimal("10.00"), "sandbox-2", "token-2"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), buyer.getId(), UserRole.BUYER_ADMIN));

        var all = rest.exchange(url("/api/v1/runs/mine"), HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<List<RunSummaryResponse>>() {});
        assertThat(all.getBody()).hasSize(2);

        var waiting = rest.exchange(url("/api/v1/runs/mine?status=AWAITING_APPROVAL"), HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<List<RunSummaryResponse>>() {});
        assertThat(waiting.getBody()).hasSize(1);
        assertThat(waiting.getBody().get(0).status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=RunSummaryControllerTest`
Expected: FAIL — DTO/endpoint/repository methods do not exist.

- [ ] **Step 3: Write minimal implementation**

Add to `backend/src/main/java/com/agentstore/platform/execution/RunRepository.java`:

```java
    java.util.List<Run> findByConnectionBuyerOrgIdOrderByStartedAtDesc(java.util.UUID buyerOrgId);

    java.util.List<Run> findByConnectionBuyerOrgIdAndStatusOrderByStartedAtDesc(java.util.UUID buyerOrgId, RunStatus status);
```

`backend/src/main/java/com/agentstore/platform/execution/RunSummaryResponse.java`:

```java
package com.agentstore.platform.execution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RunSummaryResponse(UUID id, String agentName, RunStatus status, BigDecimal totalCharged, Instant startedAt) {
    static RunSummaryResponse from(Run run) {
        return new RunSummaryResponse(run.getId(), run.getConnection().getAgent().getName(), run.getStatus(),
                run.getTotalCharged(), run.getStartedAt());
    }
}
```

Add to `backend/src/main/java/com/agentstore/platform/execution/RunDetailController.java` (append method, reuses the `RunRepository` field already injected in that class):

```java
    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public java.util.List<RunSummaryResponse> mine(@RequestParam(required = false) RunStatus status) {
        var claims = (com.agentstore.platform.identity.JwtService.Claims)
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        var runs = status == null
                ? runRepository.findByConnectionBuyerOrgIdOrderByStartedAtDesc(claims.orgId())
                : runRepository.findByConnectionBuyerOrgIdAndStatusOrderByStartedAtDesc(claims.orgId(), status);
        return runs.stream().map(RunSummaryResponse::from).toList();
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=RunSummaryControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(execution): add buyer's own runs listing endpoint with status filter"
```

---

### Task 5: Review queue enrichment and review item detail endpoint

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/publishing/ReviewItemResponse.java` (add `agentName`, `versionNumber` fields — used by both the enriched queue list and the new detail response)
- Create: `backend/src/main/java/com/agentstore/platform/publishing/ReviewItemDetailResponse.java`
- Modify: `backend/src/main/java/com/agentstore/platform/publishing/ReviewController.java` (enrich `queue()`, add a detail `@GetMapping`)
- Test: `backend/src/test/java/com/agentstore/platform/publishing/ReviewItemDetailControllerTest.java`

**Interfaces:**
- Consumes: `ReviewItemRepository`, `ReviewItem` (Task 13 of the backend plan), `ManifestParser` (Task 6 of the backend plan).
- Produces: `ReviewItemResponse{id, agentVersionId, agentName, versionNumber, status}` (widened from the backend plan's original three-field shape — `queue()`'s existing callers only read `id`/`status`, so this is additive, not breaking). `GET /api/v1/review/{reviewItemId}` (role `ADMIN`) → `ReviewItemDetailResponse{id, agentVersionId, agentName, versionNumber, status, manifest: Manifest, notes, createdAt, decidedAt}`. Frontend Task 24 (admin queue list + detail).

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/publishing/ReviewItemDetailControllerTest.java`:

```java
package com.agentstore.platform.publishing;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewItemDetailControllerTest extends AbstractIntegrationTest {

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
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwtService.issue(UUID.randomUUID(), UUID.randomUUID(), UserRole.ADMIN));
        return h;
    }

    @Test
    void queueIncludesAgentNameAndVersionNumber() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Collections", "collections", "d"));
        String manifest = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/manifest/valid-tier-b.json"));
        AgentVersion version = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0", manifest,
                "template-v1", VersionStatus.DRAFT, null, "cl"));
        publishingService.submit(version.getId(), developer.getId());

        var response = rest.exchange(url("/api/v1/review/queue"), HttpMethod.GET, new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<ReviewItemResponse>>() {});

        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).agentName()).isEqualTo("Collections");
        assertThat(response.getBody().get(0).versionNumber()).isEqualTo("1.0.0");
    }

    @Test
    void detailIncludesManifestAndNotes() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo2", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Collections2", "collections", "d"));
        String manifest = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/manifest/valid-tier-b.json"));
        AgentVersion version = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0", manifest,
                "template-v1", VersionStatus.DRAFT, null, "cl"));
        publishingService.submit(version.getId(), developer.getId());
        ReviewItem pending = reviewItemRepository.findByAgentVersionId(version.getId()).orElseThrow();

        var response = rest.exchange(url("/api/v1/review/" + pending.getId()), HttpMethod.GET,
                new HttpEntity<>(adminHeaders()), ReviewItemDetailResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().manifest().scopes()).isNotEmpty();
        assertThat(response.getBody().status()).isEqualTo(ReviewStatus.PENDING);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=ReviewItemDetailControllerTest`
Expected: FAIL — `ReviewItemResponse` doesn't have `agentName`/`versionNumber`, `ReviewItemDetailResponse` and its endpoint don't exist.

- [ ] **Step 3: Write minimal implementation**

Replace `backend/src/main/java/com/agentstore/platform/publishing/ReviewItemResponse.java`:

```java
package com.agentstore.platform.publishing;

import java.util.UUID;

public record ReviewItemResponse(UUID id, UUID agentVersionId, String agentName, String versionNumber, ReviewStatus status) {
    static ReviewItemResponse from(ReviewItem item) {
        return new ReviewItemResponse(item.getId(), item.getAgentVersion().getId(),
                item.getAgentVersion().getAgent().getName(), item.getAgentVersion().getVersionNumber(), item.getStatus());
    }
}
```

`backend/src/main/java/com/agentstore/platform/publishing/ReviewItemDetailResponse.java`:

```java
package com.agentstore.platform.publishing;

import com.agentstore.platform.manifest.Manifest;

import java.time.Instant;
import java.util.UUID;

public record ReviewItemDetailResponse(UUID id, UUID agentVersionId, String agentName, String versionNumber,
                                        ReviewStatus status, Manifest manifest, String notes,
                                        Instant createdAt, Instant decidedAt) {
    static ReviewItemDetailResponse from(ReviewItem item, Manifest manifest) {
        return new ReviewItemDetailResponse(item.getId(), item.getAgentVersion().getId(),
                item.getAgentVersion().getAgent().getName(), item.getAgentVersion().getVersionNumber(),
                item.getStatus(), manifest, item.getNotes(), item.getCreatedAt(), item.getDecidedAt());
    }
}
```

Add to `backend/src/main/java/com/agentstore/platform/publishing/ReviewController.java` (inject `com.agentstore.platform.manifest.ManifestParser` in the constructor alongside existing dependencies; append method):

```java
    @GetMapping("/{reviewItemId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ReviewItemDetailResponse detail(@PathVariable java.util.UUID reviewItemId) {
        ReviewItem item = reviewItemRepository.findById(reviewItemId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Review item not found"));
        var manifest = manifestParser.parse(item.getAgentVersion().getManifestJson());
        return ReviewItemDetailResponse.from(item, manifest);
    }
```

Note: `queue()`'s existing body (`.map(ReviewItemResponse::from)`) needs no change — `from` now populates the two new fields automatically.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=ReviewItemDetailControllerTest,ReviewControllerTest`
Expected: PASS (re-running `ReviewControllerTest` from the backend plan's Task 13 confirms the widened `ReviewItemResponse` doesn't break the existing approve/reject tests, which only assert on `.status()`)

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(publishing): enrich review queue with agent name and add review item detail endpoint"
```

---

### Task 6: Wallet read endpoint

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/ledger/WalletResponse.java`
- Create: `backend/src/main/java/com/agentstore/platform/ledger/WalletController.java`
- Test: `backend/src/test/java/com/agentstore/platform/ledger/WalletControllerTest.java`

**Interfaces:**
- Consumes: `WalletRepository`, `Wallet` (Task 23 of the backend plan).
- Produces: `GET /api/v1/orgs/{orgId}/wallet` (role `BUYER_ADMIN` for one's own org, or `ADMIN` for any) → `WalletResponse{balance, cap}`. `404` if the org has no wallet row yet (wallets are seeded, not self-funded, in this MVP — see `docs/superpowers/specs/2026-09-16-agent-store-frontend-design.md` §1). Frontend Task 18 (billing page) and Task 16 (insufficient-balance messaging before triggering a run).

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/ledger/WalletControllerTest.java`:

```java
package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WalletControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    WalletRepository walletRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void ownerCanReadTheirWalletBalance() {
        Org buyer = orgRepository.save(new Org(null, "BuyerCo", OrgType.BUYER));
        Wallet wallet = new Wallet(null, buyer.getId(), new BigDecimal("41220.00"), new BigDecimal("50000.00"));
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), buyer.getId(), UserRole.BUYER_ADMIN));

        var response = rest.exchange(url("/api/v1/orgs/" + buyer.getId() + "/wallet"), HttpMethod.GET,
                new HttpEntity<>(headers), WalletResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().balance()).isEqualByComparingTo(new BigDecimal("41220.00"));
    }

    @Test
    void cannotReadAnotherOrgsWallet() {
        Org buyer = orgRepository.save(new Org(null, "BuyerCo2", OrgType.BUYER));
        Org intruder = orgRepository.save(new Org(null, "IntruderCo", OrgType.BUYER));
        Wallet wallet = new Wallet(null, buyer.getId(), new BigDecimal("1000.00"), null);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), intruder.getId(), UserRole.BUYER_ADMIN));

        var response = rest.exchange(url("/api/v1/orgs/" + buyer.getId() + "/wallet"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=WalletControllerTest`
Expected: FAIL — `WalletResponse`/`WalletController` do not exist.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/com/agentstore/platform/ledger/WalletResponse.java`:

```java
package com.agentstore.platform.ledger;

import java.math.BigDecimal;

public record WalletResponse(BigDecimal balance, BigDecimal cap) {
    static WalletResponse from(Wallet wallet) {
        return new WalletResponse(wallet.getBalance(), wallet.getCap());
    }
}
```

`backend/src/main/java/com/agentstore/platform/ledger/WalletController.java`:

```java
package com.agentstore.platform.ledger;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
public class WalletController {

    private final WalletRepository walletRepository;

    public WalletController(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @GetMapping("/api/v1/orgs/{orgId}/wallet")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','ADMIN')")
    public WalletResponse wallet(@PathVariable UUID orgId) {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!orgId.equals(claims.orgId()) && claims.role() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot read another org's wallet");
        }
        Wallet wallet = walletRepository.findByOrgId(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No wallet for org " + orgId));
        return WalletResponse.from(wallet);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=WalletControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(ledger): add wallet balance read endpoint"
```

---

## Phase 1 — Frontend scaffolding and data layer

All remaining tasks live in a new `frontend/` directory at the repo root, sibling to `backend/`.

### Task 7: Next.js scaffold, Tailwind design tokens, base layout

**Files:**
- Create: `frontend/package.json`, `frontend/tsconfig.json`, `frontend/next.config.ts`, `frontend/postcss.config.mjs`, `frontend/tailwind.config.ts`
- Create: `frontend/.env.example`
- Create: `frontend/src/app/layout.tsx`, `frontend/src/app/globals.css`
- Create: `frontend/src/app/providers.tsx`

**Interfaces:**
- Produces: a bootable Next.js app on port 3000 with Tailwind color tokens (`paper`, `paper2`, `ink`, `ink2`, `muted`, `line`, `green`, `greenSoft`, `amber`, `amberSoft`, `red`, `redSoft`, `doc`) resolving to the mockups' CSS custom properties, and `<Providers>` wrapping the app in a `QueryClientProvider` for Phase 2's polling hooks. Every later frontend task builds inside `frontend/src/`.

This task has no branching logic to test-drive — it is verified by booting the dev server (Global Constraints).

- [ ] **Step 1: Scaffold the project**

```bash
mkdir -p frontend/src/app
cd frontend
npm init -y
npm install next@14 react@18 react-dom@18 @tanstack/react-query@5
npm install -D typescript @types/react @types/node @types/react-dom tailwindcss postcss autoprefixer
npx tailwindcss init -p
```

- [ ] **Step 2: Write configuration files**

`frontend/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "lib": ["dom", "dom.iterable", "esnext"],
    "allowJs": false,
    "skipLibCheck": true,
    "strict": true,
    "noEmit": true,
    "esModuleInterop": true,
    "module": "esnext",
    "moduleResolution": "bundler",
    "resolveJsonModule": true,
    "isolatedModules": true,
    "jsx": "preserve",
    "incremental": true,
    "plugins": [{ "name": "next" }],
    "paths": { "@/*": ["./src/*"] }
  },
  "include": ["next-env.d.ts", "**/*.ts", "**/*.tsx", ".next/types/**/*.ts"],
  "exclude": ["node_modules"]
}
```

`frontend/next.config.ts`:

```ts
import type { NextConfig } from "next";

const nextConfig: NextConfig = {};

export default nextConfig;
```

`frontend/postcss.config.mjs`:

```js
export default {
  plugins: { tailwindcss: {}, autoprefixer: {} },
};
```

`frontend/tailwind.config.ts`:

```ts
import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        paper: "var(--paper)",
        paper2: "var(--paper-2)",
        ink: "var(--ink)",
        ink2: "var(--ink-2)",
        muted: "var(--muted)",
        line: "var(--line)",
        green: "var(--green)",
        greenSoft: "var(--green-soft)",
        amber: "var(--amber)",
        amberSoft: "var(--amber-soft)",
        red: "var(--red)",
        redSoft: "var(--red-soft)",
        doc: "var(--doc)",
      },
      fontFamily: {
        sans: ["IBM Plex Sans", "system-ui", "sans-serif"],
        serif: ["IBM Plex Serif", "Georgia", "serif"],
      },
    },
  },
};

export default config;
```

`frontend/.env.example`:

```
BACKEND_URL=http://localhost:8080
```

- [ ] **Step 3: Write the design tokens and root layout**

`frontend/src/app/globals.css`:

```css
@tailwind base;
@tailwind components;
@tailwind utilities;

:root {
  --paper: #F6F7F4; --paper-2: #EDEFEA; --ink: #1C2430; --ink-2: #4B5563; --muted: #8A93A0;
  --line: #D6DAD2; --green: #1D5C4A; --green-soft: #E3EFEA; --amber: #B8651B; --amber-soft: #F7EBDD;
  --red: #A63A2B; --red-soft: #F5E3DF; --doc: #FFFFFF;
}

@media (prefers-color-scheme: dark) {
  :root {
    --paper: #151A20; --paper-2: #1C232B; --ink: #E9ECEF; --ink-2: #C2C8D0; --muted: #8A93A0;
    --line: #2C353F; --green: #6FBF9E; --green-soft: #1B2E28; --amber: #E0A05C; --amber-soft: #332618;
    --red: #E08A7B; --red-soft: #3A2420; --doc: #1C232B;
  }
}

body {
  background: var(--paper);
  color: var(--ink);
}
```

`frontend/src/app/providers.tsx`:

```tsx
"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";

export function Providers({ children }: { children: React.ReactNode }) {
  const [client] = useState(() => new QueryClient());
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
```

`frontend/src/app/layout.tsx`:

```tsx
import type { Metadata } from "next";
import { Providers } from "./providers";
import "./globals.css";

export const metadata: Metadata = {
  title: "Karya — governed agent store",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="font-sans text-[15px]">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
```

- [ ] **Step 4: Verify it boots**

Run: `cd frontend && cp .env.example .env.local && npm run dev`
Expected: server starts on `http://localhost:3000` without errors (the page itself 404s until Task 9 adds routes — that's expected at this point).

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "chore(frontend): scaffold Next.js app with Tailwind design tokens"
```

---

### Task 8: Shared types, server-only backend client, session helpers

**Files:**
- Create: `frontend/src/lib/types.ts`
- Create: `frontend/src/lib/backend.ts`
- Create: `frontend/src/lib/session.ts`
- Create: `frontend/src/lib/format.ts`
- Create: `frontend/vitest.config.ts`
- Test: `frontend/src/lib/session.test.ts`

**Interfaces:**
- Produces: every DTO type used by every later task (see the full list in the file below); `backend.get<T>(path)` / `backend.post<T>(path, body?)` (server-only, reads the `karya_session` cookie, throws `BackendError{status, body}` on a non-2xx response); `decodeSessionToken(token): SessionClaims | null` (pure, Edge-safe — used by both Task 9's middleware and `getSession()`); `getSession(): Promise<SessionClaims | null>`; `roleHome(role): string`; `formatInr(amount): string`. This is the foundation every subsequent task imports from.

- [ ] **Step 1: Write the failing test**

Install test tooling first:

```bash
cd frontend
npm install -D vitest @testing-library/react @testing-library/jest-dom @testing-library/user-event jsdom
```

`frontend/vitest.config.ts`:

```ts
import { defineConfig } from "vitest/config";
import path from "path";

export default defineConfig({
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./vitest.setup.ts"],
  },
  resolve: {
    alias: { "@": path.resolve(__dirname, "./src") },
  },
});
```

`frontend/vitest.setup.ts`:

```ts
import "@testing-library/jest-dom/vitest";
```

`frontend/src/lib/session.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { decodeSessionToken, roleHome } from "./session";

function fakeJwt(payload: Record<string, unknown>): string {
  const base64url = (obj: unknown) =>
    Buffer.from(JSON.stringify(obj)).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `${base64url({ alg: "HS256" })}.${base64url(payload)}.fake-signature`;
}

describe("decodeSessionToken", () => {
  it("reads userId from the standard sub claim, plus orgId and role", () => {
    const token = fakeJwt({ sub: "user-1", orgId: "org-1", role: "BUYER_ADMIN" });
    expect(decodeSessionToken(token)).toEqual({ userId: "user-1", orgId: "org-1", role: "BUYER_ADMIN" });
  });

  it("returns null for a malformed token", () => {
    expect(decodeSessionToken("not-a-jwt")).toBeNull();
  });
});

describe("roleHome", () => {
  it("routes each role to its console root", () => {
    expect(roleHome("DEVELOPER")).toBe("/dashboard");
    expect(roleHome("ADMIN")).toBe("/review");
    expect(roleHome("BUYER_ADMIN")).toBe("/catalog");
    expect(roleHome("BUYER_APPROVER")).toBe("/catalog");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Add to `frontend/package.json` scripts: `"test": "vitest run"`.

Run: `cd frontend && npm test -- session.test.ts`
Expected: FAIL — `./session` module does not exist.

- [ ] **Step 3: Write minimal implementation**

`frontend/src/lib/types.ts`:

```ts
export type UserRole = "BUYER_ADMIN" | "BUYER_APPROVER" | "DEVELOPER" | "ADMIN";
export type OrgType = "BUYER" | "DEVELOPER";
export type VersionStatus = "DRAFT" | "IN_REVIEW" | "LIVE" | "REJECTED";
export type ScopeType = "READ" | "WRITE";
export type PricingUnit = "METERED" | "OUTCOME" | "SUBSCRIPTION";
export type ConnectionStatus = "ACTIVE" | "REVOKED";
export type RunStatus = "PENDING" | "RUNNING" | "AWAITING_APPROVAL" | "COMPLETED" | "FAILED" | "CANCELLED";
export type RunStepKind = "READ" | "WRITE" | "GATE" | "COST" | "COMPLETE" | "FAILED";
export type LedgerEntryType = "CHARGE" | "REVERSAL" | "FEE";
export type ReviewStatus = "PENDING" | "APPROVED" | "REJECTED";

export interface AuthResponse {
  token: string;
}

// Parsed manifest shape (GET responses) — mirrors the backend's Manifest Java record exactly.
export interface Scope {
  type: ScopeType;
  resource: string;
  description: string;
}
export interface CostEnvelope {
  currency: string;
  maxPerRun: number;
  pricingUnit: PricingUnit;
  unitPrice: number;
  unitLabel: string;
}
export interface ApprovalGateDecl {
  trigger: string;
  description: string;
}
export interface Manifest {
  name: string;
  version: string;
  scopes: Scope[];
  networkAllowlist: string[];
  costEnvelope: CostEnvelope;
  approvalGates: ApprovalGateDecl[];
  dataClasses: string[];
}

// Raw manifest wire format — what POST .../versions expects as manifestJson, built by Task 21's wizard.
export interface ManifestDraft {
  name: string;
  version: string;
  scopes: Scope[];
  network: { allowlist: string[] };
  costEnvelope: CostEnvelope;
  modelRequirements: { provider: string; model: string };
  approvalGates: ApprovalGateDecl[];
  dataClasses: string[];
}

export interface AgentResponse {
  id: string;
  name: string;
  category: string;
  description: string;
}
export interface AgentVersionResponse {
  id: string;
  versionNumber: string;
  status: VersionStatus;
}
export interface CatalogListingResponse {
  agentId: string;
  agentName: string;
  category: string;
  versionId: string;
  versionNumber: string;
}
export interface AgentVersionDetailResponse {
  id: string;
  agentId: string;
  agentName: string;
  versionNumber: string;
  status: VersionStatus;
  manifest: Manifest;
}
export interface MyAgentsResponse {
  agentId: string;
  agentName: string;
  category: string;
  versions: AgentVersionResponse[];
}

export interface ConnectionResponse {
  id: string;
  agentId: string;
  grantedScopes: string[];
  spendCap: number;
  status: ConnectionStatus;
}
export interface ConnectionSummaryResponse extends ConnectionResponse {
  agentName: string;
}

export interface RunResponse {
  id: string;
  status: RunStatus;
}
export interface RunStepResponse {
  kind: RunStepKind;
  detail: string;
  createdAt: string;
}
export interface RunDetailResponse {
  id: string;
  status: RunStatus;
  costCap: number;
  totalCharged: number;
  steps: RunStepResponse[];
}
export interface RunSummaryResponse {
  id: string;
  agentName: string;
  status: RunStatus;
  totalCharged: number;
  startedAt: string;
}

export interface StatementLineResponse {
  runId: string;
  agentName: string;
  amount: number;
  type: LedgerEntryType;
  createdAt: string;
}
export interface WalletResponse {
  balance: number;
  cap: number | null;
}

export interface ReviewItemResponse {
  id: string;
  agentVersionId: string;
  agentName: string;
  versionNumber: string;
  status: ReviewStatus;
}
export interface ReviewItemDetailResponse extends ReviewItemResponse {
  manifest: Manifest;
  notes: string | null;
  createdAt: string;
  decidedAt: string | null;
}
```

`frontend/src/lib/session.ts`:

```ts
import { cookies } from "next/headers";
import type { UserRole } from "./types";

export const SESSION_COOKIE = "karya_session";

export interface SessionClaims {
  userId: string;
  orgId: string;
  role: UserRole;
}

export function decodeSessionToken(token: string): SessionClaims | null {
  try {
    const payloadSegment = token.split(".")[1];
    const base64 = payloadSegment.replace(/-/g, "+").replace(/_/g, "/");
    const json = JSON.parse(atob(base64)) as { sub: string; orgId: string; role: UserRole };
    if (!json.sub || !json.orgId || !json.role) return null;
    return { userId: json.sub, orgId: json.orgId, role: json.role };
  } catch {
    return null;
  }
}

export async function getSession(): Promise<SessionClaims | null> {
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  if (!token) return null;
  return decodeSessionToken(token);
}

export function roleHome(role: UserRole): string {
  if (role === "DEVELOPER") return "/dashboard";
  if (role === "ADMIN") return "/review";
  return "/catalog";
}
```

`frontend/src/lib/backend.ts`:

```ts
import { cookies } from "next/headers";
import { SESSION_COOKIE } from "./session";

export class BackendError extends Error {
  status: number;
  body: unknown;

  constructor(status: number, body: unknown) {
    super(`Backend request failed with status ${status}`);
    this.status = status;
    this.body = body;
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  const res = await fetch(`${process.env.BACKEND_URL}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init.headers ?? {}),
    },
    cache: "no-store",
  });
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new BackendError(res.status, body);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const backend = {
  get: <T,>(path: string) => request<T>(path),
  post: <T,>(path: string, body?: unknown) =>
    request<T>(path, { method: "POST", body: body !== undefined ? JSON.stringify(body) : undefined }),
};
```

`frontend/src/lib/format.ts`:

```ts
export function formatInr(amount: number): string {
  return new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 }).format(amount);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- session.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): add shared types, backend client, and session helpers"
```

---

### Task 9: Login, signup, and role-based route middleware

**Files:**
- Create: `frontend/src/lib/routing.ts`
- Create: `frontend/src/middleware.ts`
- Create: `frontend/src/app/page.tsx`
- Create: `frontend/src/app/(auth)/login/actions.ts`, `frontend/src/app/(auth)/login/page.tsx`
- Create: `frontend/src/app/(auth)/signup/actions.ts`, `frontend/src/app/(auth)/signup/page.tsx`
- Test: `frontend/src/lib/routing.test.ts`

**Interfaces:**
- Consumes: `backend` client is NOT used here — login/signup call the backend directly with `fetch` since no session cookie exists yet to attach (Global Constraints' "server-side only" rule is about *authenticated* calls; these two are the one legitimate exception). `decodeSessionToken`, `roleHome`, `SESSION_COOKIE` (Task 8).
- Produces: `resolveRedirect(pathname, claims): string | null` (pure — Task 24's E2E test relies on the resulting redirect behavior, not this function directly). Signup always sends `{orgType: "BUYER", role: "BUYER_ADMIN"}` — no role selector, per spec §6. Every later buyer/developer/admin task assumes `middleware.ts` already gates its route group.

- [ ] **Step 1: Write the failing test**

`frontend/src/lib/routing.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { resolveRedirect } from "./routing";
import type { SessionClaims } from "./session";

describe("resolveRedirect", () => {
  it("allows unauthenticated access to auth pages", () => {
    expect(resolveRedirect("/login", null)).toBeNull();
  });

  it("sends an unauthenticated visitor to login", () => {
    expect(resolveRedirect("/catalog", null)).toBe("/login");
  });

  it("bounces a developer away from buyer-only routes to their own home", () => {
    const claims: SessionClaims = { userId: "u1", orgId: "o1", role: "DEVELOPER" };
    expect(resolveRedirect("/catalog", claims)).toBe("/dashboard");
  });

  it("lets a buyer admin through to buyer routes", () => {
    const claims: SessionClaims = { userId: "u1", orgId: "o1", role: "BUYER_ADMIN" };
    expect(resolveRedirect("/catalog", claims)).toBeNull();
  });

  it("lets an admin through to the review queue but not a buyer route", () => {
    const claims: SessionClaims = { userId: "u1", orgId: "o1", role: "ADMIN" };
    expect(resolveRedirect("/review", claims)).toBeNull();
    expect(resolveRedirect("/catalog", claims)).toBe("/review");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- routing.test.ts`
Expected: FAIL — `./routing` module does not exist.

- [ ] **Step 3: Write minimal implementation**

`frontend/src/lib/routing.ts`:

```ts
import type { SessionClaims } from "./session";
import { roleHome } from "./session";
import type { UserRole } from "./types";

const ROLE_PREFIXES: [string, UserRole[]][] = [
  ["/dashboard", ["DEVELOPER"]],
  ["/review", ["ADMIN"]],
  ["/catalog", ["BUYER_ADMIN", "BUYER_APPROVER"]],
  ["/agents", ["BUYER_ADMIN", "BUYER_APPROVER"]],
  ["/connect", ["BUYER_ADMIN", "BUYER_APPROVER"]],
  ["/runs", ["BUYER_ADMIN", "BUYER_APPROVER"]],
  ["/billing", ["BUYER_ADMIN", "BUYER_APPROVER"]],
];

export function resolveRedirect(pathname: string, claims: SessionClaims | null): string | null {
  if (pathname.startsWith("/login") || pathname.startsWith("/signup")) return null;
  if (!claims) return "/login";
  const match = ROLE_PREFIXES.find(([prefix]) => pathname.startsWith(prefix));
  if (match && !match[1].includes(claims.role)) return roleHome(claims.role);
  return null;
}
```

`frontend/src/middleware.ts`:

```ts
import { NextRequest, NextResponse } from "next/server";
import { decodeSessionToken, SESSION_COOKIE } from "@/lib/session";
import { resolveRedirect } from "@/lib/routing";

export function middleware(request: NextRequest) {
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  const claims = token ? decodeSessionToken(token) : null;
  const redirectTo = resolveRedirect(request.nextUrl.pathname, claims);
  if (redirectTo) return NextResponse.redirect(new URL(redirectTo, request.url));
  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!api|_next/static|_next/image|favicon.ico).*)"],
};
```

`frontend/src/app/page.tsx`:

```tsx
import { redirect } from "next/navigation";
import { getSession, roleHome } from "@/lib/session";

export default async function RootPage() {
  const session = await getSession();
  redirect(session ? roleHome(session.role) : "/login");
}
```

`frontend/src/app/(auth)/login/actions.ts`:

```ts
"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { decodeSessionToken, roleHome, SESSION_COOKIE } from "@/lib/session";
import type { AuthResponse } from "@/lib/types";

export interface LoginState {
  error: string | null;
}

export async function loginAction(_prevState: LoginState, formData: FormData): Promise<LoginState> {
  const email = String(formData.get("email") ?? "");
  const password = String(formData.get("password") ?? "");

  const res = await fetch(`${process.env.BACKEND_URL}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) return { error: "Invalid email or password" };

  const { token } = (await res.json()) as AuthResponse;
  const claims = decodeSessionToken(token);
  if (!claims) return { error: "Login succeeded but the session could not be read" };

  (await cookies()).set(SESSION_COOKIE, token, { httpOnly: true, sameSite: "lax", path: "/" });
  redirect(roleHome(claims.role));
}
```

`frontend/src/app/(auth)/login/page.tsx`:

```tsx
"use client";

import { useFormState, useFormStatus } from "react-dom";
import { loginAction, type LoginState } from "./actions";

const initialState: LoginState = { error: null };

function SubmitButton() {
  const { pending } = useFormStatus();
  return (
    <button type="submit" disabled={pending} className="bg-green text-white rounded-lg px-5 py-2 font-medium w-full disabled:opacity-50">
      {pending ? "Signing in…" : "Sign in"}
    </button>
  );
}

export default function LoginPage() {
  const [state, formAction] = useFormState(loginAction, initialState);
  return (
    <div className="max-w-sm mx-auto mt-24">
      <h1 className="font-serif text-2xl mb-6">Sign in to Karya</h1>
      <form action={formAction} className="space-y-4">
        <input name="email" type="email" required placeholder="Email" className="border border-line rounded-lg px-3 py-2 w-full" />
        <input name="password" type="password" required placeholder="Password" className="border border-line rounded-lg px-3 py-2 w-full" />
        {state.error && <p className="text-red text-sm">{state.error}</p>}
        <SubmitButton />
      </form>
      <a href="/signup" className="block text-center text-sm text-ink2 mt-4">Create a buyer account</a>
    </div>
  );
}
```

`frontend/src/app/(auth)/signup/actions.ts`:

```ts
"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { decodeSessionToken, roleHome, SESSION_COOKIE } from "@/lib/session";
import type { AuthResponse } from "@/lib/types";

export interface SignupState {
  error: string | null;
}

export async function signupAction(_prevState: SignupState, formData: FormData): Promise<SignupState> {
  const orgName = String(formData.get("orgName") ?? "");
  const email = String(formData.get("email") ?? "");
  const password = String(formData.get("password") ?? "");

  const res = await fetch(`${process.env.BACKEND_URL}/api/v1/auth/signup`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ orgName, orgType: "BUYER", email, password, role: "BUYER_ADMIN" }),
  });
  if (!res.ok) return { error: "Could not create an account — check your details" };

  const { token } = (await res.json()) as AuthResponse;
  const claims = decodeSessionToken(token);
  if (!claims) return { error: "Signup succeeded but the session could not be read" };

  (await cookies()).set(SESSION_COOKIE, token, { httpOnly: true, sameSite: "lax", path: "/" });
  redirect(roleHome(claims.role));
}
```

`frontend/src/app/(auth)/signup/page.tsx`:

```tsx
"use client";

import { useFormState, useFormStatus } from "react-dom";
import { signupAction, type SignupState } from "./actions";

const initialState: SignupState = { error: null };

function SubmitButton() {
  const { pending } = useFormStatus();
  return (
    <button type="submit" disabled={pending} className="bg-green text-white rounded-lg px-5 py-2 font-medium w-full disabled:opacity-50">
      {pending ? "Creating account…" : "Create account"}
    </button>
  );
}

export default function SignupPage() {
  const [state, formAction] = useFormState(signupAction, initialState);
  return (
    <div className="max-w-sm mx-auto mt-24">
      <h1 className="font-serif text-2xl mb-6">Create a buyer account</h1>
      <form action={formAction} className="space-y-4">
        <input name="orgName" required placeholder="Company name" className="border border-line rounded-lg px-3 py-2 w-full" />
        <input name="email" type="email" required placeholder="Work email" className="border border-line rounded-lg px-3 py-2 w-full" />
        <input name="password" type="password" required minLength={8} placeholder="Password" className="border border-line rounded-lg px-3 py-2 w-full" />
        {state.error && <p className="text-red text-sm">{state.error}</p>}
        <SubmitButton />
      </form>
      <a href="/login" className="block text-center text-sm text-ink2 mt-4">Already have an account? Sign in</a>
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- routing.test.ts`
Expected: PASS

- [ ] **Step 5: Manual verification**

Run: `cd frontend && npm run dev`, then in a browser visit `http://localhost:3000` (redirects to `/login`), sign up as a buyer (requires the backend running with `BACKEND_URL` reachable), confirm redirect to `/catalog` (which 404s until Task 13 — expected at this point).

- [ ] **Step 6: Commit**

```bash
git add frontend
git commit -m "feat(frontend): add login, signup, and role-based route middleware"
```

---

## Phase 2 — Shared components

Reusable pieces referenced across Phases 3–5, per spec §4.

### Task 10: StatusPill and WizardRail primitives

**Files:**
- Create: `frontend/src/components/StatusPill.tsx`, `frontend/src/components/StatusPill.test.tsx`
- Create: `frontend/src/components/WizardRail.tsx`, `frontend/src/components/WizardRail.test.tsx`

**Interfaces:**
- Produces: `<StatusPill status={string} />` (any backend status enum value — `VersionStatus`, `ConnectionStatus`, `RunStatus`, `ReviewStatus`); `<WizardRail steps={string[]} currentIndex={number} />`. Used by Tasks 15, 18, 19, 21, 22, 23, 24.

- [ ] **Step 1: Write the failing tests**

`frontend/src/components/StatusPill.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { StatusPill } from "./StatusPill";

describe("StatusPill", () => {
  it("renders a lowercased, spaced label for a known status", () => {
    render(<StatusPill status="IN_REVIEW" />);
    expect(screen.getByText("in review")).toBeInTheDocument();
  });

  it("falls back to a neutral style for an unrecognized status", () => {
    render(<StatusPill status="SOMETHING_NEW" />);
    expect(screen.getByText("something new")).toBeInTheDocument();
  });
});
```

`frontend/src/components/WizardRail.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { WizardRail } from "./WizardRail";

describe("WizardRail", () => {
  it("renders every step label", () => {
    render(<WizardRail steps={["Basics", "Scopes", "Review"]} currentIndex={1} />);
    expect(screen.getByText("Basics")).toBeInTheDocument();
    expect(screen.getByText("Scopes")).toBeInTheDocument();
    expect(screen.getByText("Review")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npm test -- StatusPill.test.tsx WizardRail.test.tsx`
Expected: FAIL — neither component exists.

- [ ] **Step 3: Write minimal implementation**

`frontend/src/components/StatusPill.tsx`:

```tsx
const STYLES: Record<string, string> = {
  LIVE: "bg-greenSoft text-green",
  ACTIVE: "bg-greenSoft text-green",
  COMPLETED: "bg-greenSoft text-green",
  APPROVED: "bg-greenSoft text-green",
  DRAFT: "bg-paper2 text-muted",
  REVOKED: "bg-paper2 text-muted",
  IN_REVIEW: "bg-amberSoft text-amber",
  PENDING: "bg-amberSoft text-amber",
  RUNNING: "bg-amberSoft text-amber",
  AWAITING_APPROVAL: "bg-amberSoft text-amber",
  REJECTED: "bg-redSoft text-red",
  FAILED: "bg-redSoft text-red",
  CANCELLED: "bg-redSoft text-red",
};

export function StatusPill({ status }: { status: string }) {
  const style = STYLES[status] ?? "bg-paper2 text-muted";
  return (
    <span className={`text-xs rounded-full px-2 py-0.5 font-medium inline-block ${style}`}>
      {status.replace(/_/g, " ").toLowerCase()}
    </span>
  );
}
```

`frontend/src/components/WizardRail.tsx`:

```tsx
export function WizardRail({ steps, currentIndex }: { steps: string[]; currentIndex: number }) {
  return (
    <div className="flex border border-line rounded-lg overflow-hidden mb-6 bg-doc">
      {steps.map((label, i) => (
        <div
          key={label}
          className={`flex-1 px-3 py-2 text-xs border-r border-line last:border-r-0 flex items-center gap-2 ${
            i === currentIndex ? "text-ink" : i < currentIndex ? "text-green" : "text-muted"
          }`}
        >
          <span
            className={`w-5 h-5 rounded-full border flex items-center justify-center text-[11px] flex-none ${
              i < currentIndex ? "bg-green border-green text-white" : i === currentIndex ? "border-ink font-semibold" : "border-line"
            }`}
          >
            {i + 1}
          </span>
          {label}
        </div>
      ))}
    </div>
  );
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npm test -- StatusPill.test.tsx WizardRail.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): add StatusPill and WizardRail primitives"
```

---

### Task 11: PermissionStatementDoc (three modes) and ScopeToggleGrant

**Files:**
- Create: `frontend/src/components/ScopeToggleGrant.tsx`, `frontend/src/components/ScopeToggleGrant.test.tsx`
- Create: `frontend/src/components/PermissionStatementDoc.tsx`, `frontend/src/components/PermissionStatementDoc.test.tsx`

**Interfaces:**
- Consumes: `Manifest`, `Scope` (Task 8), `formatInr` (Task 8).
- Produces: `<ScopeToggleGrant scopes={Scope[]} onChange={(grantedResources: string[]) => void} />` — every declared scope starts granted; toggling removes/re-adds its `resource` string from the reported set; it is structurally impossible to report a resource not present in `scopes`, since rows are rendered *from* `scopes`. `<PermissionStatementDoc manifest={Manifest} mode={"listing" | "grant" | "preview"} onGrantChange?={(resources: string[]) => void} />` — spec §5's one-manifest-three-renderers component. Used by Tasks 14 (listing), 16 (grant), 21 (preview), 23 (listing), 24 (listing).

- [ ] **Step 1: Write the failing tests**

`frontend/src/components/ScopeToggleGrant.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ScopeToggleGrant } from "./ScopeToggleGrant";
import type { Scope } from "@/lib/types";

const scopes: Scope[] = [
  { type: "READ", resource: "bank_statement", description: "Read uploaded bank statement rows" },
  { type: "WRITE", resource: "journal_draft", description: "Propose draft journal entries" },
];

describe("ScopeToggleGrant", () => {
  it("starts with every declared scope granted", () => {
    render(<ScopeToggleGrant scopes={scopes} onChange={vi.fn()} />);
    expect(screen.getByRole("switch", { name: /bank_statement/i })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("switch", { name: /journal_draft/i })).toHaveAttribute("aria-checked", "true");
  });

  it("never reports a resource outside the declared manifest scopes", async () => {
    const onChange = vi.fn();
    render(<ScopeToggleGrant scopes={scopes} onChange={onChange} />);
    await userEvent.click(screen.getByRole("switch", { name: /journal_draft/i }));
    const lastCall = onChange.mock.calls.at(-1)![0] as string[];
    expect(lastCall).toEqual(["bank_statement"]);
    expect(lastCall.every((r) => scopes.some((s) => s.resource === r))).toBe(true);
  });
});
```

`frontend/src/components/PermissionStatementDoc.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PermissionStatementDoc } from "./PermissionStatementDoc";
import type { Manifest } from "@/lib/types";

const manifest: Manifest = {
  name: "GSTR-2B Reconciler",
  version: "4.2",
  scopes: [{ type: "READ", resource: "purchase_register", description: "Tally purchase register" }],
  networkAllowlist: ["gst.gov.in"],
  costEnvelope: { currency: "INR", maxPerRun: 25000, pricingUnit: "OUTCOME", unitPrice: 18, unitLabel: "matched_invoice" },
  approvalGates: [{ trigger: "MARK_UNCLAIMABLE_ABOVE_THRESHOLD", description: "Marking a credit unclaimable above ₹50,000" }],
  dataClasses: [],
};

describe("PermissionStatementDoc", () => {
  it("renders read-only scope rows in listing mode, with no interactive toggle", () => {
    render(<PermissionStatementDoc manifest={manifest} mode="listing" />);
    expect(screen.getByText("purchase_register")).toBeInTheDocument();
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
  });

  it("renders interactive toggles in grant mode", () => {
    render(<PermissionStatementDoc manifest={manifest} mode="grant" onGrantChange={() => {}} />);
    expect(screen.getByRole("switch", { name: /purchase_register/i })).toBeInTheDocument();
  });

  it("shows the approval gate and the per-run spend cap", () => {
    render(<PermissionStatementDoc manifest={manifest} mode="listing" />);
    expect(screen.getByText(/Marking a credit unclaimable/)).toBeInTheDocument();
    expect(screen.getByText("₹25,000.00")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npm test -- ScopeToggleGrant.test.tsx PermissionStatementDoc.test.tsx`
Expected: FAIL — neither component exists.

- [ ] **Step 3: Write minimal implementation**

`frontend/src/components/ScopeToggleGrant.tsx`:

```tsx
"use client";

import { useState } from "react";
import type { Scope } from "@/lib/types";

interface Props {
  scopes: Scope[];
  onChange: (grantedResources: string[]) => void;
}

export function ScopeToggleGrant({ scopes, onChange }: Props) {
  const [granted, setGranted] = useState<Set<string>>(() => new Set(scopes.map((s) => s.resource)));

  function toggle(resource: string) {
    setGranted((prev) => {
      const next = new Set(prev);
      if (next.has(resource)) next.delete(resource);
      else next.add(resource);
      onChange([...next]);
      return next;
    });
  }

  return (
    <div className="border border-line rounded-lg overflow-hidden">
      <div className="grid grid-cols-[1fr_100px] gap-3 bg-paper2 px-4 py-2 text-xs text-muted">
        <span>What it reaches</span>
        <span>Granted</span>
      </div>
      {scopes.map((scope) => {
        const isGranted = granted.has(scope.resource);
        return (
          <div key={scope.resource} className="grid grid-cols-[1fr_100px] gap-3 items-center px-4 py-3 border-t border-line">
            <div>
              <b className="font-medium">{scope.resource}</b>
              <div className="text-xs text-ink2">
                {scope.description} · {scope.type === "READ" ? "Read only" : "Write"}
              </div>
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={isGranted}
              aria-label={`Grant access to ${scope.resource}`}
              onClick={() => toggle(scope.resource)}
              className={`justify-self-end w-11 h-6 rounded-full relative ${isGranted ? "bg-green" : "bg-paper2 border border-line"}`}
            >
              <span
                className={`absolute top-0.5 w-5 h-5 rounded-full bg-doc transition-all ${isGranted ? "left-[22px]" : "left-0.5"}`}
              />
            </button>
          </div>
        );
      })}
    </div>
  );
}
```

`frontend/src/components/PermissionStatementDoc.tsx`:

```tsx
import { formatInr } from "@/lib/format";
import type { Manifest } from "@/lib/types";
import { ScopeToggleGrant } from "./ScopeToggleGrant";

interface Props {
  manifest: Manifest;
  mode: "listing" | "grant" | "preview";
  onGrantChange?: (grantedResources: string[]) => void;
}

export function PermissionStatementDoc({ manifest, mode, onGrantChange }: Props) {
  const reads = manifest.scopes.filter((s) => s.type === "READ");
  const writes = manifest.scopes.filter((s) => s.type === "WRITE");

  return (
    <aside className="bg-doc border border-line rounded p-6" aria-label="Permission statement">
      <h3 className="font-serif text-lg">{manifest.name}</h3>
      <p className="font-serif italic text-ink2 text-sm mb-4">What this agent may do</p>

      {mode === "grant" ? (
        <ScopeToggleGrant scopes={manifest.scopes} onChange={onGrantChange ?? (() => {})} />
      ) : (
        <dl>
          <dt className="font-serif text-xs text-muted mt-4 mb-1 pb-1 border-b border-dotted border-line">Reads</dt>
          {reads.map((s) => (
            <dd key={s.resource} className="flex justify-between text-sm py-0.5">
              <span>{s.resource}</span>
              <span className="text-ink2">{s.description}</span>
            </dd>
          ))}
          <dt className="font-serif text-xs text-muted mt-4 mb-1 pb-1 border-b border-dotted border-line">Writes</dt>
          {writes.length === 0 ? (
            <dd className="text-sm py-0.5 text-muted line-through">nothing</dd>
          ) : (
            writes.map((s) => (
              <dd key={s.resource} className="flex justify-between text-sm py-0.5">
                <span>{s.resource}</span>
                <span className="text-ink2">{s.description}</span>
              </dd>
            ))
          )}
        </dl>
      )}

      <dt className="font-serif text-xs text-muted mt-4 mb-1 pb-1 border-b border-dotted border-line">Talks to</dt>
      {manifest.networkAllowlist.length === 0 ? (
        <dd className="text-sm py-0.5 text-muted">nothing outside the platform</dd>
      ) : (
        manifest.networkAllowlist.map((host) => (
          <dd key={host} className="flex justify-between text-sm py-0.5">
            <span>{host}</span>
            <span className="text-ink2">only</span>
          </dd>
        ))
      )}

      {manifest.approvalGates.length > 0 && (
        <>
          <dt className="font-serif text-xs text-muted mt-4 mb-1 pb-1 border-b border-dotted border-line">Asks first</dt>
          {manifest.approvalGates.map((g) => (
            <dd key={g.trigger} className="flex justify-between text-sm py-0.5">
              <span>{g.description}</span>
              <span className="text-ink2">always</span>
            </dd>
          ))}
        </>
      )}

      <div className="mt-5 pt-4 border-t border-line flex justify-between items-baseline">
        <span className="text-ink2 text-sm">Most it can spend per run</span>
        <span className="font-serif text-xl">{formatInr(manifest.costEnvelope.maxPerRun)}</span>
      </div>
    </aside>
  );
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npm test -- ScopeToggleGrant.test.tsx PermissionStatementDoc.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): add PermissionStatementDoc and ScopeToggleGrant"
```

---

### Task 12: RunTraceStep and the run-polling hook

**Files:**
- Create: `frontend/src/components/RunTraceStep.tsx`, `frontend/src/components/RunTraceStep.test.tsx`
- Create: `frontend/src/components/useRunPolling.ts`, `frontend/src/components/useRunPolling.test.tsx`

**Interfaces:**
- Consumes: `RunStepResponse`, `RunDetailResponse` (Task 8).
- Produces: `<RunTraceStep step={RunStepResponse} />`; `useRunPolling(runId: string, initialData: RunDetailResponse)` — a TanStack Query hook that polls `GET /api/runs/{runId}` (the Task 17 route handler) every 2s and stops once `status` reaches `COMPLETED`, `FAILED`, or `CANCELLED` (spec §3's two polling consumers, and §6's cost-cap terminal state). Used by Task 18 (run console).

- [ ] **Step 1: Write the failing tests**

`frontend/src/components/RunTraceStep.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { RunTraceStep } from "./RunTraceStep";
import type { RunStepResponse } from "@/lib/types";

describe("RunTraceStep", () => {
  it("renders a human label for each step kind", () => {
    const step: RunStepResponse = { kind: "GATE", detail: '{"summary":"Needs approval above threshold"}', createdAt: "2026-09-16T09:58:12Z" };
    render(<RunTraceStep step={step} />);
    expect(screen.getByText("Needs your decision")).toBeInTheDocument();
    expect(screen.getByText("Needs approval above threshold")).toBeInTheDocument();
  });

  it("falls back to the raw detail string when it is not JSON", () => {
    const step: RunStepResponse = { kind: "READ", detail: "not json", createdAt: "2026-09-16T09:41:02Z" };
    render(<RunTraceStep step={step} />);
    expect(screen.getByText("not json")).toBeInTheDocument();
  });
});
```

`frontend/src/components/useRunPolling.test.tsx`:

```tsx
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useRunPolling } from "./useRunPolling";
import type { RunDetailResponse } from "@/lib/types";

const runningRun: RunDetailResponse = { id: "r1", status: "RUNNING", costCap: 100, totalCharged: 10, steps: [] };
const completedRun: RunDetailResponse = { ...runningRun, status: "COMPLETED" };

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient();
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe("useRunPolling", () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => completedRun });
  });

  it("fetches fresh run data from the local proxy route", async () => {
    const { result } = renderHook(() => useRunPolling("r1", runningRun), { wrapper });
    await waitFor(() => expect(result.current.data?.status).toBe("COMPLETED"));
    expect(global.fetch).toHaveBeenCalledWith("/api/runs/r1");
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npm test -- RunTraceStep.test.tsx useRunPolling.test.tsx`
Expected: FAIL — neither module exists.

- [ ] **Step 3: Write minimal implementation**

`frontend/src/components/RunTraceStep.tsx`:

```tsx
import type { RunStepResponse } from "@/lib/types";

function parseDetail(raw: string): { summary?: string } {
  try {
    return JSON.parse(raw) as { summary?: string };
  } catch {
    return {};
  }
}

const KIND_LABEL: Record<RunStepResponse["kind"], string> = {
  READ: "Read",
  WRITE: "Wrote",
  GATE: "Needs your decision",
  COST: "Charged",
  COMPLETE: "Completed",
  FAILED: "Failed",
};

export function RunTraceStep({ step }: { step: RunStepResponse }) {
  const detail = parseDetail(step.detail);
  const isGate = step.kind === "GATE";
  return (
    <div className={`relative pl-6 pb-5 border-l-2 ${isGate ? "border-amber" : "border-line"}`}>
      <div className="text-xs text-muted">{new Date(step.createdAt).toLocaleTimeString()}</div>
      <div className="font-medium">{KIND_LABEL[step.kind]}</div>
      <div className="text-sm text-ink2">{detail.summary ?? step.detail}</div>
    </div>
  );
}
```

`frontend/src/components/useRunPolling.ts`:

```ts
"use client";

import { useQuery } from "@tanstack/react-query";
import type { RunDetailResponse } from "@/lib/types";

const TERMINAL_STATUSES: RunDetailResponse["status"][] = ["COMPLETED", "FAILED", "CANCELLED"];

export function useRunPolling(runId: string, initialData: RunDetailResponse) {
  return useQuery({
    queryKey: ["run", runId],
    initialData,
    refetchInterval: (query) => (TERMINAL_STATUSES.includes(query.state.data?.status ?? "PENDING") ? false : 2000),
    queryFn: async () => {
      const res = await fetch(`/api/runs/${runId}`);
      if (!res.ok) throw new Error("Failed to load run");
      return (await res.json()) as RunDetailResponse;
    },
  });
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npm test -- RunTraceStep.test.tsx useRunPolling.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): add RunTraceStep and the run-polling hook"
```

---

## Phase 3 — Buyer console

Buyer routes live under `frontend/src/app/(buyer)/`, gated by Task 9's middleware to `BUYER_ADMIN`/`BUYER_APPROVER`. Every `page.tsx` here is a thin async Server Component that fetches via `backend.get` and renders a co-located sync presentational component (Global Constraints).

### Task 13: Buyer layout, catalog page

**Files:**
- Create: `frontend/src/app/(buyer)/layout.tsx`
- Create: `frontend/src/components/ApprovalsBadge.tsx`
- Create: `frontend/src/app/(buyer)/catalog/page.tsx`
- Create: `frontend/src/app/(buyer)/catalog/CatalogList.tsx`, `frontend/src/app/(buyer)/catalog/CatalogList.test.tsx`
- Create: `frontend/src/app/api/runs/mine/route.ts`

**Interfaces:**
- Consumes: `backend`, `CatalogListingResponse`, `RunSummaryResponse` (Task 8).
- Produces: the buyer nav rail (catalog, runs, billing, with a polling approvals badge); `GET /api/runs/mine[?status=]` route handler proxying `GET /api/v1/runs/mine` — the one Route Handler both `ApprovalsBadge` (this task) and Task 18's run console poll through, per spec §3.

- [ ] **Step 1: Write the failing test**

`frontend/src/app/(buyer)/catalog/CatalogList.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { CatalogList } from "./CatalogList";
import type { CatalogListingResponse } from "@/lib/types";

const listings: CatalogListingResponse[] = [
  { agentId: "a1", agentName: "GSTR-2B Reconciler", category: "GST & TDS", versionId: "v1", versionNumber: "4.2" },
];

describe("CatalogList", () => {
  it("links each listing to its agent detail page", () => {
    render(<CatalogList listings={listings} />);
    const link = screen.getByRole("link", { name: /GSTR-2B Reconciler/i });
    expect(link).toHaveAttribute("href", "/agents/a1/versions/v1");
  });

  it("shows a message when there are no live agents yet", () => {
    render(<CatalogList listings={[]} />);
    expect(screen.getByText(/No agents are live yet/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- CatalogList.test.tsx`
Expected: FAIL — `./CatalogList` does not exist.

- [ ] **Step 3: Write minimal implementation**

`frontend/src/app/api/runs/mine/route.ts`:

```ts
import { NextRequest, NextResponse } from "next/server";
import { backend, BackendError } from "@/lib/backend";
import type { RunSummaryResponse } from "@/lib/types";

export async function GET(request: NextRequest) {
  const status = request.nextUrl.searchParams.get("status");
  const path = status ? `/api/v1/runs/mine?status=${status}` : "/api/v1/runs/mine";
  try {
    const runs = await backend.get<RunSummaryResponse[]>(path);
    return NextResponse.json(runs);
  } catch (e) {
    if (e instanceof BackendError) return NextResponse.json(e.body, { status: e.status });
    throw e;
  }
}
```

`frontend/src/components/ApprovalsBadge.tsx`:

```tsx
"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import type { RunSummaryResponse } from "@/lib/types";

export function ApprovalsBadge() {
  const { data } = useQuery({
    queryKey: ["runs", "awaiting-approval"],
    refetchInterval: 10000,
    queryFn: async () => {
      const res = await fetch("/api/runs/mine?status=AWAITING_APPROVAL");
      if (!res.ok) throw new Error("Failed to load approvals");
      return (await res.json()) as RunSummaryResponse[];
    },
  });
  const count = data?.length ?? 0;
  return (
    <Link href="/runs" className="flex justify-between px-3 py-2 rounded-md text-ink2 hover:bg-paper2">
      <span>Approvals</span>
      {count > 0 && <span className="text-xs text-muted">{count} waiting</span>}
    </Link>
  );
}
```

`frontend/src/app/(buyer)/layout.tsx`:

```tsx
import Link from "next/link";
import { ApprovalsBadge } from "@/components/ApprovalsBadge";

export default function BuyerLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[220px_1fr] min-h-screen">
      <aside className="border-r border-line p-5 flex flex-col gap-6">
        <span className="font-serif text-2xl">Karya</span>
        <nav className="flex flex-col gap-1">
          <Link href="/catalog" className="px-3 py-2 rounded-md text-ink2 hover:bg-paper2">Find an agent</Link>
          <Link href="/runs" className="px-3 py-2 rounded-md text-ink2 hover:bg-paper2">Runs</Link>
          <ApprovalsBadge />
          <Link href="/billing" className="px-3 py-2 rounded-md text-ink2 hover:bg-paper2">Billing</Link>
        </nav>
      </aside>
      <main className="p-8 max-w-5xl">{children}</main>
    </div>
  );
}
```

`frontend/src/app/(buyer)/catalog/CatalogList.tsx`:

```tsx
import Link from "next/link";
import type { CatalogListingResponse } from "@/lib/types";

export function CatalogList({ listings }: { listings: CatalogListingResponse[] }) {
  if (listings.length === 0) return <p className="text-muted py-8">No agents are live yet.</p>;
  return (
    <div className="border-t border-line">
      {listings.map((listing) => (
        <Link
          key={listing.versionId}
          href={`/agents/${listing.agentId}/versions/${listing.versionId}`}
          className="grid grid-cols-[1.6fr_1fr_auto] gap-4 items-center py-4 border-b border-line hover:bg-paper2"
        >
          <div>
            <div className="font-semibold">{listing.agentName}</div>
            <div className="text-sm text-ink2">{listing.category}</div>
          </div>
          <div className="text-sm text-ink2">v{listing.versionNumber}</div>
          <span className="text-sm font-medium">View</span>
        </Link>
      ))}
    </div>
  );
}
```

`frontend/src/app/(buyer)/catalog/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { CatalogListingResponse } from "@/lib/types";
import { CatalogList } from "./CatalogList";

export default async function CatalogPage() {
  const listings = await backend.get<CatalogListingResponse[]>("/api/v1/catalog");
  return (
    <div>
      <h1 className="font-serif text-2xl mb-2">Agents for finance work</h1>
      <p className="text-ink2 mb-6 max-w-2xl">
        Every agent here runs inside your workspace, touches only what its statement says it can.
      </p>
      <CatalogList listings={listings} />
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- CatalogList.test.tsx`
Expected: PASS

- [ ] **Step 5: Manual verification**

Run: `cd frontend && npm run dev`, sign in as a buyer, confirm `/catalog` lists LIVE agents from the running backend and the "Approvals" nav item shows a live count.

- [ ] **Step 6: Commit**

```bash
git add frontend
git commit -m "feat(frontend): add buyer layout and catalog page"
```

---

### Task 14: Agent detail page

**Files:**
- Create: `frontend/src/app/(buyer)/agents/[agentId]/versions/[versionId]/page.tsx`
- Create: `frontend/src/app/(buyer)/agents/[agentId]/versions/[versionId]/AgentDetailView.tsx`, `.../AgentDetailView.test.tsx`

**Interfaces:**
- Consumes: `backend`, `AgentVersionDetailResponse` (Task 8), `PermissionStatementDoc` (Task 11).
- Produces: the buyer-facing manifest listing view, reading `GET /api/v1/agents/{agentId}/versions/{versionId}` (Phase 0 Task 1). Links to Task 16's connect flow at `/connect/{agentId}/{versionId}`.

- [ ] **Step 1: Write the failing test**

`frontend/src/app/(buyer)/agents/[agentId]/versions/[versionId]/AgentDetailView.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AgentDetailView } from "./AgentDetailView";
import type { AgentVersionDetailResponse } from "@/lib/types";

const version: AgentVersionDetailResponse = {
  id: "v1",
  agentId: "a1",
  agentName: "GSTR-2B Reconciler",
  versionNumber: "4.2",
  status: "LIVE",
  manifest: {
    name: "GSTR-2B Reconciler",
    version: "4.2",
    scopes: [{ type: "READ", resource: "purchase_register", description: "Tally purchase register" }],
    networkAllowlist: ["gst.gov.in"],
    costEnvelope: { currency: "INR", maxPerRun: 25000, pricingUnit: "OUTCOME", unitPrice: 18, unitLabel: "matched_invoice" },
    approvalGates: [],
    dataClasses: [],
  },
};

describe("AgentDetailView", () => {
  it("links to the connect flow for this exact agent and version", () => {
    render(<AgentDetailView version={version} />);
    expect(screen.getByRole("link", { name: /connect this agent/i })).toHaveAttribute("href", "/connect/a1/v1");
  });

  it("shows the agent name and version number as a heading", () => {
    render(<AgentDetailView version={version} />);
    expect(screen.getByRole("heading", { name: "GSTR-2B Reconciler" })).toBeInTheDocument();
    expect(screen.getByText(/4\.2/)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- AgentDetailView.test.tsx`
Expected: FAIL — `./AgentDetailView` does not exist.

- [ ] **Step 3: Write minimal implementation**

`frontend/src/app/(buyer)/agents/[agentId]/versions/[versionId]/AgentDetailView.tsx`:

```tsx
import Link from "next/link";
import { PermissionStatementDoc } from "@/components/PermissionStatementDoc";
import type { AgentVersionDetailResponse } from "@/lib/types";

export function AgentDetailView({ version }: { version: AgentVersionDetailResponse }) {
  return (
    <div className="grid grid-cols-[1fr_380px] gap-8 items-start">
      <div>
        <h1 className="font-serif text-2xl mb-2">{version.agentName}</h1>
        <p className="text-ink2 mb-6">Version {version.versionNumber}</p>
      </div>
      <div>
        <PermissionStatementDoc manifest={version.manifest} mode="listing" />
        <Link
          href={`/connect/${version.agentId}/${version.id}`}
          className="block mt-4 text-center bg-green text-white rounded-lg py-2 font-medium"
        >
          Connect this agent
        </Link>
      </div>
    </div>
  );
}
```

`frontend/src/app/(buyer)/agents/[agentId]/versions/[versionId]/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { AgentVersionDetailResponse } from "@/lib/types";
import { AgentDetailView } from "./AgentDetailView";

export default async function AgentDetailPage({
  params,
}: {
  params: Promise<{ agentId: string; versionId: string }>;
}) {
  const { agentId, versionId } = await params;
  const version = await backend.get<AgentVersionDetailResponse>(`/api/v1/agents/${agentId}/versions/${versionId}`);
  return <AgentDetailView version={version} />;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- AgentDetailView.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): add buyer agent detail page"
```

---

### Task 15: Connect flow wizard

**Files:**
- Create: `frontend/src/app/(buyer)/connect/[agentId]/[versionId]/page.tsx`
- Create: `frontend/src/app/(buyer)/connect/[agentId]/[versionId]/ConnectWizard.tsx`, `.../ConnectWizard.test.tsx`
- Create: `frontend/src/app/(buyer)/connect/[agentId]/[versionId]/actions.ts`

**Interfaces:**
- Consumes: `backend`, `Manifest`, `ConnectionResponse` (Task 8), `PermissionStatementDoc` (Task 11).
- Produces: a 3-step wizard (grant → spend cap → confirm) — collapsed from the mockup's 6 steps per spec §1 (no Tally connector, no dry-run test step, no separate "source" step since the backend's `Connection` entity has no field for it). Calls `POST /api/v1/connections`. On success, redirects to `/runs?connected={connectionId}`.

- [ ] **Step 1: Write the failing test**

`frontend/src/app/(buyer)/connect/[agentId]/[versionId]/ConnectWizard.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ConnectWizard } from "./ConnectWizard";
import type { Manifest } from "@/lib/types";

vi.mock("./actions", () => ({
  createConnectionAction: vi.fn().mockResolvedValue({ ok: true, connectionId: "c1" }),
}));
const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));

const manifest: Manifest = {
  name: "GSTR-2B Reconciler",
  version: "4.2",
  scopes: [{ type: "READ", resource: "purchase_register", description: "Tally purchase register" }],
  networkAllowlist: [],
  costEnvelope: { currency: "INR", maxPerRun: 25000, pricingUnit: "OUTCOME", unitPrice: 18, unitLabel: "matched_invoice" },
  approvalGates: [],
  dataClasses: [],
};

describe("ConnectWizard", () => {
  it("walks from grant to spend cap to confirm", async () => {
    render(<ConnectWizard agentId="a1" manifest={manifest} />);
    expect(screen.getByRole("heading", { name: /Grant exactly what/i })).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    expect(screen.getByRole("heading", { name: /Set a monthly spend limit/i })).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    expect(screen.getByRole("heading", { name: /Confirm connection/i })).toBeInTheDocument();
  });

  it("submits the granted resources and spend cap, then redirects on success", async () => {
    const { createConnectionAction } = await import("./actions");
    render(<ConnectWizard agentId="a1" manifest={manifest} />);
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    await userEvent.click(screen.getByRole("button", { name: /Confirm/i }));

    expect(createConnectionAction).toHaveBeenCalledWith({
      agentId: "a1",
      grantedScopes: ["purchase_register"],
      spendCap: 25000,
    });
    expect(push).toHaveBeenCalledWith("/runs?connected=c1");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- ConnectWizard.test.tsx`
Expected: FAIL — `./ConnectWizard` and `./actions` do not exist.

- [ ] **Step 3: Write minimal implementation**

`frontend/src/app/(buyer)/connect/[agentId]/[versionId]/actions.ts`:

```ts
"use server";

import { backend, BackendError } from "@/lib/backend";
import type { ConnectionResponse } from "@/lib/types";

interface CreateConnectionInput {
  agentId: string;
  grantedScopes: string[];
  spendCap: number;
}

export async function createConnectionAction(input: CreateConnectionInput) {
  try {
    const connection = await backend.post<ConnectionResponse>("/api/v1/connections", input);
    return { ok: true as const, connectionId: connection.id };
  } catch (e) {
    if (e instanceof BackendError) {
      const message =
        typeof e.body === "object" && e.body && "message" in e.body
          ? String((e.body as { message: unknown }).message)
          : "Could not create connection";
      return { ok: false as const, error: message };
    }
    throw e;
  }
}
```

`frontend/src/app/(buyer)/connect/[agentId]/[versionId]/ConnectWizard.tsx`:

```tsx
"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { PermissionStatementDoc } from "@/components/PermissionStatementDoc";
import { formatInr } from "@/lib/format";
import type { Manifest } from "@/lib/types";
import { createConnectionAction } from "./actions";

type Step = "grant" | "cap" | "confirm";

export function ConnectWizard({ agentId, manifest }: { agentId: string; manifest: Manifest }) {
  const [step, setStep] = useState<Step>("grant");
  const [grantedResources, setGrantedResources] = useState<string[]>(manifest.scopes.map((s) => s.resource));
  const [spendCap, setSpendCap] = useState(String(manifest.costEnvelope.maxPerRun));
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const router = useRouter();

  async function handleConfirm() {
    setSubmitting(true);
    setError(null);
    const result = await createConnectionAction({ agentId, grantedScopes: grantedResources, spendCap: Number(spendCap) });
    setSubmitting(false);
    if (!result.ok) {
      setError(result.error);
      return;
    }
    router.push(`/runs?connected=${result.connectionId}`);
  }

  return (
    <div className="max-w-2xl mx-auto">
      {step === "grant" && (
        <>
          <h1 className="font-serif text-xl mb-2">Grant exactly what the agent declared</h1>
          <p className="text-ink2 mb-4">You can turn off anything below; you can never grant more than it declared.</p>
          <PermissionStatementDoc manifest={manifest} mode="grant" onGrantChange={setGrantedResources} />
          <button className="mt-6 bg-green text-white rounded-lg px-5 py-2 font-medium" onClick={() => setStep("cap")}>
            Continue
          </button>
        </>
      )}
      {step === "cap" && (
        <>
          <h1 className="font-serif text-xl mb-2">Set a monthly spend limit</h1>
          <p className="text-ink2 mb-4">
            The agent's own declared per-run cap is {formatInr(manifest.costEnvelope.maxPerRun)} — a reasonable ceiling to start from.
          </p>
          <input
            type="number"
            value={spendCap}
            onChange={(e) => setSpendCap(e.target.value)}
            className="border border-line rounded-lg px-3 py-2 w-full"
            min={0}
          />
          <div className="flex justify-between mt-6">
            <button onClick={() => setStep("grant")} className="text-ink2">Back</button>
            <button className="bg-green text-white rounded-lg px-5 py-2 font-medium" onClick={() => setStep("confirm")}>
              Continue
            </button>
          </div>
        </>
      )}
      {step === "confirm" && (
        <>
          <h1 className="font-serif text-xl mb-2">Confirm connection</h1>
          <ul className="mb-4 text-sm text-ink2 list-disc pl-5">
            <li>Granted: {grantedResources.join(", ") || "nothing"}</li>
            <li>Spend limit: {formatInr(Number(spendCap))}</li>
          </ul>
          {error && <p className="text-red mb-4">{error}</p>}
          <div className="flex justify-between mt-6">
            <button onClick={() => setStep("cap")} className="text-ink2">Back</button>
            <button
              disabled={submitting}
              className="bg-green text-white rounded-lg px-5 py-2 font-medium disabled:opacity-50"
              onClick={handleConfirm}
            >
              {submitting ? "Connecting…" : "Confirm"}
            </button>
          </div>
        </>
      )}
    </div>
  );
}
```

`frontend/src/app/(buyer)/connect/[agentId]/[versionId]/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { AgentVersionDetailResponse } from "@/lib/types";
import { ConnectWizard } from "./ConnectWizard";

export default async function ConnectPage({
  params,
}: {
  params: Promise<{ agentId: string; versionId: string }>;
}) {
  const { agentId, versionId } = await params;
  const version = await backend.get<AgentVersionDetailResponse>(`/api/v1/agents/${agentId}/versions/${versionId}`);
  return <ConnectWizard agentId={agentId} manifest={version.manifest} />;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- ConnectWizard.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): add connect flow wizard"
```

---

### Task 16: Runs list page and trigger-run action

**Files:**
- Create: `frontend/src/app/(buyer)/runs/page.tsx`
- Create: `frontend/src/app/(buyer)/runs/RunsList.tsx`, `frontend/src/app/(buyer)/runs/RunsList.test.tsx`
- Create: `frontend/src/app/(buyer)/runs/StartRun.tsx`
- Create: `frontend/src/app/(buyer)/runs/actions.ts`

**Interfaces:**
- Consumes: `backend`, `RunSummaryResponse`, `ConnectionSummaryResponse`, `RunResponse` (Task 8), `formatInr` (Task 8), `StatusPill` (Task 10), Phase 0 Task 3's `GET /api/v1/connections/mine`.
- Produces: the runs list buyers land on after connecting (Task 15's redirect target) and from the nav, reading `GET /api/v1/runs/mine` directly server-side (distinct from Task 13's `/api/runs/mine` Route Handler, which exists only for the two client-side polling consumers) — plus `<StartRun connections={ConnectionSummaryResponse[]} />`, one form-per-connection whose `action` is `triggerRunAction` bound to that connection's id, calling `POST /api/v1/runs {connectionId}` and redirecting to Task 17's run console. This is the actual "run now" affordance the mockups' agent-detail page implies but this plan's connect-flow redirect alone doesn't provide.

- [ ] **Step 1: Write the failing test**

`frontend/src/app/(buyer)/runs/RunsList.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { RunsList } from "./RunsList";
import type { RunSummaryResponse } from "@/lib/types";

const runs: RunSummaryResponse[] = [
  { id: "r1", agentName: "GSTR-2B Reconciler", status: "AWAITING_APPROVAL", totalCharged: 16352, startedAt: "2026-09-16T09:41:00Z" },
];

describe("RunsList", () => {
  it("links each run to its console page", () => {
    render(<RunsList runs={runs} />);
    expect(screen.getByRole("link", { name: /GSTR-2B Reconciler/i })).toHaveAttribute("href", "/runs/r1");
  });

  it("shows a message when there are no runs yet", () => {
    render(<RunsList runs={[]} />);
    expect(screen.getByText(/No runs yet/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- RunsList.test.tsx`
Expected: FAIL — `./RunsList` does not exist.

- [ ] **Step 3: Write minimal implementation**

`frontend/src/app/(buyer)/runs/RunsList.tsx`:

```tsx
import Link from "next/link";
import { StatusPill } from "@/components/StatusPill";
import { formatInr } from "@/lib/format";
import type { RunSummaryResponse } from "@/lib/types";

export function RunsList({ runs }: { runs: RunSummaryResponse[] }) {
  if (runs.length === 0) return <p className="text-muted py-8">No runs yet.</p>;
  return (
    <div className="border-t border-line">
      {runs.map((run) => (
        <Link key={run.id} href={`/runs/${run.id}`} className="grid grid-cols-[1fr_auto_auto] gap-4 items-center py-4 border-b border-line hover:bg-paper2">
          <span className="font-medium">{run.agentName}</span>
          <StatusPill status={run.status} />
          <span className="text-sm">{formatInr(run.totalCharged)}</span>
        </Link>
      ))}
    </div>
  );
}
```

`frontend/src/app/(buyer)/runs/actions.ts`:

```ts
"use server";

import { redirect } from "next/navigation";
import { backend } from "@/lib/backend";
import type { RunResponse } from "@/lib/types";

export async function triggerRunAction(connectionId: string) {
  const run = await backend.post<RunResponse>("/api/v1/runs", { connectionId });
  redirect(`/runs/${run.id}`);
}
```

`frontend/src/app/(buyer)/runs/StartRun.tsx`:

```tsx
import type { ConnectionSummaryResponse } from "@/lib/types";
import { triggerRunAction } from "./actions";

export function StartRun({ connections }: { connections: ConnectionSummaryResponse[] }) {
  const active = connections.filter((c) => c.status === "ACTIVE");
  if (active.length === 0) return null;
  return (
    <div className="mb-6 border border-line rounded-lg p-4">
      <h2 className="font-semibold text-sm mb-3">Start a run</h2>
      <div className="flex flex-col gap-2">
        {active.map((c) => (
          <form key={c.id} action={triggerRunAction.bind(null, c.id)}>
            <button type="submit" className="flex justify-between w-full text-left px-3 py-2 border border-line rounded-md hover:bg-paper2">
              <span>{c.agentName}</span>
              <span className="text-ink2 text-sm">Run now</span>
            </button>
          </form>
        ))}
      </div>
    </div>
  );
}
```

`frontend/src/app/(buyer)/runs/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { ConnectionSummaryResponse, RunSummaryResponse } from "@/lib/types";
import { RunsList } from "./RunsList";
import { StartRun } from "./StartRun";

export default async function RunsPage() {
  const [runs, connections] = await Promise.all([
    backend.get<RunSummaryResponse[]>("/api/v1/runs/mine"),
    backend.get<ConnectionSummaryResponse[]>("/api/v1/connections/mine"),
  ]);
  return (
    <div>
      <h1 className="font-serif text-2xl mb-6">Runs</h1>
      <StartRun connections={connections} />
      <RunsList runs={runs} />
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- RunsList.test.tsx`
Expected: PASS

- [ ] **Step 5: Manual verification**

Run: `cd frontend && npm run dev`, sign in as a buyer with at least one active connection, confirm "Start a run" lists it and clicking it creates a run and lands on that run's console.

- [ ] **Step 6: Commit**

```bash
git add frontend
git commit -m "feat(frontend): add runs list page and trigger-run action"
```

---

### Task 17: Run console page

**Files:**
- Create: `frontend/src/app/api/runs/[runId]/route.ts`
- Create: `frontend/src/app/(buyer)/runs/[runId]/page.tsx`
- Create: `frontend/src/app/(buyer)/runs/[runId]/RunConsole.tsx`, `.../RunConsole.test.tsx`
- Create: `frontend/src/app/(buyer)/runs/[runId]/actions.ts`

**Interfaces:**
- Consumes: `backend`, `RunDetailResponse`, `RunResponse` (Task 8), `useRunPolling`, `RunTraceStep` (Task 12).
- Produces: the live run trace with approve/reject controls and the cost-cap/rejected terminal-state labels from spec §6. `GET /api/runs/{runId}` Route Handler proxying `GET /api/v1/runs/{runId}` — the polling target for `useRunPolling`.

- [ ] **Step 1: Write the failing test**

`frontend/src/app/(buyer)/runs/[runId]/RunConsole.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { RunConsole } from "./RunConsole";
import type { RunDetailResponse } from "@/lib/types";

vi.mock("./actions", () => ({
  approveRunAction: vi.fn().mockResolvedValue(undefined),
  rejectRunAction: vi.fn().mockResolvedValue(undefined),
}));

const awaitingRun: RunDetailResponse = {
  id: "r1",
  status: "AWAITING_APPROVAL",
  costCap: 18000,
  totalCharged: 16352,
  steps: [{ kind: "GATE", detail: '{"summary":"Crosses your threshold"}', createdAt: "2026-09-16T09:58:12Z" }],
};

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient();
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

describe("RunConsole", () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => awaitingRun });
  });

  it("shows approve/reject controls when a run is awaiting approval", () => {
    renderWithClient(<RunConsole runId="r1" initial={awaitingRun} />);
    expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reject" })).toBeInTheDocument();
  });

  it("calls approveRunAction when Approve is clicked", async () => {
    const { approveRunAction } = await import("./actions");
    renderWithClient(<RunConsole runId="r1" initial={awaitingRun} />);
    await userEvent.click(screen.getByRole("button", { name: "Approve" }));
    expect(approveRunAction).toHaveBeenCalledWith("r1");
  });

  it("labels a cost-cap failure as a deliberate stop, not a generic error", () => {
    const failed: RunDetailResponse = { ...awaitingRun, status: "FAILED" };
    renderWithClient(<RunConsole runId="r1" initial={failed} />);
    expect(screen.getByText("Stopped — spend limit reached")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- RunConsole.test.tsx`
Expected: FAIL — `./RunConsole` and `./actions` do not exist.

- [ ] **Step 3: Write minimal implementation**

`frontend/src/app/api/runs/[runId]/route.ts`:

```ts
import { NextRequest, NextResponse } from "next/server";
import { backend, BackendError } from "@/lib/backend";
import type { RunDetailResponse } from "@/lib/types";

export async function GET(_request: NextRequest, { params }: { params: Promise<{ runId: string }> }) {
  const { runId } = await params;
  try {
    const run = await backend.get<RunDetailResponse>(`/api/v1/runs/${runId}`);
    return NextResponse.json(run);
  } catch (e) {
    if (e instanceof BackendError) return NextResponse.json(e.body, { status: e.status });
    throw e;
  }
}
```

`frontend/src/app/(buyer)/runs/[runId]/actions.ts`:

```ts
"use server";

import { revalidatePath } from "next/cache";
import { backend } from "@/lib/backend";
import type { RunResponse } from "@/lib/types";

export async function approveRunAction(runId: string) {
  await backend.post<RunResponse>(`/api/v1/runs/${runId}/approve`, { notes: null });
  revalidatePath(`/runs/${runId}`);
}

export async function rejectRunAction(runId: string) {
  await backend.post<RunResponse>(`/api/v1/runs/${runId}/reject`, { notes: null });
  revalidatePath(`/runs/${runId}`);
}
```

`frontend/src/app/(buyer)/runs/[runId]/RunConsole.tsx`:

```tsx
"use client";

import { useState } from "react";
import { useRunPolling } from "@/components/useRunPolling";
import { RunTraceStep } from "@/components/RunTraceStep";
import { formatInr } from "@/lib/format";
import type { RunDetailResponse } from "@/lib/types";
import { approveRunAction, rejectRunAction } from "./actions";

const TERMINAL_LABEL: Partial<Record<RunDetailResponse["status"], string>> = {
  FAILED: "Stopped — spend limit reached",
  CANCELLED: "Rejected",
  COMPLETED: "Completed",
};

export function RunConsole({ runId, initial }: { runId: string; initial: RunDetailResponse }) {
  const { data } = useRunPolling(runId, initial);
  const [deciding, setDeciding] = useState(false);
  const run = data ?? initial;

  async function decide(approve: boolean) {
    setDeciding(true);
    if (approve) await approveRunAction(runId);
    else await rejectRunAction(runId);
    setDeciding(false);
  }

  return (
    <div className="grid grid-cols-[1fr_320px] gap-8 items-start">
      <div>
        <h1 className="font-serif text-2xl mb-1">Run {runId.slice(0, 8)}</h1>
        <p className="text-sm text-ink2 mb-6">{TERMINAL_LABEL[run.status] ?? run.status}</p>
        <div>
          {run.steps.map((step, i) => (
            <RunTraceStep key={i} step={step} />
          ))}
        </div>
        {run.status === "AWAITING_APPROVAL" && (
          <div className="mt-4 border border-amber bg-amberSoft rounded-lg p-4">
            <p className="mb-3 text-sm">This run needs your decision before it can continue.</p>
            <button disabled={deciding} onClick={() => decide(true)} className="bg-green text-white rounded-md px-4 py-2 mr-2">
              Approve
            </button>
            <button disabled={deciding} onClick={() => decide(false)} className="border border-line rounded-md px-4 py-2">
              Reject
            </button>
          </div>
        )}
      </div>
      <div className="border border-line rounded-lg p-4">
        <h4 className="font-semibold mb-3 text-sm">Spend on this run</h4>
        <div className="flex justify-between text-sm py-1">
          <span>Charged so far</span>
          <span>{formatInr(run.totalCharged)}</span>
        </div>
        <div className="flex justify-between text-sm py-1">
          <span>Limit for this run</span>
          <span>{formatInr(run.costCap)}</span>
        </div>
      </div>
    </div>
  );
}
```

`frontend/src/app/(buyer)/runs/[runId]/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { RunDetailResponse } from "@/lib/types";
import { RunConsole } from "./RunConsole";

export default async function RunPage({ params }: { params: Promise<{ runId: string }> }) {
  const { runId } = await params;
  const initial = await backend.get<RunDetailResponse>(`/api/v1/runs/${runId}`);
  return <RunConsole runId={runId} initial={initial} />;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- RunConsole.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): add run console with polling and approval gate"
```

---

### Task 18: Billing page

**Files:**
- Create: `frontend/src/lib/statement.ts`, `frontend/src/lib/statement.test.ts`
- Create: `frontend/src/app/(buyer)/billing/page.tsx`

**Interfaces:**
- Consumes: `backend`, `getSession`, `StatementLineResponse`, `WalletResponse`, `formatInr` (Task 8), Phase 0 Task 6's wallet endpoint.
- Produces: `groupStatementByAgent(lines: StatementLineResponse[]): {agentName, total}[]` (pure, reversals subtracted) plus the billing page reading `GET /api/v1/orgs/{orgId}/wallet` and `GET /api/v1/orgs/{orgId}/statement` in parallel.

- [ ] **Step 1: Write the failing test**

`frontend/src/lib/statement.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { groupStatementByAgent } from "./statement";
import type { StatementLineResponse } from "./types";

describe("groupStatementByAgent", () => {
  it("sums charges per agent and subtracts reversals", () => {
    const lines: StatementLineResponse[] = [
      { runId: "1", agentName: "GSTR-2B Reconciler", amount: 16352, type: "CHARGE", createdAt: "" },
      { runId: "2", agentName: "GSTR-2B Reconciler", amount: 340, type: "REVERSAL", createdAt: "" },
      { runId: "3", agentName: "Bank Statement Matcher", amount: 2400, type: "CHARGE", createdAt: "" },
    ];
    expect(groupStatementByAgent(lines)).toEqual([
      { agentName: "GSTR-2B Reconciler", total: 16012 },
      { agentName: "Bank Statement Matcher", total: 2400 },
    ]);
  });

  it("returns an empty list for no lines", () => {
    expect(groupStatementByAgent([])).toEqual([]);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- statement.test.ts`
Expected: FAIL — `./statement` does not exist.

- [ ] **Step 3: Write minimal implementation**

`frontend/src/lib/statement.ts`:

```ts
import type { StatementLineResponse } from "./types";

export interface AgentTotal {
  agentName: string;
  total: number;
}

export function groupStatementByAgent(lines: StatementLineResponse[]): AgentTotal[] {
  const totals = new Map<string, number>();
  for (const line of lines) {
    const signedAmount = line.type === "REVERSAL" ? -line.amount : line.amount;
    totals.set(line.agentName, (totals.get(line.agentName) ?? 0) + signedAmount);
  }
  return [...totals.entries()].map(([agentName, total]) => ({ agentName, total }));
}
```

`frontend/src/app/(buyer)/billing/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import { getSession } from "@/lib/session";
import { formatInr } from "@/lib/format";
import { groupStatementByAgent } from "@/lib/statement";
import type { StatementLineResponse, WalletResponse } from "@/lib/types";

export default async function BillingPage() {
  const session = await getSession();
  if (!session) return null;

  const [wallet, lines] = await Promise.all([
    backend.get<WalletResponse>(`/api/v1/orgs/${session.orgId}/wallet`),
    backend.get<StatementLineResponse[]>(`/api/v1/orgs/${session.orgId}/statement`),
  ]);
  const groups = groupStatementByAgent(lines);

  return (
    <div className="grid grid-cols-[1fr_360px] gap-8 items-start">
      <div>
        <h1 className="font-serif text-2xl mb-6">Billing</h1>
        <div className="border border-line rounded-lg p-5 mb-6">
          <div className="font-serif text-3xl">{formatInr(wallet.balance)}</div>
          <div className="text-sm text-ink2 mt-1">
            Available{wallet.cap ? ` · monthly cap ${formatInr(wallet.cap)}` : ""}
          </div>
        </div>
        <h2 className="font-semibold text-sm mb-3">This month, by agent</h2>
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-muted text-xs">
              <th className="pb-2">Agent</th>
              <th className="pb-2 text-right">Charged</th>
            </tr>
          </thead>
          <tbody>
            {groups.map((g) => (
              <tr key={g.agentName} className="border-t border-line">
                <td className="py-2">{g.agentName}</td>
                <td className="py-2 text-right">{formatInr(g.total)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <aside className="bg-doc border border-line rounded p-6">
        <h3 className="font-serif text-lg mb-4">Statement</h3>
        {lines.map((line, i) => (
          <div key={i} className="flex justify-between text-sm py-2 border-b border-dotted border-line">
            <span>{line.agentName} · {line.type.toLowerCase()}</span>
            <span>{formatInr(line.amount)}</span>
          </div>
        ))}
      </aside>
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- statement.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): add billing page with wallet and grouped statement"
```

---

## Phase 4 — Developer console

Developer routes live entirely under `frontend/src/app/(developer)/dashboard/`, gated by Task 9's middleware to `DEVELOPER` — a distinct prefix from the buyer's `/agents/...` namespace so the two role-based route trees never collide (Global Constraints).

### Task 19: Developer layout and dashboard

**Files:**
- Create: `frontend/src/app/(developer)/layout.tsx`
- Create: `frontend/src/app/(developer)/dashboard/page.tsx`
- Create: `frontend/src/app/(developer)/dashboard/DashboardList.tsx`, `.../DashboardList.test.tsx`

**Interfaces:**
- Consumes: `backend`, `MyAgentsResponse` (Task 8), `StatusPill` (Task 10), Phase 0 Task 2's `GET /api/v1/agents/mine`.
- Produces: the developer nav rail and the agent+version listing every later developer task links from. A version's status determines its link target: `DRAFT` → `/dashboard/agents/{agentId}/versions/new` (resume), `IN_REVIEW` → `.../status`, `LIVE`/`REJECTED` → `/dashboard/agents/{agentId}` (manage).

- [ ] **Step 1: Write the failing test**

`frontend/src/app/(developer)/dashboard/DashboardList.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DashboardList } from "./DashboardList";
import type { MyAgentsResponse } from "@/lib/types";

const agents: MyAgentsResponse[] = [
  {
    agentId: "a1",
    agentName: "GSTR-2B Reconciler",
    category: "GST & TDS",
    versions: [
      { id: "v1", versionNumber: "4.2", status: "LIVE" },
      { id: "v2", versionNumber: "5.0", status: "IN_REVIEW" },
    ],
  },
];

describe("DashboardList", () => {
  it("links an in-review version to its status page", () => {
    render(<DashboardList agents={agents} />);
    expect(screen.getByRole("link", { name: /5\.0/i })).toHaveAttribute("href", "/dashboard/agents/a1/versions/v2/status");
  });

  it("links a live version to the manage page", () => {
    render(<DashboardList agents={agents} />);
    expect(screen.getByRole("link", { name: /4\.2/i })).toHaveAttribute("href", "/dashboard/agents/a1");
  });

  it("shows a message when there are no agents yet", () => {
    render(<DashboardList agents={[]} />);
    expect(screen.getByText(/haven't submitted any agents yet/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- DashboardList.test.tsx`
Expected: FAIL — `./DashboardList` does not exist.

- [ ] **Step 3: Write minimal implementation**

`frontend/src/app/(developer)/layout.tsx`:

```tsx
import Link from "next/link";

export default function DeveloperLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[230px_1fr] min-h-screen">
      <aside className="border-r border-line p-5 flex flex-col gap-6">
        <span className="font-serif text-2xl">Karya <span className="text-xs text-muted border border-line rounded px-1.5 py-0.5 ml-1">for developers</span></span>
        <nav className="flex flex-col gap-1">
          <Link href="/dashboard" className="px-3 py-2 rounded-md text-ink2 hover:bg-paper2">Your agents</Link>
          <Link href="/dashboard/new" className="px-3 py-2 rounded-md text-ink2 hover:bg-paper2">New submission</Link>
        </nav>
      </aside>
      <main className="p-8 max-w-5xl">{children}</main>
    </div>
  );
}
```

`frontend/src/app/(developer)/dashboard/DashboardList.tsx`:

```tsx
import Link from "next/link";
import { StatusPill } from "@/components/StatusPill";
import type { AgentVersionResponse, MyAgentsResponse } from "@/lib/types";

function linkFor(agentId: string, version: AgentVersionResponse): string {
  if (version.status === "IN_REVIEW") return `/dashboard/agents/${agentId}/versions/${version.id}/status`;
  if (version.status === "DRAFT") return `/dashboard/agents/${agentId}/versions/new`;
  return `/dashboard/agents/${agentId}`;
}

export function DashboardList({ agents }: { agents: MyAgentsResponse[] }) {
  if (agents.length === 0) return <p className="text-muted py-8">You haven't submitted any agents yet.</p>;
  return (
    <div className="border-t border-line">
      {agents.map((agent) =>
        agent.versions.map((version) => (
          <Link
            key={version.id}
            href={linkFor(agent.agentId, version)}
            className="grid grid-cols-[1.6fr_1fr_auto] gap-4 items-center py-4 border-b border-line hover:bg-paper2"
          >
            <div>
              <div className="font-semibold">{agent.agentName}</div>
              <div className="text-sm text-ink2">v{version.versionNumber} · {agent.category}</div>
            </div>
            <StatusPill status={version.status} />
            <span className="text-sm font-medium">
              {version.status === "IN_REVIEW" ? "View status" : version.status === "DRAFT" ? "Continue" : "Manage"}
            </span>
          </Link>
        )),
      )}
    </div>
  );
}
```

`frontend/src/app/(developer)/dashboard/page.tsx`:

```tsx
import Link from "next/link";
import { backend } from "@/lib/backend";
import type { MyAgentsResponse } from "@/lib/types";
import { DashboardList } from "./DashboardList";

export default async function DashboardPage() {
  const agents = await backend.get<MyAgentsResponse[]>("/api/v1/agents/mine");
  return (
    <div>
      <div className="flex justify-between items-center mb-6">
        <h1 className="font-serif text-2xl">Your agents</h1>
        <Link href="/dashboard/new" className="bg-green text-white rounded-lg px-4 py-2 font-medium text-sm">
          Submit a new agent
        </Link>
      </div>
      <DashboardList agents={agents} />
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- DashboardList.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): add developer layout and dashboard"
```

---

### Task 20: Submission wizard

**Files:**
- Create: `frontend/src/lib/manifestDraft.ts`, `frontend/src/lib/manifestDraft.test.ts`
- Create: `frontend/src/components/SubmissionWizard.tsx`, `frontend/src/components/SubmissionWizard.test.tsx`
- Create: `frontend/src/components/submissionActions.ts`
- Create: `frontend/src/app/(developer)/dashboard/new/page.tsx`
- Create: `frontend/src/app/(developer)/dashboard/agents/[agentId]/versions/new/page.tsx`

**Interfaces:**
- Consumes: `backend`, `Manifest`, `ManifestDraft`, `Scope`, `ApprovalGateDecl`, `AgentResponse`, `AgentVersionResponse`, `MyAgentsResponse` (Task 8), `WizardRail`, `PermissionStatementDoc` (Tasks 10–11).
- Produces: `toParsedManifest(draft: ManifestDraft): Manifest` (adapts the raw wire-format draft — nested `network.allowlist`, extra `modelRequirements` — into the flat `Manifest` shape `PermissionStatementDoc`'s `mode="preview"` expects; see Global Constraints). One `<SubmissionWizard existingAgent?={{id, name, category}} />` used by both a brand-new-agent route (`/dashboard/new`) and an additional-version-for-an-existing-agent route. Calls, in order on final submit: `POST /api/v1/agents` (skipped if `existingAgent` is given), `POST /api/v1/agents/{agentId}/versions`, `POST /api/v1/agents/{agentId}/versions/{versionId}/submit` — then redirects to Task 21's status page.

- [ ] **Step 1: Write the failing tests**

`frontend/src/lib/manifestDraft.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { emptyManifestDraft, toParsedManifest } from "./manifestDraft";

describe("toParsedManifest", () => {
  it("flattens network.allowlist into networkAllowlist for the preview renderer", () => {
    const draft = emptyManifestDraft("Test Agent");
    draft.network.allowlist = ["gst.gov.in"];
    const manifest = toParsedManifest(draft);
    expect(manifest.networkAllowlist).toEqual(["gst.gov.in"]);
    expect(manifest.name).toBe("Test Agent");
  });
});
```

`frontend/src/components/SubmissionWizard.test.tsx`:

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

  it("creates the agent, then the version, then submits for review, then redirects", async () => {
    const { createAgentAction, createVersionAction, submitVersionAction } = await import("./submissionActions");
    render(<SubmissionWizard />);
    await userEvent.type(screen.getByPlaceholderText("Agent name"), "Test Agent");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    await userEvent.click(screen.getByRole("button", { name: "Submit for review" }));

    expect(createAgentAction).toHaveBeenCalled();
    expect(createVersionAction).toHaveBeenCalledWith("a1", expect.objectContaining({ versionNumber: "1.0.0" }));
    expect(submitVersionAction).toHaveBeenCalledWith("a1", "v1");
    expect(push).toHaveBeenCalledWith("/dashboard/agents/a1/versions/v1/status");
  });

  it("skips agent creation when an existing agent is provided", async () => {
    const { createAgentAction } = await import("./submissionActions");
    render(<SubmissionWizard existingAgent={{ id: "existing-1", name: "Existing Agent", category: "GST & TDS" }} />);
    expect(screen.queryByPlaceholderText("Agent name")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    await userEvent.click(screen.getByRole("button", { name: "Submit for review" }));
    expect(createAgentAction).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npm test -- manifestDraft.test.ts SubmissionWizard.test.tsx`
Expected: FAIL — none of the modules exist.

- [ ] **Step 3: Write minimal implementation**

`frontend/src/lib/manifestDraft.ts`:

```ts
import type { Manifest, ManifestDraft } from "./types";

export function emptyManifestDraft(agentName: string): ManifestDraft {
  return {
    name: agentName,
    version: "1.0.0",
    scopes: [],
    network: { allowlist: [] },
    costEnvelope: { currency: "INR", maxPerRun: 0, pricingUnit: "OUTCOME", unitPrice: 0, unitLabel: "" },
    modelRequirements: { provider: "anthropic", model: "claude-sonnet-5" },
    approvalGates: [],
    dataClasses: [],
  };
}

export function toParsedManifest(draft: ManifestDraft): Manifest {
  return {
    name: draft.name,
    version: draft.version,
    scopes: draft.scopes,
    networkAllowlist: draft.network.allowlist,
    costEnvelope: draft.costEnvelope,
    approvalGates: draft.approvalGates,
    dataClasses: draft.dataClasses,
  };
}
```

`frontend/src/components/submissionActions.ts`:

```ts
"use server";

import { backend, BackendError } from "@/lib/backend";
import type { AgentResponse, AgentVersionResponse } from "@/lib/types";

function errorMessage(e: unknown, fallback: string): string {
  if (e instanceof BackendError) {
    if (Array.isArray(e.body)) return e.body.join("; ");
    if (typeof e.body === "object" && e.body && "message" in e.body) return String((e.body as { message: unknown }).message);
  }
  return fallback;
}

export async function createAgentAction(input: { name: string; category: string; description: string }) {
  try {
    const agent = await backend.post<AgentResponse>("/api/v1/agents", input);
    return { ok: true as const, agentId: agent.id };
  } catch (e) {
    return { ok: false as const, error: errorMessage(e, "Could not create agent") };
  }
}

export async function createVersionAction(
  agentId: string,
  input: { versionNumber: string; manifestJson: string; imageRef: string; changelog: string },
) {
  try {
    const version = await backend.post<AgentVersionResponse>(`/api/v1/agents/${agentId}/versions`, input);
    return { ok: true as const, versionId: version.id };
  } catch (e) {
    return { ok: false as const, error: errorMessage(e, "Could not create version — check the manifest fields") };
  }
}

export async function submitVersionAction(agentId: string, versionId: string) {
  try {
    await backend.post<AgentVersionResponse>(`/api/v1/agents/${agentId}/versions/${versionId}/submit`);
    return { ok: true as const };
  } catch (e) {
    return { ok: false as const, error: errorMessage(e, "Could not submit for review") };
  }
}
```

`frontend/src/components/SubmissionWizard.tsx`:

```tsx
"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { WizardRail } from "./WizardRail";
import { PermissionStatementDoc } from "./PermissionStatementDoc";
import { toParsedManifest, emptyManifestDraft } from "@/lib/manifestDraft";
import type { ApprovalGateDecl, ManifestDraft, Scope } from "@/lib/types";
import { createAgentAction, createVersionAction, submitVersionAction } from "./submissionActions";

interface ExistingAgent {
  id: string;
  name: string;
  category: string;
}

const STEPS = ["Basics", "Scopes", "Cost & limits", "Review & submit"];
const inputClass = "border border-line rounded-lg px-3 py-2 w-full mb-3";

export function SubmissionWizard({ existingAgent }: { existingAgent?: ExistingAgent }) {
  const [stepIndex, setStepIndex] = useState(0);
  const [agentName, setAgentName] = useState(existingAgent?.name ?? "");
  const [category, setCategory] = useState(existingAgent?.category ?? "");
  const [description, setDescription] = useState("");
  const [versionNumber, setVersionNumber] = useState("1.0.0");
  const [imageRef, setImageRef] = useState("");
  const [changelog, setChangelog] = useState("");
  const [draft, setDraft] = useState<ManifestDraft>(() => emptyManifestDraft(existingAgent?.name ?? ""));
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const router = useRouter();

  function addScope() {
    setDraft((d) => ({ ...d, scopes: [...d.scopes, { type: "READ", resource: "", description: "" }] }));
  }
  function updateScope(i: number, patch: Partial<Scope>) {
    setDraft((d) => ({ ...d, scopes: d.scopes.map((s, idx) => (idx === i ? { ...s, ...patch } : s)) }));
  }
  function removeScope(i: number) {
    setDraft((d) => ({ ...d, scopes: d.scopes.filter((_, idx) => idx !== i) }));
  }
  function addGate() {
    setDraft((d) => ({ ...d, approvalGates: [...d.approvalGates, { trigger: "", description: "" }] }));
  }
  function updateGate(i: number, patch: Partial<ApprovalGateDecl>) {
    setDraft((d) => ({ ...d, approvalGates: d.approvalGates.map((g, idx) => (idx === i ? { ...g, ...patch } : g)) }));
  }
  function removeGate(i: number) {
    setDraft((d) => ({ ...d, approvalGates: d.approvalGates.filter((_, idx) => idx !== i) }));
  }

  async function handleSubmit() {
    setSubmitting(true);
    setError(null);
    const finalDraft: ManifestDraft = { ...draft, name: agentName, version: versionNumber };

    let agentId = existingAgent?.id;
    if (!agentId) {
      const agentResult = await createAgentAction({ name: agentName, category, description });
      if (!agentResult.ok) {
        setSubmitting(false);
        setError(agentResult.error);
        return;
      }
      agentId = agentResult.agentId;
    }

    const versionResult = await createVersionAction(agentId, {
      versionNumber,
      manifestJson: JSON.stringify(finalDraft),
      imageRef,
      changelog,
    });
    if (!versionResult.ok) {
      setSubmitting(false);
      setError(versionResult.error);
      return;
    }

    const submitResult = await submitVersionAction(agentId, versionResult.versionId);
    setSubmitting(false);
    if (!submitResult.ok) {
      setError(submitResult.error);
      return;
    }

    router.push(`/dashboard/agents/${agentId}/versions/${versionResult.versionId}/status`);
  }

  return (
    <div>
      <WizardRail steps={STEPS} currentIndex={stepIndex} />

      {stepIndex === 0 && (
        <div>
          {!existingAgent && (
            <>
              <input placeholder="Agent name" value={agentName} onChange={(e) => setAgentName(e.target.value)} className={inputClass} />
              <input placeholder="Category" value={category} onChange={(e) => setCategory(e.target.value)} className={inputClass} />
              <textarea placeholder="One-line job description" value={description} onChange={(e) => setDescription(e.target.value)} className={inputClass} />
            </>
          )}
          <input placeholder="Version number, e.g. 1.0.0" value={versionNumber} onChange={(e) => setVersionNumber(e.target.value)} className={inputClass} />
          <input placeholder="E2B template ID" value={imageRef} onChange={(e) => setImageRef(e.target.value)} className={inputClass} />
          <textarea placeholder="What changed in this version" value={changelog} onChange={(e) => setChangelog(e.target.value)} className={inputClass} />
        </div>
      )}

      {stepIndex === 1 && (
        <div>
          <h2 className="font-semibold mb-2">Declare every scope it needs</h2>
          {draft.scopes.map((scope, i) => (
            <div key={i} className="grid grid-cols-[1fr_100px_1fr_auto] gap-2 mb-2">
              <input placeholder="resource, e.g. bank_statement" value={scope.resource} onChange={(e) => updateScope(i, { resource: e.target.value })} className="border border-line rounded px-2 py-1" />
              <select value={scope.type} onChange={(e) => updateScope(i, { type: e.target.value as Scope["type"] })} className="border border-line rounded px-2 py-1">
                <option value="READ">Read</option>
                <option value="WRITE">Write</option>
              </select>
              <input placeholder="description" value={scope.description} onChange={(e) => updateScope(i, { description: e.target.value })} className="border border-line rounded px-2 py-1" />
              <button type="button" onClick={() => removeScope(i)} className="text-red text-sm">Remove</button>
            </div>
          ))}
          <button type="button" onClick={addScope} className="border border-dashed border-line rounded px-3 py-2 text-sm w-full text-ink2 mb-4">
            + Add a scope
          </button>

          <label className="block text-sm font-medium mb-1">Network destinations (comma-separated)</label>
          <input
            value={draft.network.allowlist.join(", ")}
            onChange={(e) => setDraft((d) => ({ ...d, network: { allowlist: e.target.value.split(",").map((s) => s.trim()).filter(Boolean) } }))}
            className={inputClass}
          />

          <h2 className="font-semibold mb-2">Approval gates</h2>
          {draft.approvalGates.map((gate, i) => (
            <div key={i} className="grid grid-cols-[1fr_1fr_auto] gap-2 mb-2">
              <input placeholder="trigger, e.g. MESSAGE_CUSTOMER" value={gate.trigger} onChange={(e) => updateGate(i, { trigger: e.target.value })} className="border border-line rounded px-2 py-1" />
              <input placeholder="description" value={gate.description} onChange={(e) => updateGate(i, { description: e.target.value })} className="border border-line rounded px-2 py-1" />
              <button type="button" onClick={() => removeGate(i)} className="text-red text-sm">Remove</button>
            </div>
          ))}
          <button type="button" onClick={addGate} className="border border-dashed border-line rounded px-3 py-2 text-sm w-full text-ink2">
            + Add an approval gate
          </button>
        </div>
      )}

      {stepIndex === 2 && (
        <div>
          <h2 className="font-semibold mb-2">What can it cost a company</h2>
          <div className="grid grid-cols-2 gap-3 mb-3">
            <input
              placeholder="Max per run (INR)"
              type="number"
              value={draft.costEnvelope.maxPerRun}
              onChange={(e) => setDraft((d) => ({ ...d, costEnvelope: { ...d.costEnvelope, maxPerRun: Number(e.target.value) } }))}
              className="border border-line rounded-lg px-3 py-2"
            />
            <input
              placeholder="Unit price (INR)"
              type="number"
              value={draft.costEnvelope.unitPrice}
              onChange={(e) => setDraft((d) => ({ ...d, costEnvelope: { ...d.costEnvelope, unitPrice: Number(e.target.value) } }))}
              className="border border-line rounded-lg px-3 py-2"
            />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <select
              value={draft.costEnvelope.pricingUnit}
              onChange={(e) => setDraft((d) => ({ ...d, costEnvelope: { ...d.costEnvelope, pricingUnit: e.target.value as typeof d.costEnvelope.pricingUnit } }))}
              className="border border-line rounded-lg px-3 py-2"
            >
              <option value="OUTCOME">Per outcome</option>
              <option value="METERED">Metered</option>
              <option value="SUBSCRIPTION">Subscription</option>
            </select>
            <input
              placeholder="Unit label, e.g. matched_invoice"
              value={draft.costEnvelope.unitLabel}
              onChange={(e) => setDraft((d) => ({ ...d, costEnvelope: { ...d.costEnvelope, unitLabel: e.target.value } }))}
              className="border border-line rounded-lg px-3 py-2"
            />
          </div>
        </div>
      )}

      {stepIndex === 3 && (
        <div>
          <h2 className="font-semibold mb-4">Ready to submit</h2>
          <p className="text-ink2 mb-4">This is exactly what companies will see. Submitting starts automated review.</p>
          <PermissionStatementDoc manifest={toParsedManifest({ ...draft, name: agentName, version: versionNumber })} mode="preview" />
          {error && <p className="text-red mt-4">{error}</p>}
        </div>
      )}

      <div className="flex justify-between mt-6">
        <button type="button" disabled={stepIndex === 0} onClick={() => setStepIndex((i) => i - 1)} className="text-ink2 disabled:opacity-0">
          Back
        </button>
        {stepIndex < STEPS.length - 1 ? (
          <button type="button" onClick={() => setStepIndex((i) => i + 1)} className="bg-green text-white rounded-lg px-5 py-2 font-medium">
            Continue
          </button>
        ) : (
          <button type="button" disabled={submitting} onClick={handleSubmit} className="bg-green text-white rounded-lg px-5 py-2 font-medium disabled:opacity-50">
            {submitting ? "Submitting…" : "Submit for review"}
          </button>
        )}
      </div>
    </div>
  );
}
```

`frontend/src/app/(developer)/dashboard/new/page.tsx`:

```tsx
import { SubmissionWizard } from "@/components/SubmissionWizard";

export default function NewAgentPage() {
  return <SubmissionWizard />;
}
```

`frontend/src/app/(developer)/dashboard/agents/[agentId]/versions/new/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import { SubmissionWizard } from "@/components/SubmissionWizard";
import type { MyAgentsResponse } from "@/lib/types";

export default async function NewVersionPage({ params }: { params: Promise<{ agentId: string }> }) {
  const { agentId } = await params;
  const agents = await backend.get<MyAgentsResponse[]>("/api/v1/agents/mine");
  const agent = agents.find((a) => a.agentId === agentId);
  if (!agent) return <p className="text-red">Agent not found.</p>;
  return <SubmissionWizard existingAgent={{ id: agent.agentId, name: agent.agentName, category: agent.category }} />;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npm test -- manifestDraft.test.ts SubmissionWizard.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): add developer submission wizard"
```

---

### Task 21: Review-status view

**Files:**
- Create: `frontend/src/app/(developer)/dashboard/agents/[agentId]/versions/[versionId]/status/page.tsx`
- Create: `.../status/ReviewStatusView.tsx`, `.../status/ReviewStatusView.test.tsx`

**Interfaces:**
- Consumes: `backend`, `AgentVersionDetailResponse` (Task 8), `StatusPill` (Task 10).
- Produces: a one-status-line view (not the mockup's 5-stage pipeline — collapsed per spec §1, since the backend only exposes the version's current `VersionStatus`, not which individual gate it's at). Known cut: rejection notes are not shown here — `ReviewItem.notes` is only readable via the admin-only detail endpoint (Phase 0 Task 5), and no endpoint exposes it to the owning developer in this MVP. Documented rather than fabricated.

- [ ] **Step 1: Write the failing test**

`.../status/ReviewStatusView.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ReviewStatusView } from "./ReviewStatusView";
import type { AgentVersionDetailResponse } from "@/lib/types";

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

describe("ReviewStatusView", () => {
  it("explains an in-review version is waiting on a human reviewer", () => {
    render(<ReviewStatusView version={baseVersion} />);
    expect(screen.getByText(/Waiting on a human reviewer/)).toBeInTheDocument();
  });

  it("explains a rejected version", () => {
    render(<ReviewStatusView version={{ ...baseVersion, status: "REJECTED" }} />);
    expect(screen.getByText(/A reviewer rejected this version/)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- ReviewStatusView.test.tsx`
Expected: FAIL — `./ReviewStatusView` does not exist.

- [ ] **Step 3: Write minimal implementation**

`.../status/ReviewStatusView.tsx`:

```tsx
import { StatusPill } from "@/components/StatusPill";
import type { AgentVersionDetailResponse, VersionStatus } from "@/lib/types";

const STATUS_EXPLANATION: Record<VersionStatus, string> = {
  DRAFT: "Not yet submitted for review.",
  IN_REVIEW: "Passed the automated manifest check. Waiting on a human reviewer.",
  LIVE: "Passed every check and is now visible to buyers in the catalog.",
  REJECTED: "A reviewer rejected this version. Submit a new version to try again.",
};

export function ReviewStatusView({ version }: { version: AgentVersionDetailResponse }) {
  return (
    <div>
      <h1 className="font-serif text-2xl mb-1">
        {version.agentName} — v{version.versionNumber}
      </h1>
      <div className="mb-4">
        <StatusPill status={version.status} />
      </div>
      <p className="text-ink2">{STATUS_EXPLANATION[version.status]}</p>
    </div>
  );
}
```

`.../status/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { AgentVersionDetailResponse } from "@/lib/types";
import { ReviewStatusView } from "./ReviewStatusView";

export default async function ReviewStatusPage({
  params,
}: {
  params: Promise<{ agentId: string; versionId: string }>;
}) {
  const { agentId, versionId } = await params;
  const version = await backend.get<AgentVersionDetailResponse>(`/api/v1/agents/${agentId}/versions/${versionId}`);
  return <ReviewStatusView version={version} />;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- ReviewStatusView.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): add developer review-status view"
```

---

### Task 22: Manage / version-history view

**Files:**
- Create: `frontend/src/app/(developer)/dashboard/agents/[agentId]/page.tsx`
- Create: `.../agents/[agentId]/ManageView.tsx`, `.../agents/[agentId]/ManageView.test.tsx`

**Interfaces:**
- Consumes: `backend`, `MyAgentsResponse` (Task 8), `StatusPill` (Task 10), Phase 0 Task 2's `GET /api/v1/agents/mine`.
- Produces: the per-agent version-history view, and the entry point into Task 20's wizard for an additional version of an already-published agent.

- [ ] **Step 1: Write the failing test**

`.../agents/[agentId]/ManageView.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ManageView } from "./ManageView";
import type { MyAgentsResponse } from "@/lib/types";

const agent: MyAgentsResponse = {
  agentId: "a1",
  agentName: "GSTR-2B Reconciler",
  category: "GST & TDS",
  versions: [
    { id: "v1", versionNumber: "4.2", status: "LIVE" },
    { id: "v2", versionNumber: "4.1", status: "REJECTED" },
  ],
};

describe("ManageView", () => {
  it("lists every version with its status", () => {
    render(<ManageView agent={agent} />);
    expect(screen.getByText("v4.2")).toBeInTheDocument();
    expect(screen.getByText("v4.1")).toBeInTheDocument();
  });

  it("links to submitting a new version for this agent", () => {
    render(<ManageView agent={agent} />);
    expect(screen.getByRole("link", { name: /Submit new version/i })).toHaveAttribute(
      "href",
      "/dashboard/agents/a1/versions/new",
    );
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- ManageView.test.tsx`
Expected: FAIL — `./ManageView` does not exist.

- [ ] **Step 3: Write minimal implementation**

`.../agents/[agentId]/ManageView.tsx`:

```tsx
import Link from "next/link";
import { StatusPill } from "@/components/StatusPill";
import type { MyAgentsResponse } from "@/lib/types";

export function ManageView({ agent }: { agent: MyAgentsResponse }) {
  return (
    <div>
      <div className="flex justify-between items-center mb-6">
        <h1 className="font-serif text-2xl">{agent.agentName}</h1>
        <Link href={`/dashboard/agents/${agent.agentId}/versions/new`} className="bg-green text-white rounded-lg px-4 py-2 font-medium text-sm">
          Submit new version
        </Link>
      </div>
      <h2 className="font-semibold text-sm mb-3">Version history</h2>
      <div className="border-t border-line">
        {agent.versions.map((v) => (
          <div key={v.id} className="grid grid-cols-[100px_1fr_auto] gap-4 items-center py-3 border-b border-line">
            <span className="font-mono text-sm">v{v.versionNumber}</span>
            <span />
            <StatusPill status={v.status} />
          </div>
        ))}
      </div>
    </div>
  );
}
```

`.../agents/[agentId]/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { MyAgentsResponse } from "@/lib/types";
import { ManageView } from "./ManageView";

export default async function ManagePage({ params }: { params: Promise<{ agentId: string }> }) {
  const { agentId } = await params;
  const agents = await backend.get<MyAgentsResponse[]>("/api/v1/agents/mine");
  const agent = agents.find((a) => a.agentId === agentId);
  if (!agent) return <p className="text-red">Agent not found.</p>;
  return <ManageView agent={agent} />;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- ManageView.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): add developer manage/version-history view"
```

---

## Phase 5 — Admin

### Task 23: Admin review queue

**Files:**
- Create: `frontend/src/app/(admin)/layout.tsx`
- Create: `frontend/src/app/(admin)/review/page.tsx`
- Create: `frontend/src/app/(admin)/review/ReviewQueueList.tsx`, `.../review/ReviewQueueList.test.tsx`
- Create: `frontend/src/app/(admin)/review/[reviewItemId]/page.tsx`
- Create: `.../review/[reviewItemId]/ReviewItemDetailView.tsx`, `.../ReviewItemDetailView.test.tsx`
- Create: `.../review/[reviewItemId]/actions.ts`

**Interfaces:**
- Consumes: `backend`, `ReviewItemResponse`, `ReviewItemDetailResponse` (Task 8), `PermissionStatementDoc` (Task 11), Phase 0 Task 5's enriched queue and detail endpoints.
- Produces: the only admin-facing surface in this plan (no mockup precedent, per spec §1) — a list at `GET /api/v1/review/queue`, a detail view rendering the manifest via `mode="listing"`, and approve/reject with a required notes field, calling `POST /api/v1/review/{reviewItemId}/approve` / `/reject`.

- [ ] **Step 1: Write the failing tests**

`.../review/ReviewQueueList.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ReviewQueueList } from "./ReviewQueueList";
import type { ReviewItemResponse } from "@/lib/types";

const items: ReviewItemResponse[] = [
  { id: "ri1", agentVersionId: "v1", agentName: "Collections", versionNumber: "5.0", status: "PENDING" },
];

describe("ReviewQueueList", () => {
  it("links each item to its detail page", () => {
    render(<ReviewQueueList items={items} />);
    expect(screen.getByRole("link", { name: /Collections/i })).toHaveAttribute("href", "/review/ri1");
  });

  it("shows a message when the queue is empty", () => {
    render(<ReviewQueueList items={[]} />);
    expect(screen.getByText(/Nothing waiting for review/i)).toBeInTheDocument();
  });
});
```

`.../review/[reviewItemId]/ReviewItemDetailView.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ReviewItemDetailView } from "./ReviewItemDetailView";
import type { ReviewItemDetailResponse } from "@/lib/types";

vi.mock("./actions", () => ({
  approveReviewItemAction: vi.fn().mockResolvedValue(undefined),
  rejectReviewItemAction: vi.fn().mockResolvedValue(undefined),
}));

const item: ReviewItemDetailResponse = {
  id: "ri1",
  agentVersionId: "v1",
  agentName: "Collections",
  versionNumber: "5.0",
  status: "PENDING",
  manifest: {
    name: "Collections",
    version: "5.0",
    scopes: [],
    networkAllowlist: [],
    costEnvelope: { currency: "INR", maxPerRun: 200, pricingUnit: "OUTCOME", unitPrice: 5, unitLabel: "invoice_cycle" },
    approvalGates: [],
    dataClasses: [],
  },
  notes: null,
  createdAt: "2026-09-14T10:02:00Z",
  decidedAt: null,
};

describe("ReviewItemDetailView", () => {
  it("submits notes with the approval decision", async () => {
    const { approveReviewItemAction } = await import("./actions");
    render(<ReviewItemDetailView item={item} />);
    await userEvent.type(screen.getByRole("textbox"), "looks safe");
    await userEvent.click(screen.getByRole("button", { name: "Approve" }));
    expect(approveReviewItemAction).toHaveBeenCalledWith("ri1", "looks safe");
  });

  it("submits notes with the rejection decision", async () => {
    const { rejectReviewItemAction } = await import("./actions");
    render(<ReviewItemDetailView item={item} />);
    await userEvent.type(screen.getByRole("textbox"), "scope too broad");
    await userEvent.click(screen.getByRole("button", { name: "Reject" }));
    expect(rejectReviewItemAction).toHaveBeenCalledWith("ri1", "scope too broad");
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npm test -- ReviewQueueList.test.tsx ReviewItemDetailView.test.tsx`
Expected: FAIL — none of the modules exist.

- [ ] **Step 3: Write minimal implementation**

`frontend/src/app/(admin)/layout.tsx`:

```tsx
export default function AdminLayout({ children }: { children: React.ReactNode }) {
  return <div className="max-w-3xl mx-auto p-8">{children}</div>;
}
```

`.../review/ReviewQueueList.tsx`:

```tsx
import Link from "next/link";
import type { ReviewItemResponse } from "@/lib/types";

export function ReviewQueueList({ items }: { items: ReviewItemResponse[] }) {
  if (items.length === 0) return <p className="text-muted py-8">Nothing waiting for review.</p>;
  return (
    <div className="border-t border-line">
      {items.map((item) => (
        <Link key={item.id} href={`/review/${item.id}`} className="grid grid-cols-[1fr_auto] gap-4 items-center py-4 border-b border-line hover:bg-paper2">
          <div>
            <div className="font-semibold">{item.agentName}</div>
            <div className="text-sm text-ink2">v{item.versionNumber}</div>
          </div>
          <span className="text-sm font-medium">Review</span>
        </Link>
      ))}
    </div>
  );
}
```

`.../review/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { ReviewItemResponse } from "@/lib/types";
import { ReviewQueueList } from "./ReviewQueueList";

export default async function ReviewQueuePage() {
  const items = await backend.get<ReviewItemResponse[]>("/api/v1/review/queue");
  return (
    <div>
      <h1 className="font-serif text-2xl mb-6">Review queue</h1>
      <ReviewQueueList items={items} />
    </div>
  );
}
```

`.../review/[reviewItemId]/actions.ts`:

```ts
"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { backend } from "@/lib/backend";
import type { ReviewItemResponse } from "@/lib/types";

export async function approveReviewItemAction(reviewItemId: string, notes: string) {
  await backend.post<ReviewItemResponse>(`/api/v1/review/${reviewItemId}/approve`, { notes });
  revalidatePath("/review");
  redirect("/review");
}

export async function rejectReviewItemAction(reviewItemId: string, notes: string) {
  await backend.post<ReviewItemResponse>(`/api/v1/review/${reviewItemId}/reject`, { notes });
  revalidatePath("/review");
  redirect("/review");
}
```

`.../review/[reviewItemId]/ReviewItemDetailView.tsx`:

```tsx
"use client";

import { useState } from "react";
import { PermissionStatementDoc } from "@/components/PermissionStatementDoc";
import type { ReviewItemDetailResponse } from "@/lib/types";
import { approveReviewItemAction, rejectReviewItemAction } from "./actions";

export function ReviewItemDetailView({ item }: { item: ReviewItemDetailResponse }) {
  const [notes, setNotes] = useState("");
  const [submitting, setSubmitting] = useState(false);

  async function decide(approve: boolean) {
    setSubmitting(true);
    if (approve) await approveReviewItemAction(item.id, notes);
    else await rejectReviewItemAction(item.id, notes);
  }

  return (
    <div>
      <h1 className="font-serif text-2xl mb-1">
        {item.agentName} — v{item.versionNumber}
      </h1>
      <p className="text-ink2 mb-6">Submitted {new Date(item.createdAt).toLocaleString()}</p>
      <PermissionStatementDoc manifest={item.manifest} mode="listing" />
      <label className="block text-sm font-medium mt-6 mb-1">Notes</label>
      <textarea value={notes} onChange={(e) => setNotes(e.target.value)} className="border border-line rounded-lg px-3 py-2 w-full mb-4" />
      <div className="flex gap-2">
        <button disabled={submitting} onClick={() => decide(true)} className="bg-green text-white rounded-md px-4 py-2">
          Approve
        </button>
        <button disabled={submitting} onClick={() => decide(false)} className="border border-line rounded-md px-4 py-2">
          Reject
        </button>
      </div>
    </div>
  );
}
```

`.../review/[reviewItemId]/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { ReviewItemDetailResponse } from "@/lib/types";
import { ReviewItemDetailView } from "./ReviewItemDetailView";

export default async function ReviewItemPage({ params }: { params: Promise<{ reviewItemId: string }> }) {
  const { reviewItemId } = await params;
  const item = await backend.get<ReviewItemDetailResponse>(`/api/v1/review/${reviewItemId}`);
  return <ReviewItemDetailView item={item} />;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npm test -- ReviewQueueList.test.tsx ReviewItemDetailView.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): add admin review queue"
```

---

## Phase 6 — End-to-end proof

### Task 24: Seed script and core-loop E2E test

**Files:**
- Create: `frontend/e2e/seed.ts`
- Create: `frontend/e2e/playwright.config.ts`
- Create: `frontend/e2e/core-loop.spec.ts`

**Interfaces:**
- Consumes: the real backend's `/api/v1/auth/signup`, `/api/v1/agents`, `/api/v1/agents/{id}/versions`, `.../submit` endpoints directly (not through the frontend — the seed script prepares fixture data the same way Tasks 25/26 of the backend plan do), plus a direct Postgres write to fund a `Wallet` row (there is no funding endpoint by design — spec §1, Phase 0 Task 6's note).
- Produces: one Playwright test proving the frontend correctly drives the real backend through spec §7's core loop: login → catalog → agent detail → connect flow → trigger a run → run console reflects real `RunStatus`. It stops at confirming the run reaches `RUNNING` against a `FakeSandboxProvider`-backed backend (matching the backend plan's own Task 16 test double) — it does not re-simulate the full agent callback protocol (trace events, approval gates, cost metering), since that is already proven by the backend plan's own Tasks 25–26 E2E tests. This test's job is proving the *frontend* is wired correctly, not re-proving the backend.

**Prerequisites (documented, not automated by this task):** the backend from `docs/superpowers/plans/2026-09-16-agent-store-backend-platform.md` running on `localhost:8080` against a real Postgres reachable at `DATABASE_URL`, with its `SandboxProvider` bound to `FakeSandboxProvider` (no real E2B credentials needed for this test).

- [ ] **Step 1: Install Playwright and a Postgres client**

```bash
cd frontend
npm install -D @playwright/test pg @types/pg
npx playwright install --with-deps chromium
```

- [ ] **Step 2: Write the seed script**

`frontend/e2e/seed.ts`:

```ts
import { Client } from "pg";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";
const DATABASE_URL = process.env.DATABASE_URL ?? "postgres://postgres:postgres@localhost:5432/agentstore";

async function signup(orgName: string, orgType: "BUYER" | "DEVELOPER", email: string, password: string, role: string) {
  const res = await fetch(`${BACKEND_URL}/api/v1/auth/signup`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ orgName, orgType, email, password, role }),
  });
  if (!res.ok) throw new Error(`Signup failed for ${email}: ${res.status}`);
  return (await res.json()) as { token: string };
}

async function main() {
  const dev = await signup("E2E Dev Co", "DEVELOPER", "e2e-dev@example.com", "e2e-password", "DEVELOPER");
  await signup("E2E Buyer Co", "BUYER", "e2e-buyer@example.com", "e2e-password", "BUYER_ADMIN");

  const agent = (await fetch(`${BACKEND_URL}/api/v1/agents`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${dev.token}` },
    body: JSON.stringify({ name: "E2E Bank Reconciliation", category: "reconciliation", description: "seeded for e2e" }),
  }).then((r) => r.json())) as { id: string };

  const manifest = {
    name: "E2E Bank Reconciliation",
    version: "1.0.0",
    scopes: [{ type: "READ", resource: "bank_statement", description: "Read uploaded bank statement rows" }],
    network: { allowlist: [] },
    costEnvelope: { currency: "INR", maxPerRun: 500, pricingUnit: "METERED", unitPrice: 2, unitLabel: "reconciled_transaction" },
    modelRequirements: { provider: "anthropic", model: "claude-sonnet-5" },
    approvalGates: [],
    dataClasses: ["BANK_STATEMENT"],
  };

  const version = (await fetch(`${BACKEND_URL}/api/v1/agents/${agent.id}/versions`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${dev.token}` },
    body: JSON.stringify({ versionNumber: "1.0.0", manifestJson: JSON.stringify(manifest), imageRef: "e2e-template", changelog: "seed" }),
  }).then((r) => r.json())) as { id: string };

  await fetch(`${BACKEND_URL}/api/v1/agents/${agent.id}/versions/${version.id}/submit`, {
    method: "POST",
    headers: { Authorization: `Bearer ${dev.token}` },
  });

  const pg = new Client({ connectionString: DATABASE_URL });
  await pg.connect();
  const buyerOrgRow = await pg.query("select id from org where name = $1", ["E2E Buyer Co"]);
  await pg.query(
    `insert into wallet (id, org_id, balance, cap, updated_at) values (gen_random_uuid(), $1, 100000.00, null, now())
     on conflict (org_id) do update set balance = excluded.balance`,
    [buyerOrgRow.rows[0].id],
  );
  await pg.end();

  console.log("Seed complete. Buyer login: e2e-buyer@example.com / e2e-password");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
```

- [ ] **Step 3: Write the Playwright config and core-loop test**

`frontend/e2e/playwright.config.ts`:

```ts
import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: ".",
  use: { baseURL: "http://localhost:3000" },
  webServer: {
    command: "npm run dev",
    cwd: "..",
    port: 3000,
    reuseExistingServer: true,
  },
});
```

`frontend/e2e/core-loop.spec.ts`:

```ts
import { test, expect } from "@playwright/test";

test("buyer can browse, connect, and trigger a run against the real backend", async ({ page }) => {
  await page.goto("/login");
  await page.getByPlaceholder("Email").fill("e2e-buyer@example.com");
  await page.getByPlaceholder("Password").fill("e2e-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/catalog/);

  await page.getByText("E2E Bank Reconciliation").click();
  await expect(page.getByRole("heading", { name: "E2E Bank Reconciliation" })).toBeVisible();

  await page.getByRole("link", { name: "Connect this agent" }).click();
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByRole("button", { name: "Confirm" }).click();

  await expect(page).toHaveURL(/\/runs/);
  await page.getByRole("button", { name: /Run now/i }).click();
  await expect(page).toHaveURL(/\/runs\/[0-9a-f-]+/);
  await expect(page.getByText("RUNNING")).toBeVisible();
});
```

Add to `frontend/package.json` scripts: `"test:e2e": "playwright test"`, `"seed:e2e": "tsx e2e/seed.ts"` (install `tsx` as a dev dependency: `npm install -D tsx`).

- [ ] **Step 4: Run the seed script and the E2E test**

With the backend and its Postgres already running (prerequisites above):

```bash
cd frontend
npm run seed:e2e
npm run test:e2e
```

Expected: the seed script prints "Seed complete", and the Playwright test passes, ending with the run console showing `RUNNING`.

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "test(frontend): add seed script and core-loop E2E test"
```

---

## Self-Review Notes

- **Spec coverage:** every in-scope screen from spec §1 has a task — catalog (13), agent detail (14), connect flow (15), runs + trigger (16), run console (17), billing (18), developer dashboard (19), submission wizard (20), review status (21), manage/version-history (22), admin queue (23, net-new per spec §1). The explicitly-out-of-scope items (eval upload, red-team/staged-rollout stages, Tally connector, wallet top-up, GST line items, payouts) have no corresponding task, matching spec §1's cut list.
- **Backend gaps closed:** spec §2's four proposed endpoints became six concrete Phase 0 tasks once the actual backend DTOs were read against the design (the manifest-detail endpoint split needed merging into one unified endpoint since the catalog response already carries `versionId`; the review queue needed a detail endpoint in addition to enrichment; a wallet-read endpoint was added after discovering no wallet balance was readable anywhere, which the billing page and the insufficient-funds messaging both depend on).
- **Trigger-run gap:** found and fixed while drafting the E2E task — the original per-screen task breakdown never added a UI affordance to call `POST /api/v1/runs`, which would have made the core loop undemoable. Task 16 now owns this.
- **Type consistency:** `Manifest`/`ManifestDraft`/DTO field names in `frontend/src/lib/types.ts` (Task 8) were checked against the backend plan's actual Java records (not just their prose "Produces" lines, which in one case — `CatalogListingResponse` — had drifted from the real code) and against the real JWT claim structure (`sub`, not a claim literally named `userId`).
- **Route collision check:** buyer routes (`/catalog`, `/agents`, `/connect`, `/runs`, `/billing`) and developer routes (all under `/dashboard`) use disjoint top-level prefixes specifically so Task 9's middleware role-gating can match on a simple prefix list without ambiguity — the original design sketch had both roles under `/agents/...`, which was caught and fixed before any task was written against it.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-16-agent-store-frontend.md`. Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task, with review between tasks for fast iteration. Requires the `superpowers:subagent-driven-development` skill.
2. **Inline Execution** — execute tasks in this session in batches with checkpoints, using the `superpowers:executing-plans` skill.

Phase 0 (Tasks 1–6) must land in `backend/` before any frontend task that depends on it — each frontend task's Interfaces line names which Phase 0 task it needs.

