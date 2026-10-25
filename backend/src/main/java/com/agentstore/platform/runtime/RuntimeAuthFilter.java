package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunTokenService;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.runtime.RuntimeRateLimiter.Kind;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authenticates a /runtime call by its run token. Deliberately not a Spring bean (SecurityConfig builds it), so it
 * runs only inside the /runtime security chain and is never auto-registered for every URL.
 */
public class RuntimeAuthFilter extends OncePerRequestFilter {

    /** Request attribute (an Instant): when this runtime call reached the platform. */
    public static final String CALL_STARTED_AT = "agentstore.runtime.callStartedAt";
    static final String SUPPORTED_API_VERSION = "1";
    private static final Pattern API_VERSION = Pattern.compile("runtime-api/(\\S+)");

    private final RunRepository runRepository;
    private final ManifestParser manifestParser;
    private final RuntimeRateLimiter rateLimiter;

    public RuntimeAuthFilter(RunRepository runRepository, ManifestParser manifestParser, RuntimeRateLimiter rateLimiter) {
        this.runRepository = runRepository;
        this.manifestParser = manifestParser;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !UrlPathHelper.defaultInstance.getPathWithinApplication(request).startsWith("/runtime/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(CALL_STARTED_AT, Instant.now());
        String unsupported = unsupportedApiVersion(request.getHeader("User-Agent"));
        if (unsupported != null) {
            ProblemResponses.write(response, HttpStatus.BAD_REQUEST, "UNSUPPORTED_API_VERSION",
                    "This server supports runtime-api/" + SUPPORTED_API_VERSION + "; the client sent runtime-api/"
                            + unsupported, null);
            return;
        }
        String token = bearerToken(request.getHeader("Authorization"));
        Run run = token.isEmpty() ? null
                : runRepository.findFirstByCallbackTokenHash(RunTokenService.hash(token)).orElse(null);
        if (run == null || !RunTokenService.matches(token, run.getCallbackTokenHash())) {
            ProblemResponses.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid run token", null);
            return;
        }
        if (run.getStatus().isTerminal()) {
            ProblemResponses.write(response, HttpStatus.CONFLICT, "RUN_TERMINATED",
                    "The run is no longer accepting calls: " + run.getStatus(), null);
            return;
        }
        if (run.getDeadlineAt() != null && run.getDeadlineAt().isBefore(Instant.now())) {
            ProblemResponses.write(response, HttpStatus.CONFLICT, "RUN_TERMINATED",
                    "The run is past its deadline", null);
            return;
        }
        Optional<Duration> wait = rateLimiter.tryAcquire(run.getId(),
                kindFor(UrlPathHelper.defaultInstance.getPathWithinApplication(request)));
        if (wait.isPresent()) {
            ProblemResponses.write(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many calls for this run; retry shortly", wait.get());
            return;
        }
        RunPrincipal principal = RunPrincipal.forRun(run, manifestParser);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_AGENT_RUN"))));
        chain.doFilter(request, response);
    }

    static Kind kindFor(String path) {
        if (path.equals("/runtime/v1/logs") || path.equals("/runtime/v1/network-events")) {
            return Kind.LOGS;
        }
        return path.startsWith("/runtime/v1/llm/") ? Kind.LLM : Kind.DEFAULT;
    }

    private static String bearerToken(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : "";
    }

    /** The N of a {@code runtime-api/N} token in the User-Agent when it is not the supported version, else null. */
    private static String unsupportedApiVersion(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        Matcher matcher = API_VERSION.matcher(userAgent);
        return matcher.find() && !SUPPORTED_API_VERSION.equals(matcher.group(1)) ? matcher.group(1) : null;
    }
}
