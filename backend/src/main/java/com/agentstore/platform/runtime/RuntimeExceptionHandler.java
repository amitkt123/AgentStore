package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.RunTerminatedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackages = "com.agentstore.platform.runtime")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RuntimeExceptionHandler {

    private static ResponseEntity<ProblemBody> problem(HttpStatus status, String code, String detail) {
        return problem(status, code, detail, null);
    }

    private static ResponseEntity<ProblemBody> problem(HttpStatus status, String code, String detail, java.util.List<?> errors) {
        return ResponseEntity.status(status)
                .contentType(MediaType.parseMediaType(ProblemResponses.PROBLEM_JSON))
                .body(ProblemBody.of(status, code, detail, errors));
    }

    @ExceptionHandler(RuntimeApiException.class)
    ResponseEntity<ProblemBody> api(RuntimeApiException e) {
        return problem(e.status(), e.code(), e.getMessage(), e.errors());
    }

    @ExceptionHandler(com.agentstore.platform.storage.StorageException.class)
    ResponseEntity<ProblemBody> storage(com.agentstore.platform.storage.StorageException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE",
                "The data store is unavailable right now; retry shortly");
    }

    @ExceptionHandler(RunTerminatedException.class)
    ResponseEntity<ProblemBody> terminated(RunTerminatedException e) {
        return problem(HttpStatus.CONFLICT, "RUN_TERMINATED", e.getReason());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemBody> status(ResponseStatusException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String code = switch (status) {
            case BAD_REQUEST -> "BAD_REQUEST";
            case NOT_FOUND -> "NOT_FOUND";
            case CONFLICT -> "CONFLICT";
            case PAYMENT_REQUIRED -> "PAYMENT_REQUIRED";
            default -> "ERROR";
        };
        return problem(status, code, e.getReason());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemBody> invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage()).sorted().reduce((a, b) -> a + "; " + b)
                .orElse("Request is not valid");
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemBody> badParameter(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Parameter '" + e.getName() + "' is not valid");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemBody> unreadable(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Request body is missing or not valid JSON");
    }
}
