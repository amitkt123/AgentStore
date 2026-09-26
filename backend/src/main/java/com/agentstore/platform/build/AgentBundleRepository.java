package com.agentstore.platform.build;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AgentBundleRepository extends JpaRepository<AgentBundle, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from AgentBundle b where b.id = :id")
    Optional<AgentBundle> findByIdForUpdate(@Param("id") UUID id);
}
