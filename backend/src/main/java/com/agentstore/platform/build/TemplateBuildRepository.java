package com.agentstore.platform.build;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TemplateBuildRepository extends JpaRepository<TemplateBuild, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from TemplateBuild b where b.id = :id")
    Optional<TemplateBuild> findByIdForUpdate(@Param("id") UUID id);

    Optional<TemplateBuild> findFirstByStatusOrderByQueuedAtAsc(BuildStatus status);

    List<TemplateBuild> findByStatusOrderByQueuedAtAsc(BuildStatus status);

    boolean existsByStatus(BuildStatus status);

    List<TemplateBuild> findByAgentVersionIdAndStatus(UUID agentVersionId, BuildStatus status);

    Optional<TemplateBuild> findFirstByAgentVersionIdOrderByQueuedAtDesc(UUID agentVersionId);

    Optional<TemplateBuild> findFirstByBundleIdOrderByQueuedAtDesc(UUID bundleId);

    Optional<TemplateBuild> findFirstByAgentVersionIdAndStatusOrderByFinishedAtDesc(UUID agentVersionId, BuildStatus status);

    boolean existsByAgentVersionIdAndBackendAndStatusIn(UUID agentVersionId, String backend, Collection<BuildStatus> statuses);
}
