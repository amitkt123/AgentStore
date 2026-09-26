package com.agentstore.platform.identity;

import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.runtime.RuntimeAuthFilter;
import com.agentstore.platform.runtime.RuntimeBodyLimitFilter;
import com.agentstore.platform.runtime.RuntimeRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ApiKeyAuthFilter apiKeyAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, ApiKeyAuthFilter apiKeyAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.apiKeyAuthFilter = apiKeyAuthFilter;
    }

    /** The agent-facing API: no JWT, no API key; only a run token, checked by RuntimeAuthFilter. */
    @Bean
    @Order(1)
    SecurityFilterChain runtimeFilterChain(HttpSecurity http, RunRepository runRepository,
                                           ManifestParser manifestParser, RuntimeRateLimiter rateLimiter) throws Exception {
        http.securityMatcher("/runtime/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(handling -> handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .addFilterBefore(new RuntimeAuthFilter(runRepository, manifestParser, rateLimiter),
                    UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new RuntimeBodyLimitFilter(), RuntimeAuthFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(handling -> handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/v1/auth/**", "/api/v1/invitations/**", "/actuator/**", "/internal/**").permitAll()
                    .requestMatchers("/api/v1/**").authenticated()
                    .anyRequest().permitAll())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
