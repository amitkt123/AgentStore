package com.agentstore.platform.data;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunOutputRepository extends JpaRepository<RunOutput, UUID> {

    List<RunOutput> findByRunIdOrderByCreatedAtAsc(UUID runId);

    Optional<RunOutput> findByIdAndRunId(UUID id, UUID runId);

    List<RunOutput> findByStatusAndCreatedAtBeforeAndStorageKeyIsNotNull(OutputStatus status, Instant before);

    @Query("select coalesce(sum(o.sizeBytes), 0) from RunOutput o where o.runId = :runId")
    long totalBytes(@Param("runId") UUID runId);

    /** Moves the run's STAGED rows for these resources, staged no later than the gate was requested. */
    @Modifying(flushAutomatically = true)
    @Query("update RunOutput o set o.status = :to, o.gateRequestId = :gateId "
            + "where o.runId = :runId and o.status = :from and o.resourceName in :resources "
            + "and o.createdAt <= :requestedAt")
    int transition(@Param("runId") UUID runId, @Param("from") OutputStatus from, @Param("to") OutputStatus to,
                   @Param("gateId") UUID gateId, @Param("resources") Collection<String> resources,
                   @Param("requestedAt") Instant requestedAt);

    @Modifying(flushAutomatically = true)
    @Query("update RunOutput o set o.status = com.agentstore.platform.data.OutputStatus.DISCARDED "
            + "where o.runId = :runId and o.status = com.agentstore.platform.data.OutputStatus.STAGED")
    int discardStaged(@Param("runId") UUID runId);
}
