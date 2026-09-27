package com.agentstore.platform.llm;

import org.springframework.http.HttpStatus;

/** A developer-facing LLM failure with a stable code (BYOK verification, key validation). Never carries a key. */
public class LlmApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public LlmApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
