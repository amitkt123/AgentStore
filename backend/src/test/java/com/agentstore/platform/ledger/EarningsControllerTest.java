package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.execution.RunStepKind;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.llm.LlmCall;
import com.agentstore.platform.llm.LlmCallRepository;
import com.agentstore.platform.llm.LlmCallStatus;
import com.agentstore.platform.llm.LlmFixtures;
import com.agentstore.platform.llm.LlmSource;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EarningsControllerTest extends AbstractIntegrationTest {

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
    LlmCallRepository llmCalls;
    @Autowired
    LlmFixtures llmFixtures;
    @Autowired
    RunLifecycleService lifecycle;

    <T> ResponseEntity<T> get(String path, UUID callerOrg, UserRole role, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), callerOrg, role));
        return rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(headers), type);
    }

    Run newRun(ExecutionFixtures.Scenario s) {
        return fixtures.run(s, RunStatus.RUNNING, "sbx-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
    }

    void charge(ExecutionFixtures.Scenario s, Run run, String amount) {
        UUID id = usageEvents.save(new UsageEvent(null, run.getId(), s.buyer().getId(), BigDecimal.ONE,
                new BigDecimal(amount), new BigDecimal(amount))).getId();
        ledgerService.chargeUsageEvent(id);
    }

    void llmCostAndComplete(Run run, String cost) {
        llmFixtures.ensureModels();
        LlmCall call = new LlmCall(UUID.randomUUID(), run.getId(), LlmFixtures.CLAUDE, LlmSource.PLATFORM,
                LlmCallStatus.OK, "h-" + UUID.randomUUID());
        call.setCost(new BigDecimal(cost));
        llmCalls.save(call);
        lifecycle.recordEvent(run.getId(), RunStepKind.COMPLETE, Map.of());
    }

    UUID developerOrg(ExecutionFixtures.Scenario s) {
        return s.version().getAgent().getDeveloperOrg().getId();
    }

    @Test
    void earningsBreakdownForMonth() {
        var s = fixtures.liveTierA(new BigDecimal("100000.00"), new BigDecimal("1000.00"));
        Run r1 = newRun(s);
        Run r2 = newRun(s);
        charge(s, r1, "100.00");
        charge(s, r2, "50.00");
        llmCostAndComplete(r1, "3.00");

        var response = get("/api/v1/developer/earnings", developerOrg(s), UserRole.DEVELOPER, EarningsResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        EarningsResponse body = response.getBody();
        assertThat(body.month()).isEqualTo(YearMonth.now(ZoneOffset.UTC).toString());
        assertThat(body.buyersPaid()).isEqualByComparingTo("150.00");
        assertThat(body.platformFee()).isEqualByComparingTo("30.00");
        assertThat(body.feePercent()).isEqualByComparingTo("20");
        assertThat(body.aiUsage()).isEqualByComparingTo("3.00");
        assertThat(body.tax()).isEqualByComparingTo("0");
        assertThat(body.earned()).isEqualByComparingTo("117.00");
        assertThat(body.available()).isEqualByComparingTo("117.00");
        assertThat(body.byAgent()).hasSize(1);
        var agent = body.byAgent().get(0);
        assertThat(agent.agentId()).isEqualTo(s.version().getAgent().getId());
        assertThat(agent.agent()).isEqualTo("Bank Reconciliation");
        assertThat(agent.runs()).isEqualTo(2);
        assertThat(agent.buyersPaid()).isEqualByComparingTo("150.00");
        assertThat(agent.fee()).isEqualByComparingTo("30.00");
        assertThat(agent.aiUsage()).isEqualByComparingTo("3.00");
        assertThat(agent.earned()).isEqualByComparingTo("117.00");
        assertThat(agent.perRun()).isEqualByComparingTo("58.50");
    }

    @Test
    void negativeAvailableIsShown() {
        var s = fixtures.liveTierA(new BigDecimal("100000.00"), new BigDecimal("1000.00"));
        llmCostAndComplete(newRun(s), "5.00");

        var body = get("/api/v1/developer/earnings", developerOrg(s), UserRole.DEVELOPER, EarningsResponse.class).getBody();

        assertThat(body.available()).isEqualByComparingTo("-5.00");
        assertThat(body.earned()).isEqualByComparingTo("-5.00");
        assertThat(body.buyersPaid()).isEqualByComparingTo("0");
        assertThat(body.aiUsage()).isEqualByComparingTo("5.00");
        assertThat(body.byAgent()).hasSize(1);
        assertThat(body.byAgent().get(0).runs()).isEqualTo(0);
        assertThat(body.byAgent().get(0).perRun()).isEqualByComparingTo("0");
    }

    @Test
    void developerWithNoLedgerGetsZerosAndBadMonthIsRejected() {
        UUID org = UUID.randomUUID();
        var body = get("/api/v1/developer/earnings?month=2020-01", org, UserRole.DEVELOPER, EarningsResponse.class).getBody();
        assertThat(body.month()).isEqualTo("2020-01");
        assertThat(body.earned()).isEqualByComparingTo("0");
        assertThat(body.available()).isEqualByComparingTo("0");
        assertThat(body.byAgent()).isEmpty();
        assertThat(get("/api/v1/developer/earnings?month=nope", org, UserRole.DEVELOPER, String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void statementCsvHasOneRowPerTransaction() {
        var s = fixtures.liveTierA(new BigDecimal("100000.00"), new BigDecimal("1000.00"));
        Run r1 = newRun(s);
        Run r2 = newRun(s);
        charge(s, r1, "100.00");
        charge(s, r2, "50.00");
        llmCostAndComplete(r1, "3.00");
        String month = YearMonth.now(ZoneOffset.UTC).toString();

        var response = get("/api/v1/developer/statements/" + month + "/download", developerOrg(s), UserRole.DEVELOPER,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"karya-statement-" + month + ".csv\"");
        String[] lines = response.getBody().trim().split("\r?\n");
        assertThat(lines[0]).isEqualTo("date,run,agent,type,buyer_paid,platform_fee,ai_usage,tax,earned");
        assertThat(lines).hasSize(4);
        assertThat(lines[1]).contains(",Bank Reconciliation,RUN_CHARGE,100.00,20.00,0.00,0.00,80.00");
        assertThat(lines[2]).contains(",Bank Reconciliation,RUN_CHARGE,50.00,10.00,0.00,0.00,40.00");
        assertThat(lines[3]).contains(",Bank Reconciliation,LLM_COST,0.00,0.00,3.00,0.00,-3.00");
    }

    @Test
    void csvQuotesAgentNamesPerRfc4180() {
        assertThat(EarningsStatementCsv.field("Plain")).isEqualTo("Plain");
        assertThat(EarningsStatementCsv.field("A, \"B\"")).isEqualTo("\"A, \"\"B\"\"\"");
    }

    @Test
    void buyerCannotReadEarnings() {
        UUID org = UUID.randomUUID();
        assertThat(get("/api/v1/developer/earnings", org, UserRole.BUYER_ADMIN, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/developer/statements/2026-10/download", org, UserRole.BUYER_ADMIN, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
