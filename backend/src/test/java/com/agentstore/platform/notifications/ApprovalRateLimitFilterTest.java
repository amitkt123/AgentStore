package com.agentstore.platform.notifications;

import com.agentstore.platform.runtime.RuntimeRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApprovalRateLimitFilterTest {

    private final RuntimeRateLimiter limiter = mock(RuntimeRateLimiter.class);
    private final ApprovalRateLimitFilter filter = new ApprovalRateLimitFilter(limiter, 30);

    private MockHttpServletResponse call(String method, String uri, String ip, MockFilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void anAllowedCallPassesThroughUnderTheClientIpKey() throws Exception {
        when(limiter.tryAcquire(anyString(), anyInt())).thenReturn(Optional.empty());
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = call("GET", "/api/v1/approvals/abc", "203.0.113.7", chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        verify(limiter).tryAcquire(eq("approvals:203.0.113.7"), eq(30));
    }

    @Test
    void aCallOverTheLimitGets429WithRetryAfterAndNeverReachesTheController() throws Exception {
        when(limiter.tryAcquire(anyString(), anyInt())).thenReturn(Optional.of(Duration.ofSeconds(12)));
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = call("POST", "/api/v1/approvals/abc/decision", "203.0.113.7", chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("12");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void otherPathsAreNotLimited() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = call("GET", "/api/v1/notifications", "203.0.113.7", chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(limiter);
    }
}
