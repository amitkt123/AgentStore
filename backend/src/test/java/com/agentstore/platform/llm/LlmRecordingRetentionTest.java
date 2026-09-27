package com.agentstore.platform.llm;

import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.llm.StubProviderServer.Reply;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LlmRecordingRetentionTest extends AbstractLlmIntegrationTest {

    @Autowired LlmRecordingRetentionService retention;

    LlmCall recordedCall() {
        var agent = fx.runningAgent("valid-llm-platform.json");
        fx.configureRun(agent.runId(), RunMode.SANDBOX, null, null, null);
        STUB.enqueue(Reply.anthropicText("answer", 1, 1));
        chat(agent.token(), request(LlmFixtures.CLAUDE, "question " + UUID.randomUUID(), 100));
        return llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId()).get(0);
    }

    void expire(LlmCall call) {
        jdbc.update("UPDATE llm_call SET recording_expires_at = now() - interval '1 hour' WHERE id = ?", call.getId());
    }

    @Test
    void anExpiredRecordingIsDeletedAndStampedAndAnUnexpiredOneSurvives() {
        LlmCall expired = recordedCall();
        LlmCall fresh = recordedCall();
        expire(expired);
        assertThat(storage.size(expired.getRequestKey())).isPresent();

        int purged = retention.purgeOnce();

        assertThat(purged).isGreaterThanOrEqualTo(1);
        LlmCall after = llmCalls.findById(expired.getId()).orElseThrow();
        assertThat(after.getRecordingPurgedAt()).isNotNull();
        assertThat(after.getRequestKey()).isNull();
        assertThat(after.getResponseKey()).isNull();
        assertThat(storage.size(expired.getRequestKey())).isEmpty();
        assertThat(storage.size(expired.getResponseKey())).isEmpty();
        assertThat(after.getStatus()).as("the call row itself stays").isEqualTo(LlmCallStatus.OK);

        LlmCall untouched = llmCalls.findById(fresh.getId()).orElseThrow();
        assertThat(untouched.getRecordingPurgedAt()).isNull();
        assertThat(storage.size(fresh.getRequestKey())).isPresent();
        assertThat(storage.size(fresh.getResponseKey())).isPresent();
    }

    @Test
    void purgingTwiceDoesNothingTheSecondTime() {
        LlmCall call = recordedCall();
        expire(call);
        retention.purgeOnce();
        Instant stamp = llmCalls.findById(call.getId()).orElseThrow().getRecordingPurgedAt();

        assertThat(retention.purgeOnce()).isZero();

        assertThat(llmCalls.findById(call.getId()).orElseThrow().getRecordingPurgedAt()).isEqualTo(stamp);
    }

    @Test
    void callsWithoutARecordingAreIgnored() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");   // PRODUCTION: nothing recorded
        STUB.enqueue(Reply.anthropicText("answer", 1, 1));
        chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        retention.purgeOnce(Instant.now().plus(Duration.ofDays(30)));

        LlmCall call = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId()).get(0);
        assertThat(call.getRecordingPurgedAt()).isNull();
    }

    @Test
    void theClockIsTheBoundary() {
        retention.purgeOnce();   // the database is shared: clear whatever earlier tests left expired
        LlmCall call = recordedCall();

        assertThat(retention.purgeOnce(Instant.now().plus(Duration.ofDays(13)))).isZero();
        assertThat(llmCalls.findById(call.getId()).orElseThrow().getRecordingPurgedAt()).isNull();

        assertThat(retention.purgeOnce(Instant.now().plus(Duration.ofDays(15)))).isGreaterThanOrEqualTo(1);
        assertThat(llmCalls.findById(call.getId()).orElseThrow().getRecordingPurgedAt()).isNotNull();
    }
}
