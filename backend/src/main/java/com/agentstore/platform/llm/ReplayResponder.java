package com.agentstore.platform.llm;

import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.runtime.RunPrincipal;
import com.agentstore.platform.runtime.RuntimeApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Serves REPLAY runs from a source run's recordings. The n-th call with a given request hash gets the n-th recorded OK
 * call with that hash (ordered by created_at, id); the unique index on (run, hash, ordinal) makes a concurrent
 * duplicate retry with the next n. A miss never falls through to a provider.
 */
@Component
public class ReplayResponder {

    private static final int MAX_CLAIM_ATTEMPTS = 5;

    private final LlmCallRepository calls;
    private final LlmCallLog callLog;
    private final LlmRecorder recorder;

    public ReplayResponder(LlmCallRepository calls, LlmCallLog callLog, LlmRecorder recorder) {
        this.calls = calls;
        this.callLog = callLog;
        this.recorder = recorder;
    }

    public LlmResponse serve(RunPrincipal principal, UUID sourceRunId, LlmModel model,
                             LlmRequestNormalizer.Normalized normalized) {
        UUID runId = principal.runId();
        String hash = normalized.hash();
        long started = System.nanoTime();
        if (sourceRunId == null) {
            throw miss(principal, model, hash, started);
        }
        for (int attempt = 0; attempt < MAX_CLAIM_ATTEMPTS; attempt++) {
            int ordinal = calls.maxReplayOrdinal(runId, hash) + 1;
            List<LlmCall> recorded = calls.findReplayable(sourceRunId, hash, LlmCallStatus.OK, LlmSource.MOCK,
                    PageRequest.of(ordinal - 1, 1));
            if (recorded.isEmpty()) {
                throw miss(principal, model, hash, started);
            }
            LlmCall source = recorded.get(0);
            if (source.getRecordingPurgedAt() != null || source.getRecordingExpiresAt().isBefore(Instant.now())) {
                throw expired(principal, model, hash, started);
            }
            LlmResponse response = recorder.readResponse(source.getResponseKey(), model.getId());
            LlmCall call = new LlmCall(UUID.randomUUID(), runId, model.getId(), LlmSource.REPLAY, LlmCallStatus.OK, hash);
            call.setReplayOrdinal(ordinal);
            call.setInputTokens(source.getInputTokens());
            call.setOutputTokens(source.getOutputTokens());
            call.setLatencyMs(millisSince(started));
            if (principal.mode() == RunMode.SANDBOX) {
                recorder.attach(call, normalized.request(), response);
            }
            try {
                callLog.record(principal, call);
                return response;
            } catch (DataIntegrityViolationException e) {
                recorder.discard(call);   // another call took this ordinal first: try the next one
            }
        }
        throw new RuntimeApiException(HttpStatus.CONFLICT, "REPLAY_MISS",
                "Could not claim a recorded response for request " + hash + "; retry");
    }

    private RuntimeApiException miss(RunPrincipal principal, LlmModel model, String hash, long started) {
        LlmCall row = new LlmCall(UUID.randomUUID(), principal.runId(), model.getId(), LlmSource.REPLAY,
                LlmCallStatus.REPLAY_MISS, hash);
        row.setErrorCode("REPLAY_MISS");
        row.setLatencyMs(millisSince(started));
        callLog.record(principal, row);
        return new RuntimeApiException(HttpStatus.CONFLICT, "REPLAY_MISS", "No recorded response for request " + hash,
                List.of(Map.of("requestHash", hash)));
    }

    private RuntimeApiException expired(RunPrincipal principal, LlmModel model, String hash, long started) {
        LlmCall row = new LlmCall(UUID.randomUUID(), principal.runId(), model.getId(), LlmSource.REPLAY,
                LlmCallStatus.ERROR, hash);
        row.setErrorCode("REPLAY_EXPIRED");
        row.setLatencyMs(millisSince(started));
        callLog.record(principal, row);
        return new RuntimeApiException(HttpStatus.GONE, "REPLAY_EXPIRED",
                "The recording for request " + hash + " has expired");
    }

    private static int millisSince(long startedNanos) {
        return (int) ((System.nanoTime() - startedNanos) / 1_000_000);
    }
}
