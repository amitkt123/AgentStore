package com.agentstore.platform.llm;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** The app hides {@code ResponseStatusException} reasons, so LLM developer endpoints answer {@code {code, message}}. */
@RestControllerAdvice
public class LlmApiErrors {

    @ExceptionHandler(LlmApiException.class)
    ResponseEntity<Map<String, Object>> api(LlmApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code(), "message", e.getMessage()));
    }
}
