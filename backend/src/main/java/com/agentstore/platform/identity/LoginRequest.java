package com.agentstore.platform.identity;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code remember} asks for a refresh token ("Keep me signed in on this device"); a missing field reads as
 * false. {@code side}/{@code orgName} are only used when the user has no workspace membership left: both
 * present creates a new workspace to land in, otherwise the login fails with {@code NO_WORKSPACE}. They are
 * ignored when the user already belongs to a workspace.
 */
public record LoginRequest(@NotBlank String email, @NotBlank String password, boolean remember, SignupSide side,
                            String orgName) {
    public LoginRequest(String email, String password, boolean remember) {
        this(email, password, remember, null, null);
    }
}
