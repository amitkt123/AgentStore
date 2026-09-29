package com.agentstore.platform.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentstore.platform.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RunDisplayIdTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunRepository runRepository;

    @Test
    void productionRunGetsAnRPrefixedId() {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-di-1", "tok-di-1");
        assertThat(run.getDisplayId()).matches("R-\\d+");
    }

    @Test
    void sandboxRunGetsAnSPrefixedIdFromItsOwnSequence() {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        // a sandbox run has no connection; build one directly
        Run sandbox = new Run(null, null, scenario.version(), RunStatus.RUNNING, new BigDecimal("100.00"),
                BigDecimal.ZERO, "sbx-di-3", RunTokenService.hash("tok-di-3"));
        sandbox.setMode(RunMode.SANDBOX);
        sandbox.setInitiatedByOrgId(scenario.version().getAgent().getDeveloperOrg().getId());
        sandbox = runRepository.save(sandbox);
        assertThat(sandbox.getDisplayId()).matches("S-\\d+");
    }

    @Test
    void concurrentInsertsNeverCollide() throws Exception {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        var pool = Executors.newFixedThreadPool(8);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            int n = i;
            Callable<String> task = () -> fixtures.run(scenario, RunStatus.RUNNING, "sbx-c-" + n, "tok-c-" + n).getDisplayId();
            futures.add(pool.submit(task));
        }
        Set<String> ids = new HashSet<>();
        for (Future<String> f : futures) {
            ids.add(f.get());
        }
        pool.shutdown();
        assertThat(ids).hasSize(24);
    }
}
