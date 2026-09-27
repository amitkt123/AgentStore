package com.agentstore.platform.llm;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LlmBudgetTest extends AbstractIntegrationTest {

    @Autowired LlmBudget budget;
    @Autowired LlmFixtures fx;

    @Test
    void reservesUntilTheBudgetIsFullAndNotOneTokenMore() {
        var agent = fx.runningAgent("valid-llm-platform.json");

        assertThat(budget.reserve(agent.runId(), 400, 1000)).isTrue();
        assertThat(budget.reserve(agent.runId(), 600, 1000)).isTrue();      // exactly at the boundary
        assertThat(budget.reserve(agent.runId(), 1, 1000)).isFalse();

        assertThat(fx.tokens(agent.runId())).containsExactly(0, 1000);
    }

    @Test
    void aReservationLargerThanTheWholeBudgetIsRefusedWithoutTouchingTheRun() {
        var agent = fx.runningAgent("valid-llm-platform.json");

        assertThat(budget.reserve(agent.runId(), 1001, 1000)).isFalse();
        assertThat(budget.reserve(agent.runId(), 0, 1000)).isFalse();
        assertThat(budget.reserve(agent.runId(), -5, 1000)).isFalse();

        assertThat(fx.tokens(agent.runId())).containsExactly(0, 0);
    }

    @Test
    void settlingMovesTheReservationToUsedWithTheActualCount() {
        var agent = fx.runningAgent("valid-llm-platform.json");

        assertThat(budget.reserve(agent.runId(), 500, 1000)).isTrue();
        budget.settle(agent.runId(), 500, 120);
        assertThat(fx.tokens(agent.runId())).containsExactly(120, 0);

        // actual usage may exceed the estimate; the cap is enforced at admission
        assertThat(budget.reserve(agent.runId(), 300, 1000)).isTrue();
        budget.settle(agent.runId(), 300, 450);
        assertThat(fx.tokens(agent.runId())).containsExactly(570, 0);
    }

    @Test
    void releasingGivesTheReservationBackAndLeavesUsedAlone() {
        var agent = fx.runningAgent("valid-llm-platform.json");
        fx.setTokens(agent.runId(), 200, 0);

        assertThat(budget.reserve(agent.runId(), 500, 1000)).isTrue();
        budget.release(agent.runId(), 500);

        assertThat(fx.tokens(agent.runId())).containsExactly(200, 0);
    }

    @Test
    void usedTokensCountAgainstTheBudget() {
        var agent = fx.runningAgent("valid-llm-platform.json");
        fx.setTokens(agent.runId(), 900, 0);

        assertThat(budget.reserve(agent.runId(), 100, 1000)).isTrue();
        assertThat(budget.reserve(agent.runId(), 1, 1000)).isFalse();
    }

    @Test
    void concurrentReservationsCannotOversubscribeTheBudget() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return budget.reserve(agent.runId(), 300, 1000);
            }));
        }
        start.countDown();
        long granted = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                granted++;
            }
        }
        pool.shutdown();

        assertThat(granted).isEqualTo(3);
        assertThat(fx.tokens(agent.runId())).containsExactly(0, 900);
    }
}
