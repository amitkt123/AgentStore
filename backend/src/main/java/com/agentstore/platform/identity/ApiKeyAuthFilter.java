package com.agentstore.platform.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates {@code Authorization: Bearer ask_live_…} as the key's creator inside the key's org, with the role
 * fixed at DEVELOPER, so no controller needs to know about keys. A valid key on a path outside {@link ApiKeyScope}
 * is answered with 403 right here, which also covers the permitAll paths that {@code authorizeHttpRequests} never
 * checks. Anything else about the key being wrong leaves the request unauthenticated (401).
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    static final String AUTHORITY = "AUTH_API_KEY";
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final DeveloperApiKeyRepository keys;
    private final OrgMembershipRepository memberships;

    public ApiKeyAuthFilter(DeveloperApiKeyRepository keys, OrgMembershipRepository memberships) {
        this.keys = keys;
        this.memberships = memberships;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer " + ApiKeys.PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        Optional<DeveloperApiKey> key = keys.findByKeyHash(ApiKeys.hash(header.substring("Bearer ".length())))
                .filter(k -> k.getRevokedAt() == null)
                .filter(this::creatorStillDevelopsForTheOrg);
        if (key.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (!ApiKeyScope.allows(request.getMethod(), path)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "API keys cannot be used on this endpoint");
            return;
        }
        DeveloperApiKey k = key.get();
        var claims = new JwtService.Claims(k.getCreatedByUserId(), k.getOrgId(), UserRole.DEVELOPER);
        var auth = new UsernamePasswordAuthenticationToken(claims, null,
                List.of(new SimpleGrantedAuthority("ROLE_DEVELOPER"), new SimpleGrantedAuthority(AUTHORITY)));
        SecurityContextHolder.getContext().setAuthentication(auth);
        Instant now = Instant.now();
        keys.touch(k.getId(), now, now.minus(TOUCH_INTERVAL));
        chain.doFilter(request, response);
    }

    private boolean creatorStillDevelopsForTheOrg(DeveloperApiKey key) {
        return memberships.findById(new OrgMembershipId(key.getCreatedByUserId(), key.getOrgId()))
                .filter(m -> m.getRole() == UserRole.DEVELOPER)
                .isPresent();
    }
}
