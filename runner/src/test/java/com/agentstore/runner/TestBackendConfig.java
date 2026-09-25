package com.agentstore.runner;

import com.agentstore.runner.backend.FakeBackend;
import com.agentstore.runner.backend.IsolationBackend;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class TestBackendConfig {
    @Bean
    @ConditionalOnProperty(name = "agentstore.runner.backend", havingValue = "fake")
    FakeBackend fakeBackend(RunnerProperties props) {
        return new FakeBackend(props.runnerId());
    }
}
