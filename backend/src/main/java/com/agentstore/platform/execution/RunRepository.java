package com.agentstore.platform.execution;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunRepository extends JpaRepository<Run, UUID> {
    List<Run> findByConnectionBuyerOrgIdOrderByStartedAtDesc(UUID buyerOrgId);

    List<Run> findByConnectionBuyerOrgIdAndStatusOrderByStartedAtDesc(UUID buyerOrgId, RunStatus status);

    List<Run> findByStatusIn(Collection<RunStatus> statuses);

    /** Row-locks the run for the rest of the caller's transaction. Every state transition goes through this. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Run r where r.id = :id")
    Optional<Run> findByIdForUpdate(@Param("id") UUID id);
}
