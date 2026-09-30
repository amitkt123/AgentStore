package com.agentstore.platform.execution;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** The app hides {@code ResponseStatusException} reasons, so a refused decision answers {@code {code, message}}. */
@RestControllerAdvice
public class GateApiErrors {

    @ExceptionHandler(NotAnApproverException.class)
    ResponseEntity<Map<String, Object>> notAnApprover(NotAnApproverException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("code", NotAnApproverException.CODE, "message", e.getReason()));
    }
}
