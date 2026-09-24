package com.agentstore.platform.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code remember} asks for a refresh token ("Keep me signed in on this device"); a missing field reads as
 * false. {@code side}/{@code orgName} are only used when the user has no workspace membership left: both
 * present creates a new workspace to land in, otherwise the login fails with {@code NO_WORKSPACE}. They are
 * ignored when the user already belongs to a workspace. {@code orgName} is deliberately not {@code @NotBlank}:
 * blank must keep meaning "not provided" so a member-less user with no fields gets 422 {@code NO_WORKSPACE},
 * not 400.
 */
public record LoginRequest(@NotBlank String email, @NotBlank String password, boolean remember, SignupSide side,
                            @Size(max = 255) String orgName) {
    public LoginRequest(String email, String password, boolean remember) {
        this(email, password, remember, null, null);
    }
}
