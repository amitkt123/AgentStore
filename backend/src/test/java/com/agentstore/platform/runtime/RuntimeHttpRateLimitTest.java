package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "agentstore.runtime.rate-limit.per-minute=3")
class RuntimeHttpRateLimitTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    RuntimeFixtures fx;

    /**
     * The default TestRestTemplate uses Apache HttpClient, which silently retries a 429 after its Retry-After delay
     * (about 20 s here) and would hide the very response this test asserts. The JDK client does not retry.
     */
    private final TestRestTemplate rest = new TestRestTemplate(new RestTemplateBuilder().requestFactory(SimpleClientHttpRequestFactory::new));

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> get(String token) {
        return rest.exchange("http://localhost:" + port + "/runtime/v1/context", HttpMethod.GET,
                new HttpEntity<>(RuntimeFixtures.bearer(token)), Map.class);
    }

    @Test
    void theFourthCallInAMinuteIsRateLimitedWithARetryAfter() {
        var busy = fx.runningAgent(new BigDecimal("500.00"));
        var quiet = fx.runningAgent(new BigDecimal("500.00"));

        for (int i = 0; i < 3; i++) {
            assertThat(get(busy.token()).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        var limited = get(busy.token());

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody().get("code")).isEqualTo("RATE_LIMITED");
        assertThat(Integer.parseInt(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isPositive();
        assertThat(get(quiet.token()).getStatusCode()).isEqualTo(HttpStatus.OK);   // another run is unaffected
    }
}
