package com.agentstore.platform.runtime;

import com.agentstore.platform.runtime.RuntimeRateLimiter.Kind;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeRateLimiterOutageTest {

    @Test
    void requestsStillPassWhenRedisIsUnreachableAndDoNotStallForLong() {
        RedisClient dead = RuntimeRedisConfig.client("redis://localhost:1");
        try {
            RuntimeRateLimiter limiter = new RuntimeRateLimiter(dead, 1, 1, 1);
            UUID run = UUID.randomUUID();

            long started = System.nanoTime();
            for (int i = 0; i < 5; i++) {
                assertThat(limiter.tryAcquire(run, Kind.DEFAULT)).as("call %d", i + 1).isEmpty();
            }

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        } finally {
            dead.shutdown();
        }
    }

    @Test
    void constructingTheClientNeverConnects() {
        RedisClient dead = RuntimeRedisConfig.client("redis://localhost:1");
        try {
            assertThat(dead).isNotNull();   // no exception: the app must start with Redis down
        } finally {
            dead.shutdown();
        }
    }
}
