package com.agentstore.platform.llm;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.RunStep;
import com.agentstore.platform.execution.RunStepKind;
import com.agentstore.platform.execution.RunStepRepository;
import com.agentstore.platform.runtime.RuntimeFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Gateway integration tests: Postgres, Redis and SeaweedFS from the parents, plus a stub LLM provider. */
public abstract class AbstractLlmIntegrationTest extends AbstractStorageIntegrationTest {

    public static final String PLATFORM_ANTHROPIC_KEY = "platform-anthropic-key-0001";
    public static final String PLATFORM_OPENAI_KEY = "platform-openai-key-0001";

    protected static final StubProviderServer STUB = StubProviderServer.start();

    @DynamicPropertySource
    static void llmProperties(DynamicPropertyRegistry registry) {
        registry.add("agentstore.llm.providers.anthropic.base-url", STUB::baseUrl);
        registry.add("agentstore.llm.providers.anthropic.api-key", () -> PLATFORM_ANTHROPIC_KEY);
        registry.add("agentstore.llm.providers.openai.base-url", STUB::baseUrl);
        registry.add("agentstore.llm.providers.openai.api-key", () -> PLATFORM_OPENAI_KEY);
        registry.add("agentstore.llm.retry-backoff", () -> "PT0S");
    }

    @LocalServerPort protected int port;
    @Autowired protected TestRestTemplate rest;

    /**
     * Apache HttpClient 5 (the TestRestTemplate default here, and it replaces any Apache factory given to it) silently
     * re-sends a POST once on 429/503, which would make the gateway look as if it called the provider twice per
     * request. The JDK client used here never retries.
     */
    protected final TestRestTemplate chatClient = new TestRestTemplate(
            new RestTemplateBuilder().requestFactory(() -> new JdkClientHttpRequestFactory()));
    @Autowired protected LlmFixtures fx;
    @Autowired protected ProviderClient providerClient;
    @Autowired protected LlmCallRepository llmCalls;
    @Autowired protected RunStepRepository runSteps;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper json;

    @BeforeEach
    void resetLlmState() {
        STUB.reset();
        providerClient.resetBreakers();
        fx.ensureModels();
    }

    @SuppressWarnings("rawtypes")
    protected ResponseEntity<Map> chat(String token, Object body) {
        return chatClient.exchange("http://localhost:" + port + "/runtime/v1/llm/chat", HttpMethod.POST,
                new HttpEntity<>(body, RuntimeFixtures.bearer(token)), Map.class);
    }

    protected static Map<String, Object> request(String model, String text, int maxTokens) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", List.of(Map.of("role", "user", "content", text)));
        request.put("maxTokens", maxTokens);
        return request;
    }

    /** The details of the run's LLM_CALL steps, oldest first. */
    protected List<JsonNode> llmSteps(UUID runId) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (RunStep step : runSteps.findByRunIdOrderByCreatedAtAsc(runId)) {
            if (step.getKind() == RunStepKind.LLM_CALL) {
                out.add(json.readTree(step.getDetail()));
            }
        }
        return out;
    }
}
