package com.agentstore.platform.sandbox;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.execution.RunStep;
import com.agentstore.platform.execution.RunStepKind;
import com.agentstore.platform.execution.RunStepRepository;
import com.agentstore.platform.execution.UsageOutcome;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ScopeType;
import com.agentstore.platform.runtime.RunPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxRunPlumbingTest extends AbstractStorageIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired SandboxFixtures fx;
    @Autowired ExecutionFixtures exec;
    @Autowired RunLifecycleService lifecycle;
    @Autowired RunRepository runs;
    @Autowired RunStepRepository runSteps;
    @Autowired ManifestParser parser;
    @Autowired JdbcTemplate jdbc;

    long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    BigDecimal walletTotal() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(balance + reserved), 0) FROM wallet", BigDecimal.class);
    }

    List<RunStepKind> stepKinds(Run run) {
        return runSteps.findByRunIdOrderByCreatedAtAsc(run.getId()).stream().map(RunStep::getKind).toList();
    }

    ResponseEntity<Object> call(HttpMethod method, String path, Object body, Org org, UserRole role) {
        return rest.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, fx.bearer(org, role)), Object.class);
    }

    @Test
    void usageOnASandboxRunTouchesNoLedgerWalletOrUsageEvent() {
        var dev = fx.developer("valid-tier-a.json");   // unit price 2.00, maxPerRun 500
        Run run = fx.sandboxRun(dev, fx.profile(dev, "p"), RunStatus.RUNNING);
        long ledger = count("ledger_entry");
        long events = count("usage_event");
        BigDecimal wallets = walletTotal();

        UsageOutcome outcome = lifecycle.recordUsage(run.getId(), new BigDecimal("3"), Map.of("unit", "u"));

        assertThat(outcome.charged()).isEqualByComparingTo("6.00");
        assertThat(outcome.capReached()).isFalse();
        Run after = runs.findById(run.getId()).orElseThrow();
        assertThat(after.getSimulatedCharged()).isEqualByComparingTo("6.00");
        assertThat(after.getRequestedCharge()).isEqualByComparingTo("6.00");
        assertThat(after.getTotalCharged()).isEqualByComparingTo("0");
        assertThat(count("ledger_entry")).isEqualTo(ledger);
        assertThat(count("usage_event")).isEqualTo(events);
        assertThat(walletTotal()).isEqualByComparingTo(wallets);
    }

    @Test
    void theCapClipsTheSimulatedChargeButRequestedKeepsTheAsk() {
        var dev = fx.developer("valid-tier-a.json");
        Run run = fx.sandboxRun(dev, fx.profile(dev, "capped"), RunStatus.RUNNING);
        run.setCostCap(new BigDecimal("10.00"));
        runs.save(run);

        UsageOutcome outcome = lifecycle.recordUsage(run.getId(), new BigDecimal("8"), Map.of());   // asks 16.00, cap 10.00

        assertThat(outcome.capReached()).isTrue();
        Run after = runs.findById(run.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(after.getSimulatedCharged()).isEqualByComparingTo("10.00");
        assertThat(after.getRequestedCharge()).isEqualByComparingTo("16.00");
        assertThat(stepKinds(after)).contains(RunStepKind.COST_CAP_REACHED);
    }

    @Test
    void aScopeViolationDoesNotKillASandboxRunButStillKillsAProductionRun() {
        var dev = fx.developer("valid-tier-a.json");
        Run sandbox = fx.sandboxRun(dev, fx.profile(dev, "v"), RunStatus.RUNNING);
        for (int i = 0; i < 4; i++) {
            assertThat(lifecycle.recordViolation(sandbox.getId(), Map.of("summary", "x"))).isFalse();
        }
        assertThat(runs.findById(sandbox.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);

        var scenario = exec.liveTierA(new BigDecimal("500"), new BigDecimal("1000"));
        Run prod = exec.run(scenario, RunStatus.RUNNING, "sbx", "tok");
        lifecycle.recordViolation(prod.getId(), Map.of("summary", "x"));
        lifecycle.recordViolation(prod.getId(), Map.of("summary", "x"));
        assertThat(lifecycle.recordViolation(prod.getId(), Map.of("summary", "x"))).isTrue();
    }

    @Test
    void aSandboxRunPrincipalIsGrantedEveryDeclaredScopeInTheInitiatorsOrg() {
        var dev = fx.developer("valid-runtime-agent.json");
        Run run = fx.sandboxRun(dev, fx.profile(dev, "p"), RunStatus.RUNNING);

        RunPrincipal principal = RunPrincipal.forRun(run, parser);

        assertThat(principal.orgId()).isEqualTo(dev.org().getId());
        assertThat(principal.mode()).isEqualTo(RunMode.SANDBOX);
        assertThat(principal.granted(ScopeType.READ, "bank_statement")).isTrue();
        assertThat(principal.granted(ScopeType.READ, "invoices")).isTrue();
        assertThat(principal.granted(ScopeType.WRITE, "journal_entries")).isTrue();
        assertThat(principal.granted(ScopeType.WRITE, "undeclared")).isFalse();
    }

    @Test
    void buyerRunEndpointsAnswer404ForASandboxRunAndListsExcludeIt() {
        var dev = fx.developer("valid-tier-a.json");
        Run run = fx.sandboxRun(dev, fx.profile(dev, "p"), RunStatus.RUNNING);
        Org buyer = exec.liveTierA(new BigDecimal("500"), new BigDecimal("1000")).buyer();

        assertThat(call(HttpMethod.GET, "/api/v1/runs/" + run.getId(), null, buyer, UserRole.BUYER_ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/v1/runs/" + run.getId() + "/outputs", null, buyer, UserRole.BUYER_ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST, "/api/v1/runs/" + run.getId() + "/approve", Map.of("notes", "x"), buyer,
                UserRole.BUYER_MEMBER).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var mine = rest.exchange("http://localhost:" + port + "/api/v1/runs/mine", HttpMethod.GET,
                new HttpEntity<>(null, fx.bearer(buyer, UserRole.BUYER_ADMIN)), List.class);
        assertThat(mine.getBody()).isEmpty();
    }

    @Test
    void theInitiatingDeveloperReadsOutputsButAnotherDeveloperAndAProductionRunAreRefused() {
        var dev = fx.developer("valid-tier-a.json");
        var other = fx.developer("valid-tier-a.json");
        Run run = fx.sandboxRun(dev, fx.profile(dev, "p"), RunStatus.RUNNING);
        Run prod = exec.run(exec.liveTierA(new BigDecimal("500"), new BigDecimal("1000")), RunStatus.RUNNING, "s", "t");

        assertThat(call(HttpMethod.GET, "/api/v1/runs/" + run.getId() + "/outputs", null, dev.org(), UserRole.DEVELOPER)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/v1/runs/" + run.getId() + "/outputs", null, other.org(), UserRole.DEVELOPER)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/v1/runs/" + prod.getId() + "/outputs", null, dev.org(), UserRole.DEVELOPER)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
