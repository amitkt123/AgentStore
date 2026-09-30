package com.agentstore.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.time.Duration;

/** Writes a problem+json response from inside a servlet filter, where controller advice cannot reach. */
public final class ProblemResponses {

    static final String PROBLEM_JSON = "application/problem+json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProblemResponses() {
    }

    public static void write(HttpServletResponse response, HttpStatus status, String code, String detail, Duration retryAfter)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(PROBLEM_JSON);
        response.setCharacterEncoding("UTF-8");
        if (retryAfter != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, (retryAfter.toMillis() + 999) / 1000)));
        }
        MAPPER.writeValue(response.getOutputStream(), ProblemBody.of(status, code, detail));
    }
}
