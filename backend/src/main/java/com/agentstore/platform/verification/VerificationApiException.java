package com.agentstore.platform.verification;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/** A developer-verification failure with a stable code; {@code extra} fields are added to the response body. */
public class VerificationApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> extra;

    public VerificationApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public VerificationApiException(HttpStatus status, String code, String message, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.code = code;
        this.extra = extra;
    }

    public static VerificationApiException locked() {
        return new VerificationApiException(HttpStatus.CONFLICT, "VERIFICATION_LOCKED",
                "This verification cannot be edited in its current status");
    }

    public static VerificationApiException notPending() {
        return new VerificationApiException(HttpStatus.CONFLICT, "VERIFICATION_NOT_PENDING",
                "This verification is not awaiting a decision");
    }

    public static VerificationApiException invalidField(String message) {
        return new VerificationApiException(HttpStatus.BAD_REQUEST, "INVALID_FIELD", message);
    }

    public static VerificationApiException incomplete(List<String> missingSteps) {
        return new VerificationApiException(HttpStatus.BAD_REQUEST, "VERIFICATION_INCOMPLETE",
                "Complete all steps before submitting: " + missingSteps, Map.of("missingSteps", missingSteps));
    }

    public static VerificationApiException documentTooLarge() {
        return new VerificationApiException(HttpStatus.PAYLOAD_TOO_LARGE, "DOCUMENT_TOO_LARGE",
                "PAN document must be at most 5 MB");
    }

    public static VerificationApiException unsupportedDocumentType() {
        return new VerificationApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_DOCUMENT_TYPE",
                "PAN document must be image/jpeg, image/png, or application/pdf");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> extra() {
        return extra;
    }
}
