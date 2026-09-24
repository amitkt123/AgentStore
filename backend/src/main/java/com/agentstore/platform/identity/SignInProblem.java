package com.agentstore.platform.identity;

import org.springframework.http.HttpStatus;

/**
 * Raised as a 409/422 with a stable {@code code} the web app can act on, from password login or Google
 * sign-in. Shared so both entry points shape their "you need to tell us more" responses the same way.
 */
public class SignInProblem extends RuntimeException {
    public final HttpStatus status;
    public final String code;

    public SignInProblem(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
