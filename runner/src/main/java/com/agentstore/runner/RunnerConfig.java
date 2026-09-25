package com.agentstore.runner;

import com.agentstore.runner.backend.IsolationBackend;
import com.agentstore.runner.sandbox.BudgetGuard;
import com.agentstore.runner.sandbox.RunnerReaper;
import com.agentstore.runner.sandbox.SandboxService;
import com.agentstore.runner.sandbox.SpringRunsClient;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Configuration
public class RunnerConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    BudgetGuard budgetGuard(RunnerProperties props, Clock clock) {
        var b = props.budget();
        return new BudgetGuard(b.maxConcurrent(), b.monthlyLimit(), b.stateFile(), clock);
    }

    @Bean
    SandboxService sandboxService(IsolationBackend backend, BudgetGuard budget, RunnerProperties props, Clock clock) {
        return new SandboxService(backend, budget, props.runnerId(), props.budget().maxLifetime(), clock);
    }

    @Bean
    SpringRunsClient springRunsClient(RestClient.Builder builder, RunnerProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        RestClient client = builder.baseUrl(props.springBaseUrl()).requestFactory(factory).build();
        return () -> {
            try {
                Map<?, ?> body = client.get().uri("/internal/runs/active-ids")
                        .header("X-Runner-Secret", props.secret())
                        .retrieve().body(Map.class);
                Set<String> ids = new HashSet<>();
                for (Object id : (List<?>) body.get("runIds")) {
                    ids.add(String.valueOf(id));
                }
                return Optional.of(ids);
            } catch (RestClientException | NullPointerException | ClassCastException e) {
                LoggerFactory.getLogger(RunnerConfig.class).warn("Could not fetch active runs from Spring: {}",
                        e.getMessage());
                return Optional.empty();
            }
        };
    }

    @Bean
    RunnerReaper runnerReaper(IsolationBackend backend, SandboxService service, BudgetGuard budget,
                              SpringRunsClient spring, Clock clock) {
        return new RunnerReaper(backend, service, budget, spring, clock);
    }
}
