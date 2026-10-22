package com.agentstore.platform.payouts;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Scoped to the payouts controllers; later tasks add them to {@code assignableTypes}. */
@RestControllerAdvice(assignableTypes = {})
public class PayoutApiErrors {

    @ExceptionHandler(PayoutApiException.class)
    ResponseEntity<Map<String, Object>> api(PayoutApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("message", e.getMessage());
        return ResponseEntity.status(e.status()).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return api(new PayoutApiException(HttpStatus.BAD_REQUEST, "INVALID_FIELD", "request body is malformed"));
    }
}
