package com.agentstore.platform.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentstore.platform.AbstractIntegrationTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

class RunLookupTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunLookup lookup;

    private Run run() {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        return fixtures.run(scenario, RunStatus.RUNNING, "sbx-lk", "tok-lk-" + System.nanoTime());
    }

    @Test
    void resolvesByUuidAndByDisplayId() {
        Run run = run();
        assertThat(lookup.find(run.getId().toString()).getId()).isEqualTo(run.getId());
        assertThat(lookup.find(run.getDisplayId()).getId()).isEqualTo(run.getId());
    }

    @Test
    void displayIdIsCaseAndWhitespaceInsensitive() {
        Run run = run();
        assertThat(lookup.find("  " + run.getDisplayId().toLowerCase() + " ").getId()).isEqualTo(run.getId());
    }

    @Test
    void unknownReferenceIs404() {
        assertThatThrownBy(() -> lookup.find("R-99999999")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        assertThatThrownBy(() -> lookup.find("not-an-id")).isInstanceOf(ResponseStatusException.class);
    }
}
