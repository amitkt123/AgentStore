package com.agentstore.platform.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Deletes the blobs of recordings whose 14 days are up and stamps {@code recording_purged_at}. The call row stays
 * (its tokens and cost are still the run's history); replay treats a purged recording as expired.
 */
@Service
public class LlmRecordingRetentionService {

    private static final Logger log = LoggerFactory.getLogger(LlmRecordingRetentionService.class);
    private static final int BATCH = 500;

    private final LlmCallRepository calls;
    private final LlmRecorder recorder;

    public LlmRecordingRetentionService(LlmCallRepository calls, LlmRecorder recorder) {
        this.calls = calls;
        this.recorder = recorder;
    }

    public int purgeOnce() {
        return purgeOnce(Instant.now());
    }

    /** Returns how many recordings were purged; the next run takes the next batch if more than 500 were due. */
    public int purgeOnce(Instant now) {
        int purged = 0;
        for (LlmCall call : calls.findRecordingsDueForPurge(now, PageRequest.of(0, BATCH))) {
            try {
                recorder.delete(call.getRequestKey());
                recorder.delete(call.getResponseKey());
                call.setRequestKey(null);
                call.setResponseKey(null);
                call.setRecordingPurgedAt(Instant.now());
                calls.save(call);
                purged++;
            } catch (RuntimeException e) {
                log.warn("Could not purge the recording of LLM call {}", call.getId());   // the next run retries it
            }
        }
        return purged;
    }
}
