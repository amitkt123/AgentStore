package com.agentstore.platform.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Size(max = 255) String orgName,
        @Email @NotBlank String email,
        @NotBlank String password,
        @NotNull SignupSide side) {
}
