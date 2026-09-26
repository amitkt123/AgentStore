package com.agentstore.platform.build;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.catalog.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Transitions queue behind a finalize (which holds the agent row) instead of racing it for the version row. */
class BuildTransitionsLockTest extends AbstractStorageIntegrationTest {

    @Autowired BuildFixtures fixtures;
    @Autowired BuildTransitions transitions;
    @Autowired AgentRepository agents;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void aTransitionWaitsForTheAgentLockUntilItsHolderCommits() throws Exception {
        var built = fixtures.queued("e2b");
        CompletableFuture<Void> transition = new TransactionTemplate(transactionManager).execute(status -> {
            agents.findByIdForUpdate(built.agent().getId()).orElseThrow();
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> transitions.markBuilding(built.build().getId()));
            assertThatThrownBy(() -> f.get(700, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(fixtures.reload(built.build()).getStatus()).isEqualTo(BuildStatus.QUEUED);
            return f;
        });

        transition.get(10, TimeUnit.SECONDS);
        assertThat(fixtures.reload(built.build()).getStatus()).isEqualTo(BuildStatus.BUILDING);
    }
}
