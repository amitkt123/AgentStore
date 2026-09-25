package com.agentstore.runner.api;

import com.agentstore.runner.backend.BackendException;
import com.agentstore.runner.sandbox.BudgetExhaustedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(BudgetExhaustedException.class)
    ResponseEntity<ApiError> budget(BudgetExhaustedException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ApiError(e.code(), e.getMessage()));
    }

    /** Backend messages are written secret-free, but the body stays generic anyway. */
    @ExceptionHandler(BackendException.class)
    ResponseEntity<ApiError> backend(BackendException e) {
        log.warn("Sandbox backend failure: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("BACKEND_ERROR", "Sandbox backend failed"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "Invalid request"));
    }
}
