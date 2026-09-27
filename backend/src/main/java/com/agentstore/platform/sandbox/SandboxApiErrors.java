package com.agentstore.platform.sandbox;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** The app hides {@code ResponseStatusException} reasons, so sandbox endpoints answer {@code {code, message, ...}}. */
@RestControllerAdvice
public class SandboxApiErrors {

    @ExceptionHandler(SandboxApiException.class)
    ResponseEntity<Map<String, Object>> api(SandboxApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("message", e.getMessage());
        body.putAll(e.extra());
        return ResponseEntity.status(e.status()).body(body);
    }
}
