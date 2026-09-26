package com.agentstore.platform.build;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Bundle rejections carry a reason the developer must see (spec §1: "a readable reason"). The app hides
 * ResponseStatusException reasons, so this advice returns them explicitly, only for these controllers.
 */
@RestControllerAdvice(assignableTypes = BundleController.class)
public class BuildApiErrors {

    @ExceptionHandler(BundleRejectedException.class)
    ResponseEntity<Map<String, String>> rejected(BundleRejectedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("code", BuildFailureCodes.INVALID_BUNDLE, "message", e.getMessage()));
    }
}
