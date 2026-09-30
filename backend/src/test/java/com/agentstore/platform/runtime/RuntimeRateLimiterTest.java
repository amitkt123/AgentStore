package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.runtime.RuntimeRateLimiter.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "agentstore.runtime.rate-limit.per-minute=3",
        "agentstore.runtime.rate-limit.logs-per-minute=2"})
class RuntimeRateLimiterTest extends AbstractIntegrationTest {

    @Autowired
    RuntimeRateLimiter limiter;

    @Test
    void allowsUpToTheLimitThenAsksTheCallerToWait() {
        UUID run = UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(run, Kind.DEFAULT)).as("call %d", i + 1).isEmpty();
        }
        Optional<Duration> wait = limiter.tryAcquire(run, Kind.DEFAULT);

        assertThat(wait).isPresent();
        assertThat(wait.get()).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void eachRunAndEachKindHasItsOwnBucket() {
        UUID run = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(run, Kind.DEFAULT);
        }

        assertThat(limiter.tryAcquire(run, Kind.DEFAULT)).isPresent();
        assertThat(limiter.tryAcquire(UUID.randomUUID(), Kind.DEFAULT)).isEmpty();
        assertThat(limiter.tryAcquire(run, Kind.LOGS)).isEmpty();
        assertThat(limiter.tryAcquire(run, Kind.LOGS)).isEmpty();
        assertThat(limiter.tryAcquire(run, Kind.LOGS)).isPresent();
    }

    @Test
    void aCallerChosenKeyHasItsOwnLimit() {
        String subject = "approvals:" + UUID.randomUUID();

        assertThat(limiter.tryAcquire(subject, 2)).isEmpty();
        assertThat(limiter.tryAcquire(subject, 2)).isEmpty();
        assertThat(limiter.tryAcquire(subject, 2)).isPresent();
        assertThat(limiter.tryAcquire("approvals:" + UUID.randomUUID(), 2)).isEmpty();
    }
}
