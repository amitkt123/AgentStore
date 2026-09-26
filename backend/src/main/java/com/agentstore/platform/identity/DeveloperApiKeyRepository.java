package com.agentstore.platform.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeveloperApiKeyRepository extends JpaRepository<DeveloperApiKey, UUID> {

    Optional<DeveloperApiKey> findByKeyHash(String keyHash);

    List<DeveloperApiKey> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    Optional<DeveloperApiKey> findByIdAndOrgId(UUID id, UUID orgId);

    /** Records a use at most once per throttle window: the row is only written when the last use is older than {@code threshold}. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update DeveloperApiKey k set k.lastUsedAt = :now "
            + "where k.id = :id and (k.lastUsedAt is null or k.lastUsedAt < :threshold)")
    int touch(@Param("id") UUID id, @Param("now") Instant now, @Param("threshold") Instant threshold);
}
