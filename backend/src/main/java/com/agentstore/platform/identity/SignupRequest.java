package com.agentstore.platform.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record SignupRequest(
        @NotBlank String orgName,
        OrgType orgType,
        @Email @NotBlank String email,
        @NotBlank String password,
        UserRole role) {
}
