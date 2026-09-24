package com.agentstore.platform.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

@Component
public class NimbusGoogleIdTokenVerifier implements GoogleIdTokenVerifier {

    private static final String JWKS = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private final String clientId;
    private final JwtDecoder decoder;

    public NimbusGoogleIdTokenVerifier(@Value("${agentstore.google.client-id:}") String clientId) {
        this.clientId = clientId;
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(JWKS).build();
        OAuth2TokenValidator<Jwt> issuer = jwt -> ISSUERS.contains(String.valueOf(jwt.getClaims().get("iss")))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong issuer", null));
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(clientId)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong audience", null));
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), issuer, audience));
        this.decoder = nimbus;
    }

    @Override
    public Optional<GoogleIdentity> verify(String idToken) {
        if (clientId.isBlank()) {
            return Optional.empty();
        }
        try {
            Jwt jwt = decoder.decode(idToken);
            Object verified = jwt.getClaims().get("email_verified");
            return Optional.of(new GoogleIdentity(jwt.getSubject(), jwt.getClaimAsString("email"),
                    Boolean.TRUE.equals(verified) || "true".equals(String.valueOf(verified)),
                    jwt.getClaimAsString("name")));
        } catch (JwtException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean isConfigured() {
        return !clientId.isBlank();
    }
}
