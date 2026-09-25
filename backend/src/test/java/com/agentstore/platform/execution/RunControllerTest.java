package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.*;
import com.agentstore.platform.ledger.Wallet;
import com.agentstore.platform.ledger.WalletRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    @Autowired
    WalletRepository walletRepository;
    @Autowired
    SecretRepository secretRepository;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunStepRepository runStepRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders buyerAdmin(UUID orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, UserRole.BUYER_ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
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
        Connection connection = new Connection(null, buyer, agent,
                List.of("invoice", "whatsapp_message"), new BigDecimal("200.00"), ConnectionStatus.ACTIVE);
        connection.setAgentVersion(version);
        connection = connectionRepository.save(connection);
        Wallet wallet = new Wallet(null, buyer.getId(), new BigDecimal("1000.00"), null);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), buyer.getId(), UserRole.BUYER_ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>(new TriggerRunRequest(connection.getId()), headers);

        var response = rest.postForEntity(url("/api/v1/runs"), request, RunResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(RunStatus.RUNNING);

        Run saved = runRepository.findById(response.getBody().id()).orElseThrow();
        assertThat(saved.getSandboxId()).isNotBlank();

        var lastSpec = fakeSandboxProvider.createdSpecs().get(fakeSandboxProvider.createdSpecs().size() - 1);
        assertThat(lastSpec.imageRef()).isEqualTo("collections-template-v1");
        assertThat(lastSpec.requestId()).isEqualTo(saved.getId().toString());
        assertThat(lastSpec.command()).containsExactly("sh", "-c", "/workspace/app/agentstore-entrypoint");
        assertThat(lastSpec.cpu()).isEqualTo(RunController.SANDBOX_CPU);
        assertThat(lastSpec.memoryMb()).isEqualTo(RunController.SANDBOX_MEMORY_MB);
        assertThat(lastSpec.allowOut()).contains("graph.facebook.com");
        assertThat(lastSpec.envVars()).containsEntry("AGENT_RUN_ID", saved.getId().toString());

        String rawToken = fakeSandboxProvider.specForRun(saved.getId()).envVars().get("AGENT_CALLBACK_TOKEN");
        assertThat(rawToken).isNotBlank();
        assertThat(saved.getCallbackTokenHash()).isEqualTo(RunTokenService.hash(rawToken)).isNotEqualTo(rawToken);
    }

    @Test
    void triggeredRunGetsADeadlineMatchingTheSandboxTimeout() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));

        var response = rest.postForEntity(url("/api/v1/runs"),
                new HttpEntity<>(new TriggerRunRequest(s.connection().getId()), buyerAdmin(s.buyer().getId())),
                RunResponse.class);

        Run saved = runRepository.findById(response.getBody().id()).orElseThrow();
        assertThat(Duration.between(saved.getStartedAt(), saved.getDeadlineAt()))
                .isEqualTo(Duration.ofSeconds(RunController.SANDBOX_TIMEOUT_SECONDS));
    }

    @Test
    void sandboxCreationFailureFailsTheRunAndReturnsBadGateway() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));
        fakeSandboxProvider.failNextCreate();

        var response = rest.postForEntity(url("/api/v1/runs"),
                new HttpEntity<>(new TriggerRunRequest(s.connection().getId()), buyerAdmin(s.buyer().getId())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        var runs = runRepository.findByConnectionBuyerOrgIdOrderByStartedAtDesc(s.buyer().getId());
        assertThat(runs).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(RunStatus.FAILED);
            assertThat(r.getCompletedAt()).isNotNull();
        });
    }

    @Test
    void noSandboxCapacityFailsTheRunWith503AndReleasesTheReservation() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));
        fakeSandboxProvider.failNextCreateWithCapacity("CAPACITY_EXHAUSTED");

        // Not TestRestTemplate: its Apache HttpClient5-backed factory transparently retries this specific POST
        // about a second later (observed via debug instrumentation during TDD) once the server answers with a
        // 5xx status faster than the client finishes writing the small request body over the loopback socket.
        // The retry succeeds (capacityCode is already consumed) and TestRestTemplate silently returns that
        // second, unrelated 200 response, hiding the 503 this test is asserting on. A plain RestTemplate over
        // the JDK's SimpleClientHttpRequestFactory does not exhibit this and reliably returns the first response.
        var plain = new RestTemplate();
        plain.setRequestFactory(new SimpleClientHttpRequestFactory());
        plain.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) { return false; }
        });

        var response = plain.postForEntity(url("/api/v1/runs"),
                new HttpEntity<>(new TriggerRunRequest(s.connection().getId()), buyerAdmin(s.buyer().getId())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        var run = runRepository.findByConnectionBuyerOrgIdOrderByStartedAtDesc(s.buyer().getId()).get(0);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getReservedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        var steps = runStepRepository.findByRunIdOrderByCreatedAtAsc(run.getId());
        assertThat(steps).anySatisfy(step -> {
            assertThat(step.getKind()).isEqualTo(RunStepKind.FAILED);
            // jsonb round-trips through Postgres with reformatted spacing, so parse rather than substring-match.
            Map<String, Object> detail;
            try {
                detail = new ObjectMapper().readValue(step.getDetail(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            assertThat(detail).containsEntry("code", "CAPACITY_EXHAUSTED");
        });
    }

    @Test
    void secretRevealFailureFailsTheRunAndReleasesTheReservation() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));
        // A corrupted secret row: reveal() will fail to decrypt it, mimicking a decryption failure after key
        // rotation or a corrupted row, well before sandboxProvider.create is ever reached.
        secretRepository.save(new Secret(null, s.connection(), "broken",
                "not-a-valid-ciphertext".getBytes(), new byte[12]));

        var response = rest.postForEntity(url("/api/v1/runs"),
                new HttpEntity<>(new TriggerRunRequest(s.connection().getId()), buyerAdmin(s.buyer().getId())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        var runs = runRepository.findByConnectionBuyerOrgIdOrderByStartedAtDesc(s.buyer().getId());
        assertThat(runs).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(RunStatus.FAILED);
            assertThat(r.getCompletedAt()).isNotNull();
        });
        Wallet wallet = walletRepository.findByOrgId(s.buyer().getId()).orElseThrow();
        assertThat(wallet.getReserved()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void buyerCannotTriggerAnotherOrgsConnection() {
        var victim = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));
        Org attacker = orgRepository.save(new Org(null, "Mallory", OrgType.BUYER));

        var response = rest.postForEntity(url("/api/v1/runs"),
                new HttpEntity<>(new TriggerRunRequest(victim.connection().getId()), buyerAdmin(attacker.getId())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(runRepository.findByConnectionBuyerOrgIdOrderByStartedAtDesc(victim.buyer().getId())).isEmpty();
        Wallet wallet = walletRepository.findByOrgId(victim.buyer().getId()).orElseThrow();
        assertThat(wallet.getReserved()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
