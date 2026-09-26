package com.agentstore.platform.runtime;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RuntimeRedisConfig {

    /**
     * Creating the client does not connect. Commands fail fast (300 ms, and immediately while disconnected) so a Redis
     * outage cannot stall runtime calls; {@link RuntimeRateLimiter} treats any failure as "allow". The URI timeout
     * matters too: the socket connect timeout only covers TCP, while a Redis that accepts the socket and never answers
     * would otherwise hold the connection handshake for Lettuce's default 60 s.
     */
    public static RedisClient client(String uri) {
        RedisURI redisUri = RedisURI.create(uri);
        redisUri.setTimeout(Duration.ofMillis(300));
        RedisClient client = RedisClient.create(redisUri);
        client.setOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(300)).build())
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(300)))
                .build());
        return client;
    }

    @Bean(destroyMethod = "shutdown")
    RedisClient runtimeRedisClient(@Value("${agentstore.runtime.redis-uri:redis://localhost:6379}") String uri) {
        return client(uri);
    }
}
