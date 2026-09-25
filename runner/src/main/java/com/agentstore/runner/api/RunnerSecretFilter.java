package com.agentstore.runner.api;

import com.agentstore.runner.RunnerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class RunnerSecretFilter extends OncePerRequestFilter {

    private final byte[] secret;

    public RunnerSecretFilter(RunnerProperties props) {
        this.secret = props.secret().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().equals("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("X-Runner-Secret");
        byte[] given = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(given, secret)) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid runner secret\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
