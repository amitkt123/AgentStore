package com.agentstore.runner;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties("agentstore.runner")
public record RunnerProperties(@NotBlank String secret, @NotBlank String runnerId, @NotBlank String backend,
                               @NotBlank String springBaseUrl, @Valid @NotNull Budget budget, E2b e2b) {

    public record Budget(@Min(1) int maxConcurrent, @NotNull Duration maxLifetime, @NotNull Duration monthlyLimit,
                         @NotNull Path stateFile) {}

    public record E2b(String apiKey, String baseUrl, List<String> shimCommand, Duration shimTimeout,
                      Duration httpTimeout) {}
}
