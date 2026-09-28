package com.agentstore.platform.verification;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = {DeveloperVerificationController.class, AdminVerificationController.class})
public class VerificationApiErrors {

    @ExceptionHandler(VerificationApiException.class)
    ResponseEntity<Map<String, Object>> api(VerificationApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("message", e.getMessage());
        body.putAll(e.extra());
        return ResponseEntity.status(e.status()).body(body);
    }

    /** A malformed request body (e.g. unrecognized {@code step} text) is a clean 400, not Spring's default error body. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return api(VerificationApiException.invalidField("request body is malformed"));
    }
}
