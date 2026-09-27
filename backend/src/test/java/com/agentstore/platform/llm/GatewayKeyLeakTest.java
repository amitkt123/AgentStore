package com.agentstore.platform.llm;

import com.agentstore.platform.llm.StubProviderServer.Reply;
import com.agentstore.platform.manifest.LlmVendor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A provider that echoes the key and the prompt in its error body must not get either onto any surface we control. */
@ExtendWith(OutputCaptureExtension.class)
class GatewayKeyLeakTest extends AbstractLlmIntegrationTest {

    static final String KEY = "sk-dev-leak-check-7777";
    static final String PROMPT = "PROMPT-MARKER-42";
    static final String BODY_MARKER = "provider-body-marker";

    @Autowired ByokKeyService keys;

    @Test
    void neitherTheKeyNorTheProviderBodyNorThePromptLeaks(CapturedOutput output) {
        var agent = fx.runningAgent("valid-llm-byok-proxy.json");
        fx.verifyDeveloperOrg(agent.developerOrg());
        keys.put(agent.developerOrg().getId(), LlmVendor.ANTHROPIC, KEY);
        STUB.always(Reply.status(401, "{\"error\":{\"message\":\"invalid x-api-key " + KEY + "; " + BODY_MARKER
                + "; prompt: " + PROMPT + "\"}}"));

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, PROMPT + " please", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        List<String> surfaces = List.of(
                String.valueOf(response.getBody()),
                jdbc.queryForList("SELECT * FROM llm_call WHERE run_id = ?", agent.runId()).toString(),
                jdbc.queryForList("SELECT * FROM run_step WHERE run_id = ?", agent.runId()).toString(),
                output.getAll());
        for (String surface : surfaces) {
            assertThat(surface).doesNotContain(KEY).doesNotContain(BODY_MARKER).doesNotContain(PROMPT);
        }
        assertThat(output.getAll()).as("the capture works and the call was logged").contains("llm provider call provider=anthropic");
    }
}
