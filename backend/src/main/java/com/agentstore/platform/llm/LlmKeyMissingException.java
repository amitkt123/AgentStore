package com.agentstore.platform.llm;

import com.agentstore.platform.manifest.LlmVendor;

/** A {@code byok_direct} launch found no stored credential for the provider. Carries no key material. */
public class LlmKeyMissingException extends RuntimeException {

    public LlmKeyMissingException(LlmVendor vendor) {
        super("No " + vendor.wire() + " key is stored for this agent's developer organisation");
    }
}
