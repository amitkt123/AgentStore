package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.Tier;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunVersionPinningTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    ExecutionFixtures fixtures;
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

    HttpHeaders buyerAdmin(UUID orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, UserRole.BUYER_ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    <T> ResponseEntity<T> trigger(ExecutionFixtures.Scenario s, Class<T> type) {
        return rest.postForEntity(url("/api/v1/runs"),
                new HttpEntity<>(new TriggerRunRequest(s.connection().getId()), buyerAdmin(s.buyer().getId())), type);
    }

    @Test
    void creatingAConnectionPinsTheCurrentLiveVersion() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));

        var created = rest.postForEntity(url("/api/v1/connections"),
                new HttpEntity<>(new CreateConnectionRequest(s.version().getAgent().getId(), List.of("bank_statement"),
                        new BigDecimal("200.00")), buyerAdmin(s.buyer().getId())),
                ConnectionResponse.class).getBody();

        assertThat(connectionRepository.findById(created.id()).orElseThrow().getAgentVersion().getId())
                .isEqualTo(s.version().getId());
    }

    @Test
    void runsUseThePinnedVersionEvenWhenANewerOneIsLive() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));
        AgentVersion v2 = new AgentVersion(null, s.version().getAgent(), "2.0.0", s.version().getManifestJson(),
                "template-v2", VersionStatus.LIVE, Tier.TIER_A, "adds things");
        v2.setApprovedAt(Instant.now().plusSeconds(60));
        agentVersionRepository.save(v2);

        UUID runId = trigger(s, RunResponse.class).getBody().id();

        assertThat(runRepository.findById(runId).orElseThrow().getAgentVersion().getId()).isEqualTo(s.version().getId());
        assertThat(fakeSandboxProvider.specForRun(runId).templateId()).isEqualTo("template-v1");
    }

    @Test
    void connectionWithoutAPinnedVersionCannotRun() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));
        Connection connection = s.connection();
        connection.setAgentVersion(null);
        connectionRepository.save(connection);

        assertThat(trigger(s, String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void pinnedVersionThatIsNoLongerLiveCannotRun() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));
        AgentVersion pinned = s.version();
        pinned.setStatus(VersionStatus.REJECTED);
        agentVersionRepository.save(pinned);

        assertThat(trigger(s, String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
