package com.agentstore.platform.identity;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Tests register a fake ID token string and the identity Google would vouch for. */
@Component
@Primary
public class FakeGoogleIdTokenVerifier implements GoogleIdTokenVerifier {

    private final Map<String, GoogleIdentity> tokens = new ConcurrentHashMap<>();
    private volatile boolean configured = true;

    public void register(String idToken, GoogleIdentity identity) {
        tokens.put(idToken, identity);
    }

    /** Simulates {@code agentstore.google.client-id} not being set. Call {@link #configure()} to undo. */
    public void unconfigure() {
        configured = false;
    }

    public void configure() {
        configured = true;
    }

    @Override
    public Optional<GoogleIdentity> verify(String idToken) {
        return Optional.ofNullable(tokens.get(idToken));
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }
}
