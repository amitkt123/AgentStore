package com.agentstore.platform.llm;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LlmCallRepository extends JpaRepository<LlmCall, UUID> {

    List<LlmCall> findByRunIdOrderByCreatedAtAsc(UUID runId);

    /** The highest replay ordinal this run has already consumed for a request hash (0 when none). */
    @Query("select coalesce(max(c.replayOrdinal), 0) from LlmCall c where c.runId = :runId and c.requestHash = :hash")
    int maxReplayOrdinal(@Param("runId") UUID runId, @Param("hash") String hash);

    /**
     * The source run's recorded, successful calls for a hash in recorded order (created_at, id), skipping mock calls
     * and anything that was never recorded. Page {@code n-1} of size 1 is the n-th recorded call.
     */
    @Query("select c from LlmCall c where c.runId = :runId and c.requestHash = :hash and c.status = :status "
            + "and c.source <> :excluded and c.recordingExpiresAt is not null order by c.createdAt asc, c.id asc")
    List<LlmCall> findReplayable(@Param("runId") UUID runId, @Param("hash") String hash,
                                 @Param("status") LlmCallStatus status, @Param("excluded") LlmSource excluded,
                                 Pageable pageable);

    @Query("select c from LlmCall c where c.recordingExpiresAt < :now and c.recordingPurgedAt is null "
            + "order by c.recordingExpiresAt asc")
    List<LlmCall> findRecordingsDueForPurge(@Param("now") Instant now, Pageable pageable);
}
