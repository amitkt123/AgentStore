package com.agentstore.platform.llm;

import com.agentstore.platform.storage.ObjectStorage;
import com.agentstore.platform.storage.StorageException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;

/**
 * Request and response blobs of SANDBOX calls, in the object store under {@code llm/{runId}/{callId}/}. Callers decide
 * which calls are recorded; this class never sees a key (only normalized requests and responses) and never fails a call.
 */
@Component
public class LlmRecorder {

    private static final Logger log = LoggerFactory.getLogger(LlmRecorder.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ObjectStorage storage;
    private final ObjectMapper mapper;
    private final LlmProperties properties;

    public LlmRecorder(ObjectStorage storage, ObjectMapper mapper, LlmProperties properties) {
        this.storage = storage;
        this.mapper = mapper;
        this.properties = properties;
    }

    /** Writes both blobs and sets the call's keys and expiry; on any failure logs, cleans up and leaves the keys null. */
    public void attach(LlmCall call, LlmRequest request, LlmResponse response) {
        String base = "llm/" + call.getRunId() + "/" + call.getId() + "/";
        String requestKey = base + "request.json";
        String responseKey = base + "response.json";
        try {
            storage.put(requestKey, mapper.writeValueAsBytes(request.toMap()));
            storage.put(responseKey, mapper.writeValueAsBytes(response.toMap()));
        } catch (StorageException | JsonProcessingException e) {
            storage.deleteQuietly(requestKey);
            storage.deleteQuietly(responseKey);
            log.warn("Could not record LLM call {} of run {}", call.getId(), call.getRunId());
            return;
        }
        call.setRequestKey(requestKey);
        call.setResponseKey(responseKey);
        call.setRecordingExpiresAt(Instant.now().plus(properties.recordingTtl()));
    }

    public LlmResponse readResponse(String key, String fallbackModel) {
        try (InputStream in = storage.open(key)) {
            return LlmResponse.fromMap(mapper.readValue(in, MAP), fallbackModel);
        } catch (IOException | IllegalArgumentException e) {
            throw new StorageException("Could not read a recorded response", e);
        }
    }

    /** Removes a recording that was written for a row that will not be saved. */
    public void discard(LlmCall call) {
        delete(call.getRequestKey());
        delete(call.getResponseKey());
        call.setRequestKey(null);
        call.setResponseKey(null);
        call.setRecordingExpiresAt(null);
    }

    public void delete(String key) {
        if (key != null) {
            storage.deleteQuietly(key);
        }
    }
}
