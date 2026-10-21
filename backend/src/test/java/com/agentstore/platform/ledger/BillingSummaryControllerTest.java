package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.execution.Connection;
import com.agentstore.platform.execution.ConnectionRepository;
import com.agentstore.platform.execution.ConnectionStatus;
import com.agentstore.platform.catalog.Tier;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.GateRequest;
import com.agentstore.platform.execution.GateRequestRepository;
import com.agentstore.platform.execution.GateStatus;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BillingSummaryControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    LedgerService ledgerService;
    @Autowired
    UsageEventRepository usageEvents;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    AgentVersionRepository agentVersionRepository;
    @Autowired
    ConnectionRepository connectionRepository;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    GateRequestRepository gateRequests;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    com.agentstore.platform.execution.RunRepository runRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    <T> ResponseEntity<T> get(String path, UUID callerOrg, UserRole role, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), callerOrg, role));
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), type);
    }

    Run charge(ExecutionFixtures.Scenario scenario, Connection connection, String quantity, String unitCost) {
        Run run = new Run(null, connection, scenario.version(), RunStatus.RUNNING, connection.getSpendCap(),
                BigDecimal.ZERO, "sbx-" + UUID.randomUUID(), "h-" + UUID.randomUUID());
        run.setStartedAt(Instant.now());
        run = runRepository.save(run);
        BigDecimal qty = new BigDecimal(quantity);
        BigDecimal amount = qty.multiply(new BigDecimal(unitCost));
        UUID eventId = usageEvents.save(new UsageEvent(null, run.getId(), scenario.buyer().getId(), qty,
                new BigDecimal(unitCost), amount)).getId();
        ledgerService.chargeUsageEvent(eventId);
        return run;
    }

    @Test
    void billingSummaryGroupsByAgent() {
        ExecutionFixtures.Scenario first = fixtures.liveTierA(new BigDecimal("100000.00"), new BigDecimal("10000.00"));
        Org buyer = first.buyer();
        // A second agent for the same buyer.
        Org developer = orgRepository.save(new Org(null, "DevCo2", OrgType.DEVELOPER));
        Agent agent2 = agentRepository.save(new Agent(null, developer, "Invoice Matcher", "matching", "d"));
        AgentVersion version2 = new AgentVersion(null, agent2, "1.0.0", first.version().getManifestJson(),
                "template-v1", VersionStatus.LIVE, Tier.TIER_A, "cl");
        version2.setApprovedAt(Instant.now());
        version2 = agentVersionRepository.save(version2);
        Connection connection2 = new Connection(null, buyer, agent2, List.of("bank_statement"),
                new BigDecimal("100000.00"), ConnectionStatus.ACTIVE);
        connection2.setAgentVersion(version2);
        connection2 = connectionRepository.save(connection2);
        var second = new ExecutionFixtures.Scenario(buyer, version2, connection2);

        Run a1 = charge(first, first.connection(), "10", "2.00");     // 20.00
        Run a2 = charge(first, first.connection(), "5", "2.00");      // 10.00, ends at the cost cap
        Run b1 = charge(second, connection2, "100", "2.00");          // 200.00
        jdbc.update("update run set terminal_reason = 'COST_CAP_REACHED' where id = ?", a2.getId());
        UUID decider = UUID.randomUUID();
        GateRequest gate = new GateRequest(b1, "pay", "s", "{}", "[]", Instant.now().plusSeconds(60));
        gate.setStatus(GateStatus.APPROVED);
        gate.setDecidedByUserId(decider);
        gate.setDecidedAt(Instant.now());
        gateRequests.save(gate);

        var response = get("/api/v1/orgs/" + buyer.getId() + "/billing/summary", buyer.getId(), UserRole.BUYER_ADMIN,
                BillingSummaryResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        BillingSummaryResponse body = response.getBody();
        assertThat(body.month()).isEqualTo(YearMonth.now(ZoneOffset.UTC).toString());
        assertThat(body.total()).isEqualByComparingTo("230.00");
        assertThat(body.agents()).hasSize(2);
        var top = body.agents().get(0);
        assertThat(top.agent()).isEqualTo("Invoice Matcher");
        assertThat(top.agentId()).isEqualTo(agent2.getId());
        assertThat(top.runs()).isEqualTo(1);
        assertThat(top.total()).isEqualByComparingTo("200.00");
        assertThat(top.lines()).hasSize(1);
        assertThat(top.lines().get(0).units()).isEqualByComparingTo("100");
        assertThat(top.lines().get(0).unitDisplay()).isEqualTo("reconciled_transaction");
        assertThat(top.lines().get(0).amount()).isEqualByComparingTo("200.00");
        assertThat(top.lines().get(0).decidedBy()).isEqualTo(decider);
        assertThat(top.lines().get(0).terminalReason()).isNull();
        assertThat(top.lines().get(0).runDisplayId()).startsWith("R-");

        var bottom = body.agents().get(1);
        assertThat(bottom.agent()).isEqualTo("Bank Reconciliation");
        assertThat(bottom.runs()).isEqualTo(2);
        assertThat(bottom.total()).isEqualByComparingTo("30.00");
        assertThat(bottom.lines()).hasSize(2);
        assertThat(bottom.lines().get(0).at()).isAfterOrEqualTo(bottom.lines().get(1).at());   // newest first
        var capped = bottom.lines().stream().filter(l -> l.amount().compareTo(new BigDecimal("10.00")) == 0)
                .findFirst().orElseThrow();
        assertThat(capped.terminalReason()).isEqualTo("COST_CAP_REACHED");
        assertThat(capped.decidedBy()).isNull();
    }

    @Test
    void otherMonthAndBadMonth() {
        ExecutionFixtures.Scenario s = fixtures.liveTierA(new BigDecimal("100000.00"), new BigDecimal("1000.00"));
        UUID orgId = s.buyer().getId();
        charge(s, s.connection(), "1", "10.00");

        var empty = get("/api/v1/orgs/" + orgId + "/billing/summary?month=2020-01", orgId, UserRole.BUYER_ADMIN,
                BillingSummaryResponse.class);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(empty.getBody().month()).isEqualTo("2020-01");
        assertThat(empty.getBody().total()).isEqualByComparingTo("0");
        assertThat(empty.getBody().agents()).isEmpty();

        assertThat(get("/api/v1/orgs/" + orgId + "/billing/summary?month=nope", orgId, UserRole.BUYER_ADMIN,
                String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void otherOrgIsForbidden() {
        Org buyer = orgRepository.save(new Org(null, "B-" + UUID.randomUUID(), OrgType.BUYER));
        Org intruder = orgRepository.save(new Org(null, "I-" + UUID.randomUUID(), OrgType.BUYER));

        assertThat(get("/api/v1/orgs/" + buyer.getId() + "/billing/summary", intruder.getId(), UserRole.BUYER_ADMIN,
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/orgs/" + buyer.getId() + "/wallet", intruder.getId(), UserRole.BUYER_ADMIN,
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
