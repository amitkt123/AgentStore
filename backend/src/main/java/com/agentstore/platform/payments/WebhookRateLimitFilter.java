package com.agentstore.platform.payments;

import com.agentstore.platform.runtime.ProblemResponses;
import com.agentstore.platform.runtime.RuntimeRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Per-client-IP limit on the public webhook endpoints, which sit outside authentication. The IP is the proxy's until
 * {@code server.forward-headers-strategy} is set, so behind a proxy without it the limit is shared by every sender.
 */
@Component
public class WebhookRateLimitFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/webhooks/";

    private final RuntimeRateLimiter limiter;
    private final int perMinute;

    public WebhookRateLimitFilter(RuntimeRateLimiter limiter, PaymentProperties props) {
        this.limiter = limiter;
        this.perMinute = props.webhookRatePerMinute() > 0 ? props.webhookRatePerMinute() : 120;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !UrlPathHelper.defaultInstance.getPathWithinApplication(request).startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Duration> wait = limiter.tryAcquire("webhooks:" + request.getRemoteAddr(), perMinute);
        if (wait.isPresent()) {
            ProblemResponses.write(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many requests; try again shortly", wait.get());
            return;
        }
        chain.doFilter(request, response);
    }
}
