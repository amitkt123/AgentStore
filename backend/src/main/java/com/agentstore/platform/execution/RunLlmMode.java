package com.agentstore.platform.execution;

/** How a non-production run's LLM calls are served instead of hitting a provider; null on the run means real. */
public enum RunLlmMode { MOCK, REPLAY }
