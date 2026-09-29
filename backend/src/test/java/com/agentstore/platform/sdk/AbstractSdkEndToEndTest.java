package com.agentstore.platform.sdk;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.*;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.runtime.InMemoryBackends;
import com.agentstore.platform.runtime.InMemoryBackends.InMemoryResourceBackend;
import com.agentstore.platform.runtime.RuntimeFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * WS4a acceptance: a real SDK test agent (Python or TypeScript) runs as a subprocess against this backend, speaking only
 * /runtime/v1. The test thread plays the buyer and decides the gate. Subclasses supply the command and skip when the
 * toolchain is not installed.
 */
@Import(InMemoryBackends.class)
abstract class AbstractSdkEndToEndTest extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired RuntimeFixtures fx;
    @Autowired InMemoryResourceBackend resources;
    @Autowired JwtService jwtService;
    @Autowired RunRepository runRepository;
    @Autowired RunStepRepository stepRepository;
    @Autowired GateRequestRepository gateRepository;

    @BeforeEach
    void reset() {
        resources.written.clear();
    }

    static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !new File(p.toFile(), "contracts/runtime-v1.yaml").exists()) p = p.getParent();
        if (p == null) throw new IllegalStateException("repo root not found from " + Path.of("").toAbsolutePath());
        return p;
    }

    /** The command that runs the named scenario ("happy" | "denied" | "rejected"); call assumeTrue(...) for missing tooling. */
    protected abstract List<String> command(String scenario);

    protected abstract File workDir();

    private record Outcome(int exitCode, String output) {}

    private Outcome runAgent(RuntimeFixtures.RunningAgent agent, String scenario, GateDecision decision) throws Exception {
        var pb = new ProcessBuilder(command(scenario)).directory(workDir()).redirectErrorStream(true);
        pb.environment().put("AGENTSTORE_API_URL", "http://localhost:" + port);
        pb.environment().put("AGENTSTORE_RUN_TOKEN", agent.token());
        pb.environment().put("AGENTSTORE_RUN_ID", agent.run().getId().toString());
        pb.environment().put("AGENTSTORE_MODE", "SANDBOX");
        Process process = pb.start();
        var output = new StringBuffer();
        Thread reader = new Thread(() -> {
            try (var r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                r.lines().forEach(l -> output.append(l).append('\n'));
            } catch (IOException ignored) {
            }
        });
        reader.start();

        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        boolean decided = decision == null;
        while (process.isAlive() && Instant.now().isBefore(deadline)) {
            if (!decided) {
                var gates = gateRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId());
                if (!gates.isEmpty()) {
                    decide(agent, gates.get(0).getId(), decision);
                    decided = true;
                }
            }
            Thread.sleep(200);
        }
        if (process.isAlive()) {
            process.destroyForcibly();
            fail("agent timed out; output:\n" + output);
        }
        reader.join(5000);
        return new Outcome(process.exitValue(), output.toString());
    }

    private void decide(RuntimeFixtures.RunningAgent agent, UUID gateId, GateDecision decision) {
        HttpHeaders buyer = new HttpHeaders();
        buyer.setBearerAuth(jwtService.issue(UUID.randomUUID(), agent.scenario().buyer().getId(), UserRole.BUYER_APPROVER));
        buyer.setContentType(MediaType.APPLICATION_JSON);
        var response = rest.exchange("http://localhost:" + port + "/api/v1/runs/" + agent.run().getId() + "/gates/" + gateId + "/decision",
                HttpMethod.POST, new HttpEntity<>(Map.of("decision", decision.name(), "note", "e2e"), buyer), RunResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theBankStatementMatcherReadsGatesWritesChargesAndCompletes() throws Exception {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        resources.seed("bank_statement", List.of(Map.of("id", 1, "amount", 100), Map.of("id", 2, "amount", 250)));

        var outcome = runAgent(agent, "happy", GateDecision.APPROVE);

        assertThat(outcome.exitCode()).as(outcome.output()).isZero();
        Run run = runRepository.findById(agent.run().getId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        var steps = stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId());
        assertThat(steps).extracting(RunStep::getKind).contains(
                RunStepKind.DATA_READ, RunStepKind.GATE_REQUESTED, RunStepKind.GATE_DECIDED,
                RunStepKind.WRITE_STAGED, RunStepKind.USAGE, RunStepKind.COMPLETE);
        assertThat(resources.written).hasSize(2);
    }

    @Test
    void anUndeclaredReadSurfacesScopeNotGrantedToTheHandlerAndFailsTheRun() throws Exception {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var outcome = runAgent(agent, "denied", null);

        assertThat(outcome.exitCode()).as(outcome.output()).isNotZero();
        assertThat(outcome.output()).contains("ScopeNotGrantedError");
        var steps = stepRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId());
        assertThat(steps).extracting(RunStep::getKind).contains(RunStepKind.SCOPE_VIOLATION);
        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void aRejectedGateSurfacesGateRejectedErrorAndNothingIsWritten() throws Exception {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var outcome = runAgent(agent, "rejected", GateDecision.REJECT);

        assertThat(outcome.exitCode()).as(outcome.output()).isNotZero();
        assertThat(outcome.output()).contains("GateRejectedError");
        // WS1: a buyer rejection ends the run CANCELLED itself; the SDK's follow-up /fail gets a 409 it must swallow.
        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(resources.written).isEmpty();
    }
}
