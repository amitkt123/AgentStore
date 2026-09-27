package com.agentstore.platform.llm;

import com.agentstore.platform.execution.RunLlmMode;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.llm.StubProviderServer.Reply;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayReplayTest extends AbstractLlmIntegrationTest {

    @Autowired LlmRequestNormalizer normalizer;
    @Autowired LlmRecordingRetentionService retention;

    @SuppressWarnings("rawtypes")
    static String textOf(ResponseEntity<Map> response) {
        return (String) ((Map<?, ?>) ((List<?>) response.getBody().get("content")).get(0)).get("text");
    }

    String hashOf(String text) {
        return normalizer.normalize(request(LlmFixtures.CLAUDE, text, 100)).hash();
    }

    /** A SANDBOX run whose real calls are recorded: "same" twice (first, second) and "different" once (other). */
    LlmFixtures.LlmAgent recordedSource(RunMode mode) {
        var source = fx.runningAgent("valid-llm-platform.json");
        fx.configureRun(source.runId(), mode, null, null, null);
        STUB.enqueue(Reply.anthropicText("first", 10, 5), Reply.anthropicText("second", 20, 6),
                Reply.anthropicText("other", 30, 7));
        chat(source.token(), request(LlmFixtures.CLAUDE, "same", 100));
        chat(source.token(), request(LlmFixtures.CLAUDE, "same", 100));
        chat(source.token(), request(LlmFixtures.CLAUDE, "different", 100));
        return source;
    }

    LlmFixtures.LlmAgent replayer(UUID sourceRunId) {
        var replay = fx.runningAgent("valid-llm-platform.json");
        fx.configureRun(replay.runId(), RunMode.SANDBOX, RunLlmMode.REPLAY, sourceRunId, null);
        return replay;
    }

    @Test
    void duplicatesAreConsumedInRecordedOrderForFree() throws Exception {
        var source = recordedSource(RunMode.SANDBOX);
        int providerCalls = STUB.received().size();
        assertThat(providerCalls).isEqualTo(3);
        var replay = replayer(source.runId());

        assertThat(textOf(chat(replay.token(), request(LlmFixtures.CLAUDE, "same", 100)))).isEqualTo("first");
        assertThat(textOf(chat(replay.token(), request(LlmFixtures.CLAUDE, "same", 100)))).isEqualTo("second");
        assertThat(textOf(chat(replay.token(), request(LlmFixtures.CLAUDE, "different", 100)))).isEqualTo("other");

        assertThat(STUB.received()).as("replay never reaches a provider").hasSize(providerCalls);
        var rows = llmCalls.findByRunIdOrderByCreatedAtAsc(replay.runId());
        assertThat(rows).extracting(LlmCall::getSource).containsOnly(LlmSource.REPLAY);
        assertThat(rows).extracting(LlmCall::getStatus).containsOnly(LlmCallStatus.OK);
        assertThat(rows).extracting(LlmCall::getReplayOrdinal).containsExactly(1, 2, 1);
        assertThat(rows).extracting(LlmCall::getInputTokens).containsExactly(10, 20, 30);
        assertThat(rows).extracting(LlmCall::getOutputTokens).containsExactly(5, 6, 7);
        assertThat(rows).allSatisfy(r -> assertThat(r.getCost()).isEqualByComparingTo("0"));
        assertThat(rows).as("a replayed call in a SANDBOX run is itself recorded").allSatisfy(r -> {
            assertThat(r.getRequestKey()).isNotNull();
            assertThat(r.getRecordingExpiresAt()).isNotNull();
        });
        assertThat(fx.tokens(replay.runId())).as("replayed tokens do not count towards the budget").containsExactly(0, 0);
        assertThat(llmSteps(replay.runId())).extracting(s -> s.get("source").asText()).containsOnly("REPLAY");
    }

    @Test
    void theRequestAfterTheLastRecordedDuplicateIsAMissNeverAPaidCall() throws Exception {
        var source = recordedSource(RunMode.SANDBOX);
        int providerCalls = STUB.received().size();
        var replay = replayer(source.runId());
        chat(replay.token(), request(LlmFixtures.CLAUDE, "same", 100));
        chat(replay.token(), request(LlmFixtures.CLAUDE, "same", 100));

        var third = chat(replay.token(), request(LlmFixtures.CLAUDE, "same", 100));
        var fourth = chat(replay.token(), request(LlmFixtures.CLAUDE, "same", 100));

        for (var miss : List.of(third, fourth)) {
            assertThat(miss.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(miss.getBody().get("code")).isEqualTo("REPLAY_MISS");
            assertThat(miss.getBody().get("detail").toString()).contains(hashOf("same"));
            assertThat(((Map<?, ?>) ((List<?>) miss.getBody().get("errors")).get(0)).get("requestHash"))
                    .isEqualTo(hashOf("same"));
        }
        assertThat(STUB.received()).hasSize(providerCalls);
        var rows = llmCalls.findByRunIdOrderByCreatedAtAsc(replay.runId());
        assertThat(rows).extracting(LlmCall::getStatus).containsExactly(LlmCallStatus.OK, LlmCallStatus.OK,
                LlmCallStatus.REPLAY_MISS, LlmCallStatus.REPLAY_MISS);
        assertThat(rows.get(2).getReplayOrdinal()).as("a miss never consumes an ordinal").isNull();
        assertThat(rows.get(2).getErrorCode()).isEqualTo("REPLAY_MISS");
        assertThat(llmSteps(replay.runId()).get(2).get("status").asText()).isEqualTo("REPLAY_MISS");
    }

    @Test
    void aRequestThatWasNeverRecordedIsAMiss() throws Exception {
        var source = recordedSource(RunMode.SANDBOX);
        int providerCalls = STUB.received().size();
        var replay = replayer(source.runId());

        var response = chat(replay.token(), request(LlmFixtures.CLAUDE, "never seen", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("REPLAY_MISS");
        assertThat(STUB.received()).hasSize(providerCalls);
    }

    @Test
    void aReplayRunWithoutASourceIsAMissToo() {
        var replay = replayer(null);

        var response = chat(replay.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("REPLAY_MISS");
        assertThat(STUB.received()).isEmpty();
    }

    @Test
    void aSourceThatNeverRecordedIsAMiss() {
        var source = recordedSource(RunMode.PRODUCTION);   // production calls are never recorded
        int providerCalls = STUB.received().size();
        var replay = replayer(source.runId());

        var response = chat(replay.token(), request(LlmFixtures.CLAUDE, "same", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("REPLAY_MISS");
        assertThat(STUB.received()).hasSize(providerCalls);
    }

    @Test
    void anExpiredRecordingIsGone() throws Exception {
        var source = recordedSource(RunMode.SANDBOX);
        jdbc.update("UPDATE llm_call SET recording_expires_at = now() - interval '1 day' WHERE run_id = ?", source.runId());
        var replay = replayer(source.runId());

        var response = chat(replay.token(), request(LlmFixtures.CLAUDE, "same", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody().get("code")).isEqualTo("REPLAY_EXPIRED");
        var row = llmCalls.findByRunIdOrderByCreatedAtAsc(replay.runId()).get(0);
        assertThat(row.getStatus()).isEqualTo(LlmCallStatus.ERROR);
        assertThat(row.getErrorCode()).isEqualTo("REPLAY_EXPIRED");
        assertThat(row.getReplayOrdinal()).isNull();
    }

    @Test
    void aPurgedRecordingIsGoneToo() {
        var source = recordedSource(RunMode.SANDBOX);
        jdbc.update("UPDATE llm_call SET recording_expires_at = now() - interval '1 day' WHERE run_id = ?", source.runId());
        retention.purgeOnce();
        var replay = replayer(source.runId());

        var response = chat(replay.token(), request(LlmFixtures.CLAUDE, "same", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody().get("code")).isEqualTo("REPLAY_EXPIRED");
    }

    @Test
    void concurrentDuplicatesNeverShareAnOrdinal() throws Exception {
        var source = recordedSource(RunMode.SANDBOX);   // two recordings of "same"
        var replay = replayer(source.runId());
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<Map>>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return chat(replay.token(), request(LlmFixtures.CLAUDE, "same", 100));
            }));
        }
        start.countDown();
        List<String> served = new ArrayList<>();
        int misses = 0;
        for (Future<ResponseEntity<Map>> result : results) {
            ResponseEntity<Map> response = result.get(60, TimeUnit.SECONDS);
            if (response.getStatusCode() == HttpStatus.OK) {
                served.add(textOf(response));
            } else if (response.getStatusCode() == HttpStatus.CONFLICT) {
                misses++;
            }
        }
        pool.shutdown();

        assertThat(served).containsExactlyInAnyOrder("first", "second");
        assertThat(misses).isEqualTo(2);
        var ordinals = llmCalls.findByRunIdOrderByCreatedAtAsc(replay.runId()).stream()
                .filter(c -> c.getStatus() == LlmCallStatus.OK).map(LlmCall::getReplayOrdinal).toList();
        assertThat(ordinals).containsExactlyInAnyOrder(1, 2);
    }
}
