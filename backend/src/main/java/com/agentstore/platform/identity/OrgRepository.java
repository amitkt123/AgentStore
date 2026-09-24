package com.agentstore.platform.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface OrgRepository extends JpaRepository<Org, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Org o where o.id = :id")
    Optional<Org> findByIdForUpdate(@Param("id") UUID id);
}
