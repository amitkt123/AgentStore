package com.agentstore.platform.payments;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Caller checks shared by the payments controllers. */
@Component
public class PaymentAccess {

    public JwtService.Claims claims() {
        return (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    /** 403 unless the caller is the BUYER_ADMIN of {@code orgId}. */
    public JwtService.Claims requireOwnOrgAdmin(UUID orgId) {
        var claims = claims();
        if (claims.role() != UserRole.BUYER_ADMIN || !orgId.equals(claims.orgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only this org's admin can do this");
        }
        return claims;
    }

    /** 403 unless the caller is the BUYER_ADMIN of {@code orgId} or a platform ADMIN. */
    public JwtService.Claims requireOwnOrgOrAdmin(UUID orgId) {
        var claims = claims();
        boolean own = claims.role() == UserRole.BUYER_ADMIN && orgId.equals(claims.orgId());
        if (!own && claims.role() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot access another org's payments");
        }
        return claims;
    }
}
