package com.agentstore.platform.identity;

/** {@code refreshToken} is present only when the person asked to stay signed in, and on refresh. */
public record AuthResponse(String token, String refreshToken) {
    public static AuthResponse access(String token) {
        return new AuthResponse(token, null);
    }
}
