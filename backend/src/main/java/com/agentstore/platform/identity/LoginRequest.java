package com.agentstore.platform.identity;

import jakarta.validation.constraints.NotBlank;

/** {@code remember} asks for a refresh token ("Keep me signed in on this device"); a missing field reads as false. */
public record LoginRequest(@NotBlank String email, @NotBlank String password, boolean remember) {
}
