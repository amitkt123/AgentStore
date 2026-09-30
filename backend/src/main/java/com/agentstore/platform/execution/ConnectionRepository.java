package com.agentstore.platform.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConnectionRepository extends JpaRepository<Connection, UUID> {
    List<Connection> findByBuyerOrgId(UUID buyerOrgId);

    /** Row-locks the connection for the rest of the caller's transaction, so its monthly limit is checked one run at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Connection c where c.id = :id")
    Optional<Connection> findByIdForUpdate(@Param("id") UUID id);
}
