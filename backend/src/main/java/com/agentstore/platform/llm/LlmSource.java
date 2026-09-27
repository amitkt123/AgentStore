package com.agentstore.platform.llm;

/** Where a call's answer came from. Only PLATFORM calls record a cost. */
public enum LlmSource { PLATFORM, BYOK_PROXY, MOCK, REPLAY }
