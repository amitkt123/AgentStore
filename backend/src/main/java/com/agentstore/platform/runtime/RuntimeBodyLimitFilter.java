package com.agentstore.platform.runtime;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;

/**
 * Caps an agent's record-write body at the transport, before Jackson reads it into memory: a declared Content-Length
 * over the cap is refused outright, and a chunked body is cut off as it crosses the cap. Runs after RuntimeAuthFilter.
 */
public class RuntimeBodyLimitFilter extends OncePerRequestFilter {

    /** 5 MB of records plus the JSON envelope. */
    static final long MAX_WRITE_BODY_BYTES = 5L * 1024 * 1024 + 4096;
    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final String DETAIL = "A write may carry at most 5 MB";

    public static class PayloadTooLargeException extends IOException {
        PayloadTooLargeException() {
            super(DETAIL);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return !(HttpMethod.POST.matches(request.getMethod()) && MATCHER.match("/runtime/v1/resources/*/records", path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_WRITE_BODY_BYTES) {
            ProblemResponses.write(response, HttpStatus.PAYLOAD_TOO_LARGE, "WRITE_TOO_LARGE", DETAIL, null);
            return;
        }
        chain.doFilter(new LimitedRequest(request), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long seen;

                @Override
                public int read() throws IOException {
                    int b = delegate.read();
                    if (b >= 0) {
                        count(1);
                    }
                    return b;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int n = delegate.read(buffer, offset, length);
                    if (n > 0) {
                        count(n);
                    }
                    return n;
                }

                private void count(int n) throws IOException {
                    seen += n;
                    if (seen > MAX_WRITE_BODY_BYTES) {
                        throw new PayloadTooLargeException();
                    }
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    delegate.setReadListener(listener);
                }
            };
        }
    }
}
