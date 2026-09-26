package com.agentstore.platform.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;

import java.util.List;

/** RFC 7807 problem details plus the stable {@code code} the SDKs map to typed exceptions. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemBody(String type, String title, int status, String code, String detail, List<?> errors) {
    static ProblemBody of(HttpStatus status, String code, String detail) {
        return of(status, code, detail, null);
    }

    static ProblemBody of(HttpStatus status, String code, String detail, List<?> errors) {
        return new ProblemBody("about:blank", status.getReasonPhrase(), status.value(), code, detail, errors);
    }
}
