package com.agentstore.runner;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties("agentstore.runner")
public record RunnerProperties(@NotBlank String secret, @NotBlank String runnerId, @NotBlank String backend,
                               @NotBlank String springBaseUrl, @Valid @NotNull Budget budget,
                               @Valid @NotNull Build build, E2b e2b) {

    static final String REDACTED = "***";

    /** Redacts {@code secret} so a logged or printed properties object never carries it. */
    @Override
    public String toString() {
        return "RunnerProperties[secret=" + REDACTED + ", runnerId=" + runnerId + ", backend=" + backend
                + ", springBaseUrl=" + springBaseUrl + ", budget=" + budget + ", build=" + build + ", e2b=" + e2b + "]";
    }

    public record Budget(@Min(1) int maxConcurrent, @NotNull Duration maxLifetime, @NotNull Duration monthlyLimit,
                         @NotNull Path stateFile) {}

    public record Build(@NotEmpty List<String> gitleaksCommand, @NotNull Duration timeout, @NotNull Duration scanTimeout,
                        @NotNull Duration downloadTimeout, @NotNull Duration logFlushInterval, @NotNull Path workDir) {}

    public record E2b(String apiKey, String baseUrl, List<String> shimCommand, Duration shimTimeout,
                      Duration httpTimeout, Duration buildTimeout) {

        /** Redacts {@code apiKey}. */
        @Override
        public String toString() {
            return "E2b[apiKey=" + REDACTED + ", baseUrl=" + baseUrl + ", shimCommand=" + shimCommand
                    + ", shimTimeout=" + shimTimeout + ", httpTimeout=" + httpTimeout
                    + ", buildTimeout=" + buildTimeout + "]";
        }
    }
}
