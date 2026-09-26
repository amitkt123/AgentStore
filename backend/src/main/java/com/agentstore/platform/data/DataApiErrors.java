package com.agentstore.platform.data;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The app hides {@code ResponseStatusException} reasons, so the data plane's buyer endpoints answer
 * {@code {code, message, ...}} explicitly. It only fires for these exception types.
 */
@RestControllerAdvice
public class DataApiErrors {

    @ExceptionHandler(DataApiException.class)
    ResponseEntity<Map<String, Object>> api(DataApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("message", e.getMessage());
        body.putAll(e.extra());
        return ResponseEntity.status(e.status()).body(body);
    }

    @ExceptionHandler(IngestionException.class)
    ResponseEntity<Map<String, Object>> ingestion(IngestionException e) {
        return api(new DataApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILE", e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        return api(new DataApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                "The file is larger than the upload limit"));
    }
}
