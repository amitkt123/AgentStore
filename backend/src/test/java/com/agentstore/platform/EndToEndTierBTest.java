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
    @Autowired
    FakeSandboxProvider fakeSandboxProvider;
    @Autowired
    com.agentstore.platform.common.RecordingEmailSender emails;

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
                new SignupRequest("Dev Two", "DevCo", "dev2@devco.example", "pw", SignupSide.DEVELOPER),
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

        rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("Karya Admin", "Platform", "admin@platform.example", "pw", SignupSide.DEVELOPER),
                AuthResponse.class);
        // A listed admin-emails address is not platform ADMIN until it verifies (I1): confirm the email,
        // then log back in for a token that actually carries ADMIN.
        String adminVerifyToken = com.agentstore.platform.common.RecordingEmailSender.tokenFromLink(
                emails.lastTo("admin@platform.example").orElseThrow(), "verify-email");
        rest.postForEntity(url("/api/v1/auth/email/verify"), Map.of("token", adminVerifyToken), Void.class);
        var adminLogin = rest.postForEntity(url("/api/v1/auth/login"),
                new LoginRequest("admin@platform.example", "pw", false), AuthResponse.class).getBody();
        var queue = rest.exchange(url("/api/v1/review/queue"), HttpMethod.GET,
                new HttpEntity<>(bearer(adminLogin.token())), new ParameterizedTypeReference<List<ReviewItemResponse>>() {}).getBody();
        var pending = queue.stream().filter(i -> i.agentVersionId().equals(version.id())).findFirst().orElseThrow();
        rest.postForEntity(url("/api/v1/review/" + pending.id() + "/approve"),
                new HttpEntity<>(new ReviewDecisionRequest("looks safe"), bearer(adminLogin.token())), ReviewItemResponse.class);

        var buyerLogin = rest.postForEntity(url("/api/v1/auth/signup"),
                new SignupRequest("Finance Two", "Acme SMB 2", "finance2@acme.example", "pw", SignupSide.BUYER),
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

        String callbackToken = fakeSandboxProvider.specForRun(run.id()).envVars().get("AGENT_CALLBACK_TOKEN");
        HttpHeaders agentHeaders = bearer(callbackToken);

        rest.postForEntity(url("/internal/agent-callback/runs/" + run.id() + "/events"),
                new HttpEntity<>(Map.of("kind", "GATE", "detail", Map.of("trigger", "MESSAGE_CUSTOMER")), agentHeaders), Void.class);

        var pausedRun = rest.exchange(url("/api/v1/runs/" + run.id()), HttpMethod.GET,
                new HttpEntity<>(bearer(buyerLogin.token())), Map.class);
        assertThat(pausedRun.getBody().get("status")).isEqualTo("AWAITING_APPROVAL");

        // A second person joins the same workspace as an approver through a real invitation.
        rest.exchange(url("/api/v1/orgs/" + buyerOrgId + "/invitations"), HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "approver@acme.example", "role", "BUYER_APPROVER"),
                        bearer(buyerLogin.token())), Void.class);
        String inviteToken = com.agentstore.platform.common.RecordingEmailSender.tokenFromLink(
                emails.lastTo("approver@acme.example").orElseThrow(), "invite");
        String approverToken = rest.postForEntity(url("/api/v1/invitations/" + inviteToken + "/accept"),
                Map.of("fullName", "Arjun Mehta", "password", "pw"), AuthResponse.class).getBody().token();

        rest.exchange(url("/api/v1/runs/" + run.id() + "/approve"), HttpMethod.POST,
                new HttpEntity<>(new ApproveRunRequest("go ahead"), bearer(approverToken)), RunResponse.class);

        var resumedRun = rest.exchange(url("/api/v1/runs/" + run.id()), HttpMethod.GET,
                new HttpEntity<>(bearer(buyerLogin.token())), Map.class);
        assertThat(resumedRun.getBody().get("status")).isEqualTo("RUNNING");
    }
}
