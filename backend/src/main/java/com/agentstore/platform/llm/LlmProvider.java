package com.agentstore.platform.llm;

import com.agentstore.platform.manifest.LlmVendor;

/**
 * One provider's API, normalized in and out. Exactly one HTTP call per invocation: retries, the circuit breaker and
 * the error contract belong to {@link ProviderClient}.
 */
public interface LlmProvider {

    LlmVendor vendor();

    LlmResponse chat(LlmRequest request, String apiKey) throws ProviderCallException;
}
