package com.agentstore.platform.identity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    JwtService jwtService = new JwtService("test-only-secret-not-for-prod-0123456789abcdef", 60);

    @Test
    void issuedTokenParsesBackToSameClaims() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();

        String token = jwtService.issue(userId, orgId, UserRole.BUYER_ADMIN);
        var claims = jwtService.parse(token).orElseThrow();

        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.orgId()).isEqualTo(orgId);
        assertThat(claims.role()).isEqualTo(UserRole.BUYER_ADMIN);
    }

    @Test
    void garbageTokenDoesNotParse() {
        assertThat(jwtService.parse("not-a-jwt")).isEmpty();
    }
}
