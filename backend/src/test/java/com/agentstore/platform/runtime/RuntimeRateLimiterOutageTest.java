package com.agentstore.platform.runtime;

import com.agentstore.platform.runtime.RuntimeRateLimiter.Kind;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.ServerSocket;
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

    /** Connection refused fails instantly; a Redis that accepts the socket but never answers is the slow failure. */
    @Test
    @Timeout(20)
    void aRedisThatAcceptsButNeverAnswersDoesNotStallCalls() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            RedisClient client = RuntimeRedisConfig.client("redis://127.0.0.1:" + silent.getLocalPort());
            try {
                RuntimeRateLimiter limiter = new RuntimeRateLimiter(client, 1, 1, 1);
                UUID run = UUID.randomUUID();

                long started = System.nanoTime();
                for (int i = 0; i < 5; i++) {
                    assertThat(limiter.tryAcquire(run, Kind.DEFAULT)).as("call %d", i + 1).isEmpty();
                }

                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            } finally {
                client.shutdown(Duration.ZERO, Duration.ZERO);
            }
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
