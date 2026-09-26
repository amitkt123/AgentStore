package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.*;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.Scope;
import com.agentstore.platform.manifest.ScopeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopeGuardTest extends AbstractIntegrationTest {

    @Autowired
    RuntimeFixtures fx;
    @Autowired
    ScopeGuard guard;
    @Autowired
    ManifestParser parser;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunStepRepository stepRepository;

    private RunPrincipal principalFor(RuntimeFixtures.RunningAgent agent) {
        return RunPrincipal.forRun(runRepository.findById(agent.run().getId()).orElseThrow(), parser);
    }

    private List<RunStep> violations(Run run) {
        return stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId()).stream()
                .filter(s -> s.getKind() == RunStepKind.SCOPE_VIOLATION).toList();
    }

    @Test
    void grantedScopesAreReturnedWithoutAnyViolation() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var principal = principalFor(agent);

        Scope read = guard.read(principal, "bank_statement");
        Scope write = guard.write(principal, "journal_entries");

        assertThat(read.type()).isEqualTo(ScopeType.READ);
        assertThat(write.requiresApproval()).isTrue();
        assertThat(violations(agent.run())).isEmpty();
    }

    @ParameterizedTest(name = "{0} {1} is denied")
    @CsvSource({
            "READ,invoices",           // declared, but the buyer did not grant it
            "READ,payroll",            // not declared at all
            "READ,journal_entries",    // declared only as WRITE
            "WRITE,bank_statement",    // declared only as READ
            "WRITE,invoices",          // declared as READ and not granted
            "READ,Bank_Statement",     // names are case-sensitive
            "READ,'bank_statement '",  // no trimming (quoted, or JUnit would trim it and the case would be granted)
            "READ,..%2Fsecret"         // path-shaped name
    })
    void everythingElseIsDeniedAndRecordedOnce(String type, String resource) {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var principal = principalFor(agent);

        assertThatThrownBy(() -> {
            if (type.equals("READ")) {
                guard.read(principal, resource);
            } else {
                guard.write(principal, resource);
            }
        }).isInstanceOfSatisfying(RuntimeApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(e.code()).isEqualTo("SCOPE_NOT_GRANTED");
        });

        assertThat(violations(agent.run())).hasSize(1);
        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getViolationCount()).isEqualTo(1);
    }

    @Test
    void gateActionsMustBeDeclaredAndCarryTheirTimeout() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var principal = principalFor(agent);

        assertThat(guard.gateAction(principal, "post_journal_entries").timeoutMinutes()).isEqualTo(5);
        assertThatThrownBy(() -> guard.gateAction(principal, "wire_money"))
                .isInstanceOfSatisfying(RuntimeApiException.class, e -> assertThat(e.code()).isEqualTo("SCOPE_NOT_GRANTED"));
        assertThat(violations(agent.run())).hasSize(1);
    }

    @Test
    void usageMustUseTheManifestUnit() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var principal = principalFor(agent);

        guard.usageUnit(principal, "reconciled_transaction");
        assertThat(violations(agent.run())).isEmpty();
        assertThatThrownBy(() -> guard.usageUnit(principal, "gigabytes"))
                .isInstanceOfSatisfying(RuntimeApiException.class, e -> assertThat(e.code()).isEqualTo("SCOPE_NOT_GRANTED"));
        assertThat(violations(agent.run())).hasSize(1);
    }

    @Test
    void theThirdProductionViolationStopsTheRunAndSaysSo() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var principal = principalFor(agent);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> guard.read(principal, "payroll")).isInstanceOf(RuntimeApiException.class);
        }
        assertThatThrownBy(() -> guard.read(principal, "payroll"))
                .isInstanceOfSatisfying(RuntimeApiException.class, e -> assertThat(e.getMessage()).contains("stopped"));

        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void sandboxRunsKeepGoingAfterManyViolations() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        Run run = runRepository.findById(agent.run().getId()).orElseThrow();
        run.setMode(RunMode.SANDBOX);
        runRepository.save(run);
        var principal = principalFor(agent);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> guard.read(principal, "payroll")).isInstanceOf(RuntimeApiException.class);
        }

        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(violations(agent.run())).hasSize(5);
    }
}
