package com.agentstore.platform.llm;

import com.agentstore.platform.execution.RunLlmMode;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.llm.StubProviderServer.Reply;
import com.agentstore.platform.manifest.LlmVendor;
import com.agentstore.platform.runtime.RuntimeFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The whole gateway, end to end, through the public runtime API. */
class LlmGatewayAcceptanceTest extends AbstractLlmIntegrationTest {

    @Autowired ByokKeyService keys;
    @Autowired LlmRecordingRetentionService retention;

    @SuppressWarnings("rawtypes")
    static String textOf(ResponseEntity<Map> response) {
        return (String) ((Map<?, ?>) ((List<?>) response.getBody().get("content")).get(0)).get("text");
    }

    @SuppressWarnings("rawtypes")
    Map<?, ?> context(String token) {
        ResponseEntity<Map> response = rest.exchange("http://localhost:" + port + "/runtime/v1/context", HttpMethod.GET,
                new HttpEntity<>(RuntimeFixtures.bearer(token)), Map.class);
        return (Map<?, ?>) ((Map<?, ?>) response.getBody().get("limits")).get("llm");
    }

    @Test
    void theWholeGatewayFlow() throws Exception {
        // 1. A platform run: forwarded with the platform key, metered, costed, traced, visible in /context.
        var platform = fx.runningAgent("valid-llm-platform.json");
        STUB.enqueue(Reply.anthropicText("hello", 100, 40));
        assertThat(chat(platform.token(), request(LlmFixtures.CLAUDE, "hi", 500)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(STUB.received().get(0).header("x-api-key")).isEqualTo(PLATFORM_ANTHROPIC_KEY);
        assertThat(fx.tokens(platform.runId())).containsExactly(140, 0);
        assertThat(llmCalls.findByRunIdOrderByCreatedAtAsc(platform.runId()).get(0).getCost()).isEqualByComparingTo("0.0009");
        assertThat(llmSteps(platform.runId())).hasSize(1);
        assertThat(context(platform.token()).get("tokensRemaining")).isEqualTo(99_860);

        // 2. A verified developer brings their own key (byok_proxy): the provider sees it, and it costs the platform nothing.
        var proxy = fx.runningAgent("valid-llm-byok-proxy.json");
        fx.verifyDeveloperOrg(proxy.developerOrg());
        keys.put(proxy.developerOrg().getId(), LlmVendor.ANTHROPIC, "sk-dev-acceptance-0001");
        STUB.enqueue(Reply.anthropicText("proxied", 10, 5));
        assertThat(chat(proxy.token(), request(LlmFixtures.CLAUDE, "hi", 500)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(STUB.received().get(1).header("x-api-key")).isEqualTo("sk-dev-acceptance-0001");
        var proxied = llmCalls.findByRunIdOrderByCreatedAtAsc(proxy.runId()).get(0);
        assertThat(proxied.getSource()).isEqualTo(LlmSource.BYOK_PROXY);
        assertThat(proxied.getCost()).isEqualByComparingTo("0");

        // 3. A sandbox run records its real call; a replay run serves it back free; a mock run needs no provider at all.
        var sandbox = fx.runningAgent("valid-llm-platform.json");
        fx.configureRun(sandbox.runId(), RunMode.SANDBOX, null, null, null);
        STUB.enqueue(Reply.anthropicText("recorded", 20, 10));
        assertThat(chat(sandbox.token(), request(LlmFixtures.CLAUDE, "recorded question", 100)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(llmCalls.findByRunIdOrderByCreatedAtAsc(sandbox.runId()).get(0).getRequestKey()).isNotNull();
        int providerCalls = STUB.received().size();

        var replay = fx.runningAgent("valid-llm-platform.json");
        fx.configureRun(replay.runId(), RunMode.SANDBOX, RunLlmMode.REPLAY, sandbox.runId(), null);
        assertThat(textOf(chat(replay.token(), request(LlmFixtures.CLAUDE, "recorded question", 100)))).isEqualTo("recorded");
        var miss = chat(replay.token(), request(LlmFixtures.CLAUDE, "recorded question", 100));
        assertThat(miss.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(miss.getBody().get("code")).isEqualTo("REPLAY_MISS");

        var mock = fx.runningAgent("valid-llm-platform.json");
        fx.configureRun(mock.runId(), RunMode.SANDBOX, RunLlmMode.MOCK, null, null);
        assertThat(textOf(chat(mock.token(), request(LlmFixtures.CLAUDE, "anything", 100)))).isEqualTo("[mock response]");
        assertThat(STUB.received()).as("neither replay nor mock reached a provider").hasSize(providerCalls);
        assertThat(fx.tokens(replay.runId())).containsExactly(0, 0);
        assertThat(fx.tokens(mock.runId())).containsExactly(0, 0);

        // 4. After the retention window the recording is purged and replaying it is gone.
        jdbc.update("UPDATE llm_call SET recording_expires_at = now() - interval '1 hour' WHERE run_id = ?", sandbox.runId());
        retention.purgeOnce();
        var late = fx.runningAgent("valid-llm-platform.json");
        fx.configureRun(late.runId(), RunMode.SANDBOX, RunLlmMode.REPLAY, sandbox.runId(), null);
        var gone = chat(late.token(), request(LlmFixtures.CLAUDE, "recorded question", 100));
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(gone.getBody().get("code")).isEqualTo("REPLAY_EXPIRED");

        // 5. A production run can never be mocked or replayed.
        assertThatThrownBy(() -> fx.configureRun(platform.runId(), RunMode.PRODUCTION, RunLlmMode.MOCK, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 6. The token budget holds: a second big call on a small budget is refused and never reaches the provider.
        var small = fx.runningAgent("valid-llm-small-budget.json");
        STUB.enqueue(Reply.anthropicText("ok", 30, 20));
        assertThat(chat(small.token(), request(LlmFixtures.CLAUDE, "hi", 950)).getStatusCode()).isEqualTo(HttpStatus.OK);
        int beforeRejection = STUB.received().size();
        var refused = chat(small.token(), request(LlmFixtures.CLAUDE, "hi", 950));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(refused.getBody().get("code")).isEqualTo("BUDGET_EXCEEDED");
        assertThat(STUB.received()).hasSize(beforeRejection);
        assertThat(fx.tokens(small.runId())).containsExactly(50, 0);
    }
}
