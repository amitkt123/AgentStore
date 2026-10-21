package com.agentstore.platform.payments;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = {TopUpController.class, FakePaymentController.class, BankTransferController.class,
        RefundController.class})
public class PaymentApiErrors {

    @ExceptionHandler(PaymentApiException.class)
    ResponseEntity<Map<String, Object>> api(PaymentApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("message", e.getMessage());
        return ResponseEntity.status(e.status()).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return api(new PaymentApiException(HttpStatus.BAD_REQUEST, "AMOUNT_INVALID", "request body is malformed"));
    }
}
