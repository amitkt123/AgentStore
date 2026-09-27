package com.agentstore.platform.execution;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunRepository extends JpaRepository<Run, UUID> {
    List<Run> findByConnectionBuyerOrgIdOrderByStartedAtDesc(UUID buyerOrgId);

    List<Run> findByConnectionBuyerOrgIdAndStatusOrderByStartedAtDesc(UUID buyerOrgId, RunStatus status);

    List<Run> findByStatusIn(Collection<RunStatus> statuses);

    List<Run> findByStatusAndAgentVersionId(RunStatus status, UUID agentVersionId);

    Optional<Run> findFirstByCallbackTokenHash(String callbackTokenHash);

    long countByModeAndInitiatedByOrgIdAndStatusIn(RunMode mode, java.util.UUID orgId, Collection<RunStatus> statuses);

    org.springframework.data.domain.Page<Run> findByModeAndInitiatedByOrgIdOrderByStartedAtDesc(RunMode mode,
            java.util.UUID orgId, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<Run> findByModeAndInitiatedByOrgIdAndAgentVersionAgentIdOrderByStartedAtDesc(
            RunMode mode, java.util.UUID orgId, java.util.UUID agentId, org.springframework.data.domain.Pageable pageable);

    @Query(value = "SELECT COALESCE(SUM(EXTRACT(EPOCH FROM (COALESCE(completed_at, now()) - started_at))), 0) FROM run "
            + "WHERE mode = 'SANDBOX' AND initiated_by_org_id = :orgId AND started_at >= :since AND status <> 'PENDING_BUILD'",
            nativeQuery = true)
    BigDecimal sandboxRunSecondsSince(@Param("orgId") java.util.UUID orgId, @Param("since") Instant since);

    @Transactional
    @Modifying
    @Query("update Run r set r.callbackTokenHash = :hash where r.id = :id")
    int updateCallbackTokenHash(@Param("id") UUID id, @Param("hash") String hash);

    /** Row-locks the run for the rest of the caller's transaction. Every state transition goes through this. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Run r where r.id = :id")
    Optional<Run> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Reserves LLM tokens for a call if they fit the run's budget: one conditional statement, so concurrent calls can
     * never oversubscribe {@code maxTokensPerRun}. Returns 1 when reserved, 0 when it does not fit.
     */
    @Transactional
    @Modifying
    @Query(value = "UPDATE run SET llm_tokens_reserved = llm_tokens_reserved + :tokens "
            + "WHERE id = :id AND llm_tokens_used + llm_tokens_reserved + :tokens <= :max", nativeQuery = true)
    int reserveLlmTokens(@Param("id") UUID id, @Param("tokens") int tokens, @Param("max") long max);

    /** Moves a reservation to actual usage in one statement (release = {@code used} of 0). */
    @Transactional
    @Modifying
    @Query(value = "UPDATE run SET llm_tokens_reserved = llm_tokens_reserved - :reserved, "
            + "llm_tokens_used = llm_tokens_used + :used WHERE id = :id", nativeQuery = true)
    int settleLlmTokens(@Param("id") UUID id, @Param("reserved") int reserved, @Param("used") int used);
}
