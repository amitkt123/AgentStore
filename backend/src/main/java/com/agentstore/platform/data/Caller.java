package com.agentstore.platform.data;

import com.agentstore.platform.identity.JwtService;
import org.springframework.security.core.context.SecurityContextHolder;

/** The authenticated buyer behind a /api/v1 call. */
public final class Caller {

    private Caller() {
    }

    public static JwtService.Claims current() {
        return (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
