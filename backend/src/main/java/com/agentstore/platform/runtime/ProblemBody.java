package com.agentstore.platform.runtime;

import org.springframework.http.HttpStatus;

/** RFC 7807 problem details plus the stable {@code code} the SDKs map to typed exceptions. */
public record ProblemBody(String type, String title, int status, String code, String detail) {
    static ProblemBody of(HttpStatus status, String code, String detail) {
        return new ProblemBody("about:blank", status.getReasonPhrase(), status.value(), code, detail);
    }
}
