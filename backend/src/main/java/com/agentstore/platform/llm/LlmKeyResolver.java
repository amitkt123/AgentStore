package com.agentstore.platform.llm;

import com.agentstore.platform.manifest.LlmMode;
import com.agentstore.platform.manifest.LlmVendor;
import com.agentstore.platform.runtime.RuntimeApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Picks the key for an outgoing call: the platform's own, or the developer org's. Never falls back from one to the other. */
@Component
public class LlmKeyResolver {

    private final LlmProperties properties;
    private final ByokKeyService byokKeys;

    public LlmKeyResolver(LlmProperties properties, ByokKeyService byokKeys) {
        this.properties = properties;
        this.byokKeys = byokKeys;
    }

    public String resolve(LlmMode mode, LlmVendor vendor, UUID developerOrgId) {
        if (mode == LlmMode.PLATFORM) {
            return properties.platformKey(vendor).orElseThrow(() -> new RuntimeApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "LLM_PROVIDER_UNAVAILABLE",
                    "The platform has no " + vendor.wire() + " key configured"));
        }
        return byokKeys.reveal(developerOrgId, vendor).orElseThrow(() -> new RuntimeApiException(
                HttpStatus.BAD_REQUEST, "LLM_KEY_MISSING",
                "No " + vendor.wire() + " key is stored for this agent's developer organisation"));
    }
}
