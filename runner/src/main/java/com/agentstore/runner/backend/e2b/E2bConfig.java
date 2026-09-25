package com.agentstore.runner.backend.e2b;

import com.agentstore.runner.RunnerProperties;
import com.agentstore.runner.backend.IsolationBackend;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnProperty(name = "agentstore.runner.backend", havingValue = "e2b")
public class E2bConfig {

    @Bean
    IsolationBackend e2bBackend(RestClient.Builder builder, RunnerProperties props) {
        RunnerProperties.E2b e2b = props.e2b();
        if (e2b == null || !StringUtils.hasText(e2b.apiKey())) {
            throw new IllegalStateException("agentstore.runner.e2b.api-key (E2B_API_KEY) is required when backend=e2b");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) e2b.httpTimeout().toMillis());
        factory.setReadTimeout((int) e2b.httpTimeout().toMillis());
        E2bRestClient rest = new E2bRestClient(builder.requestFactory(factory), e2b.baseUrl(), e2b.apiKey());
        ShimClient shim = new ShimClient(e2b.shimCommand(), e2b.shimTimeout(), e2b.apiKey());
        return new E2bBackend(rest, shim, props.runnerId());
    }
}
