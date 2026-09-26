package com.agentstore.platform.build;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

@Component
@Profile("runner")
public class HttpBuildRunnerClient implements BuildRunnerClient {

    private final RestClient restClient;

    @Autowired
    public HttpBuildRunnerClient(RestClient.Builder builder,
                                 @Value("${agentstore.runner.base-url}") String baseUrl,
                                 @Value("${agentstore.runner.secret}") String secret,
                                 @Value("${agentstore.runner.connect-timeout:PT5S}") Duration connectTimeout,
                                 @Value("${agentstore.runner.build-read-timeout:PT30S}") Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("X-Runner-Secret", secret).build();
    }

    /** Test-only: keeps the caller's request factory (e.g. MockRestServiceServer). */
    HttpBuildRunnerClient(RestClient.Builder builder, String baseUrl, String secret) {
        this.restClient = builder.baseUrl(baseUrl).defaultHeader("X-Runner-Secret", secret).build();
    }

    @Override
    public RunnerBuild submit(RunnerBuildRequest r) {
        Map<String, Object> body = Map.of("buildId", r.buildId(), "runtime", r.runtime(), "bundleUrl", r.bundleUrl(),
                "bundleSha256", r.bundleSha256(), "entrypoint", r.entrypoint(), "logUploadUrl", r.logUploadUrl());
        return call(() -> restClient.post().uri("/builds").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(RunnerBuild.class));
    }

    @Override
    public RunnerBuild get(String buildId) {
        return call(() -> restClient.get().uri("/builds/{id}", buildId).retrieve().body(RunnerBuild.class));
    }

    /** Only the exception type is kept: a RestClientException's message can quote the request. */
    private static RunnerBuild call(Supplier<RunnerBuild> request) {
        RunnerBuild build;
        try {
            build = request.get();
        } catch (RestClientException e) {
            throw new BuildRunnerUnavailableException("Build runner unavailable (" + e.getClass().getSimpleName() + ")");
        }
        if (build == null || build.status() == null) {
            throw new BuildRunnerUnavailableException("Build runner returned an empty answer");
        }
        return build;
    }
}
