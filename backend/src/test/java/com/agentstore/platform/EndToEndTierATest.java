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
