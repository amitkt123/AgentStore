package com.agentstore.platform.payments;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookRateLimitFilterTest extends AbstractIntegrationTest {

    @Autowired
    WebhookRateLimitFilter filter;

    /** A sender address no other test uses, so this test's bucket is its own. */
    static String uniqueIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
    }

    int post(String path, String ip) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        return chain.getRequest() == null ? res.getStatus() : 200;
    }

    @Test
    void the121stWebhookInAMinuteFromOneSenderIs429() throws Exception {
        String ip = uniqueIp();
        for (int i = 0; i < 120; i++) {
            assertThat(post("/webhooks/payments/fake", ip)).as("request %d", i + 1).isEqualTo(200);
        }
        assertThat(post("/webhooks/payments/fake", ip)).isEqualTo(429);
        // Another sender, and non-webhook paths, are not affected.
        assertThat(post("/webhooks/payments/fake", uniqueIp())).isEqualTo(200);
        assertThat(post("/api/v1/wallet", ip)).isEqualTo(200);
    }
}
