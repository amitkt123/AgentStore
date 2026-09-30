package com.agentstore.platform.notifications;

import com.agentstore.platform.runtime.ProblemResponses;
import com.agentstore.platform.runtime.RuntimeRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Per-client-IP limit on the public approval-link endpoints, which sit outside authentication. The IP is the proxy's
 * until {@code server.forward-headers-strategy} is set, so behind a proxy without it the limit is shared by everyone.
 */
@Component
public class ApprovalRateLimitFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/api/v1/approvals/";

    private final RuntimeRateLimiter limiter;
    private final int perMinute;

    public ApprovalRateLimitFilter(RuntimeRateLimiter limiter,
                                   @Value("${agentstore.approvals.rate-limit.per-minute:30}") int perMinute) {
        this.limiter = limiter;
        this.perMinute = perMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !UrlPathHelper.defaultInstance.getPathWithinApplication(request).startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Duration> wait = limiter.tryAcquire("approvals:" + request.getRemoteAddr(), perMinute);
        if (wait.isPresent()) {
            ProblemResponses.write(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many requests; try again shortly", wait.get());
            return;
        }
        chain.doFilter(request, response);
    }
}
