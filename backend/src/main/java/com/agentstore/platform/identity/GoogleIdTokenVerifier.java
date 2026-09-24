package com.agentstore.platform.identity;

import java.util.Optional;

/** Checks a Google ID token's signature, issuer, audience and expiry. Empty means "do not trust it". */
public interface GoogleIdTokenVerifier {
    Optional<GoogleIdentity> verify(String idToken);

    /** False when {@code agentstore.google.client-id} is not set, so sign-in cannot be trusted. */
    default boolean isConfigured() {
        return true;
    }
}
