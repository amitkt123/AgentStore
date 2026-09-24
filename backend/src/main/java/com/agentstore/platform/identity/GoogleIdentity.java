package com.agentstore.platform.identity;

public record GoogleIdentity(String subject, String email, boolean emailVerified, String name) {
}
