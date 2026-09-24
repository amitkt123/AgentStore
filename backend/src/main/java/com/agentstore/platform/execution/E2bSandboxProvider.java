package com.agentstore.platform.execution;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Direct E2B REST client.
 *
 * <p>Known limitation (spec §2.2 B2): this only creates the sandbox. It cannot start the agent process, because E2B
 * exposes in-sandbox command execution only through its JS/Python SDKs. Real runs therefore never call back and are
 * reclaimed by {@link RunReaper} at their deadline. WS5 replaces this with a sandbox-bridge provider; until then only
 * {@link FakeSandboxProvider} runs exercise the full loop.
 */
@Component
@Profile("e2b")
public class E2bSandboxProvider implements SandboxProvider {

    /** E2B: allow rules take precedence over deny rules, so deny-all + allowOut = allowlist only. */
    static final String ALL_TRAFFIC = "0.0.0.0/0";

    private final RestClient restClient;
    private final String apiKey;

    @Autowired
    public E2bSandboxProvider(RestClient.Builder builder,
                               @Value("${agentstore.e2b.base-url}") String baseUrl,
                               @Value("${agentstore.e2b.api-key}") String apiKey,
                               @Value("${agentstore.e2b.connect-timeout:PT5S}") Duration connectTimeout,
                               @Value("${agentstore.e2b.read-timeout:PT30S}") Duration readTimeout) {
        // Boot's auto-configured RestClient.Builder falls back to SimpleClientHttpRequestFactory in production
        // (httpclient5 is test-scoped), which has no timeout by default. An unbounded call here would hang the
        // single-threaded reaper and every other lifecycle call queued on the run row lock.
        this.restClient = builder.baseUrl(baseUrl)
                .requestFactory(timeoutRequestFactory(connectTimeout, readTimeout))
                .build();
        this.apiKey = apiKey;
    }

    /** Test-only: builds against a caller-supplied {@link RestClient.Builder} (e.g. one wired to a mock server) without
     * overriding whatever request factory the caller already configured on it. */
    E2bSandboxProvider(RestClient.Builder builder, String baseUrl, String apiKey) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    private static SimpleClientHttpRequestFactory timeoutRequestFactory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) connectTimeout.toMillis());
        requestFactory.setReadTimeout((int) readTimeout.toMillis());
        return requestFactory;
    }

    @Override
    public SandboxHandle create(SandboxSpec spec) {
        Map<String, Object> body = Map.of(
                "templateID", spec.templateId(),
                "timeout", spec.timeoutSeconds(),
                "envVars", spec.envVars(),
                "network", Map.of("allowOut", spec.allowOut(), "denyOut", List.of(ALL_TRAFFIC)));

        Map<?, ?> response = restClient.post()
                .uri("/sandboxes")
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        return new SandboxHandle((String) response.get("sandboxID"));
    }

    @Override
    public void kill(String sandboxId) {
        restClient.delete()
                .uri("/sandboxes/{id}", sandboxId)
                .header("X-API-Key", apiKey)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public SandboxStatus status(String sandboxId) {
        try {
            restClient.get()
                    .uri("/sandboxes/{id}", sandboxId)
                    .header("X-API-Key", apiKey)
                    .retrieve()
                    .toBodilessEntity();
            return SandboxStatus.RUNNING;
        } catch (HttpClientErrorException.NotFound e) {
            return SandboxStatus.EXITED;
        } catch (RestClientException e) {
            return SandboxStatus.UNKNOWN;
        }
    }
}
