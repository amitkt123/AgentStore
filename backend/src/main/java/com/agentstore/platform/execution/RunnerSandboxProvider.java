package com.agentstore.platform.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Talks to the internal sandbox-runner service (WS5 spec §3). The runner owns all vendor credentials. */
@Component
@Profile("runner")
public class RunnerSandboxProvider implements SandboxProvider {

    private static final Logger log = LoggerFactory.getLogger(RunnerSandboxProvider.class);

    private final RestClient restClient;

    @Autowired
    public RunnerSandboxProvider(RestClient.Builder builder,
                                 @Value("${agentstore.runner.base-url}") String baseUrl,
                                 @Value("${agentstore.runner.secret}") String secret,
                                 @Value("${agentstore.runner.connect-timeout:PT5S}") Duration connectTimeout,
                                 // Worst-case create is the vendor backend's REST call (30s) + the runner's
                                 // agent start (60s); Spring must outlast that.
                                 @Value("${agentstore.runner.read-timeout:PT120S}") Duration readTimeout) {
        // Unbounded calls would hang the reaper and every lifecycle call waiting on the run row lock.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("X-Runner-Secret", secret).build();
    }

    /** Test-only: keeps the caller's request factory (e.g. MockRestServiceServer). */
    RunnerSandboxProvider(RestClient.Builder builder, String baseUrl, String secret) {
        this.restClient = builder.baseUrl(baseUrl).defaultHeader("X-Runner-Secret", secret).build();
    }

    @Override
    public SandboxHandle create(SandboxSpec spec) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", spec.requestId());
        body.put("imageRef", spec.imageRef());
        body.put("command", spec.command());
        body.put("env", spec.envVars());
        body.put("allowOut", spec.allowOut());
        body.put("cpu", spec.cpu());
        body.put("memoryMb", spec.memoryMb());
        body.put("timeoutSeconds", spec.timeoutSeconds());
        try {
            Map<?, ?> response = restClient.post().uri("/sandboxes")
                    .contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(Map.class);
            return new SandboxHandle((String) response.get("sandboxId"));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                Map<?, ?> error = e.getResponseBodyAs(Map.class);
                throw new SandboxCapacityException(error == null ? "CAPACITY_EXHAUSTED" : String.valueOf(error.get("code")));
            }
            throw e;
        }
    }

    @Override
    public void kill(String sandboxId) {
        restClient.delete().uri("/sandboxes/{id}", sandboxId).retrieve().toBodilessEntity();
    }

    @Override
    public SandboxStatus status(String sandboxId) {
        try {
            Map<?, ?> body = restClient.get().uri("/sandboxes/{id}", sandboxId).retrieve().body(Map.class);
            SandboxStatus.State state = SandboxStatus.State.valueOf(String.valueOf(body.get("state")));
            Object code = body.get("exitCode");
            return new SandboxStatus(state, code == null ? null : ((Number) code).intValue());
        } catch (RestClientException | IllegalArgumentException | NullPointerException | ClassCastException e) {
            // Exception type only: the message of a RestClientException can carry the response body.
            log.warn("Sandbox {} status unavailable from the sandbox runner ({}); reporting UNKNOWN",
                    sandboxId, e.getClass().getSimpleName());
            return SandboxStatus.UNKNOWN;
        }
    }
}
