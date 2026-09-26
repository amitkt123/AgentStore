package com.agentstore.platform.catalog;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentRepository extends JpaRepository<Agent, UUID> {
    List<Agent> findByDeveloperOrgId(UUID developerOrgId);

    /** Serializes writers of one agent's versions (bundle finalize takes this lock before any bundle lock). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Agent a where a.id = :id")
    Optional<Agent> findByIdForUpdate(@Param("id") UUID id);
}
