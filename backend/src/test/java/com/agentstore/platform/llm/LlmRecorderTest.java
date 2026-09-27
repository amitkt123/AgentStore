package com.agentstore.platform.llm;

import com.agentstore.platform.storage.ObjectStorage;
import com.agentstore.platform.storage.StorageException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LlmRecorderTest {

    final ObjectStorage storage = mock(ObjectStorage.class);
    final LlmRecorder recorder = new LlmRecorder(storage, new ObjectMapper(), LlmKeyResolverTest.props("", ""));
    final LlmRequest request = new LlmRequest("m", null,
            List.of(new LlmRequest.Msg("user", List.of(Map.of("type", "text", "text", "hi")))), List.of(), 100, null);
    final LlmResponse response = new LlmResponse("m", List.of(Map.of("type", "text", "text", "ok")), "end_turn", 1, 1);

    LlmCall call() {
        return new LlmCall(UUID.randomUUID(), UUID.randomUUID(), "m", LlmSource.PLATFORM, LlmCallStatus.OK, "a".repeat(64));
    }

    @Test
    void aStorageFailureNeverFailsTheCallAndLeavesNoKeys() {
        doThrow(new StorageException("down", null)).when(storage).put(anyString(), any(byte[].class));
        LlmCall call = call();

        recorder.attach(call, request, response);

        assertThat(call.getRequestKey()).isNull();
        assertThat(call.getResponseKey()).isNull();
        assertThat(call.getRecordingExpiresAt()).isNull();
        verify(storage).deleteQuietly("llm/" + call.getRunId() + "/" + call.getId() + "/request.json");
        verify(storage).deleteQuietly("llm/" + call.getRunId() + "/" + call.getId() + "/response.json");
    }

    @Test
    void aSuccessfulAttachSetsTheKeysAndAFourteenDayExpiry() {
        LlmCall call = call();

        recorder.attach(call, request, response);

        assertThat(call.getRequestKey()).isEqualTo("llm/" + call.getRunId() + "/" + call.getId() + "/request.json");
        assertThat(call.getResponseKey()).isEqualTo("llm/" + call.getRunId() + "/" + call.getId() + "/response.json");
        assertThat(call.getRecordingExpiresAt()).isAfter(java.time.Instant.now().plus(java.time.Duration.ofDays(14)).minusSeconds(60))
                .isBefore(java.time.Instant.now().plus(java.time.Duration.ofDays(14)).plusSeconds(60));
    }
}
