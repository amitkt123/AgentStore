package com.agentstore.platform.manifest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** The app hides ResponseStatusException reasons; manifest-patch failures need a stable {code, message} the
 * developer (and this plan's tests) can act on. */
@RestControllerAdvice
public class ManifestApiErrors {

    @ExceptionHandler(ManifestApiException.class)
    ResponseEntity<Map<String, String>> api(ManifestApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code(), "message", e.getMessage()));
    }
}
